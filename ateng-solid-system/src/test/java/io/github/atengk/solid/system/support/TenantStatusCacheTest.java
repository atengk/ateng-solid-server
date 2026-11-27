package io.github.atengk.solid.system.support;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.redis.cache.template.RedisCacheTemplate;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.properties.SolidTenantProperties;
import io.github.atengk.solid.system.api.dto.TenantAuthDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static io.github.atengk.solid.system.support.CacheTestFixtures.adaptiveSupport;
import static io.github.atengk.solid.system.support.CacheTestFixtures.emptyEnvProvider;
import static io.github.atengk.solid.system.support.CacheTestFixtures.offlineAdaptiveSupport;
import static io.github.atengk.solid.system.support.CacheTestFixtures.redisDisabledEnvProvider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link TenantStatusCache} 租户状态纯 L2 缓存与离线降级 Layer 1 单元测试。
 *
 * @author Ateng
 * @since 2026-11-25
 */
class TenantStatusCacheTest {

    private static SolidTenantProperties defaultProps() {
        SolidTenantProperties props = new SolidTenantProperties();
        props.setEnabled(true);
        props.setStatusCacheTtl(Duration.ofSeconds(60));
        return props;
    }

    @Test
    @DisplayName("传入空租户 ID 时应当直接返回 null 且不触发回源")
    void shouldReturnNullWhenTenantIdIsNull() {
        TenantStatusCache cache = new TenantStatusCache(offlineAdaptiveSupport(), defaultProps());
        AtomicInteger calls = new AtomicInteger();

        TenantAuthDTO result = cache.getOrLoad(null, id -> {
            calls.incrementAndGet();
            return new TenantAuthDTO();
        });

        assertThat(result).isNull();
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("无 Redis 降级模式下应当命中本地有界缓存并在 evict 后重新回源")
    void shouldCacheAndEvictInBoundedLocalFallbackMode() {
        TenantStatusCache cache = new TenantStatusCache(offlineAdaptiveSupport(), defaultProps());
        AtomicInteger calls = new AtomicInteger();

        TenantAuthDTO first = cache.getOrLoad(1001L, id -> {
            calls.incrementAndGet();
            return TenantAuthDTO.builder()
                    .tenantId(id)
                    .tenantCode("T1001")
                    .tenantName("租户1001")
                    .status(CommonStatusEnum.NORMAL.getCode())
                    .expireTime(LocalDateTime.now().plusHours(1))
                    .build();
        });
        TenantAuthDTO second = cache.getOrLoad(1001L, id -> {
            calls.incrementAndGet();
            return null;
        });

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(second.getTenantName()).isEqualTo("租户1001");
        assertThat(calls.get()).isEqualTo(1);

        cache.evict(1001L);
        cache.getOrLoad(1001L, id -> {
            calls.incrementAndGet();
            return TenantAuthDTO.builder()
                    .tenantId(id)
                    .tenantCode("T1001")
                    .tenantName("租户1001-已刷新")
                    .status(CommonStatusEnum.NORMAL.getCode())
                    .build();
        });

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("不存在租户应当写入本地负向哨兵防穿透并在读取时实现 0 秒过期截断")
    void shouldCacheNegativeSentinelAndEnforceZeroSecondExpirationTruncation() {
        TenantStatusCache cache = new TenantStatusCache(offlineAdaptiveSupport(), defaultProps());
        AtomicInteger missingCalls = new AtomicInteger();

        assertThat(cache.getOrLoad(9999L, id -> {
            missingCalls.incrementAndGet();
            return null;
        })).isNull();
        assertThat(cache.getOrLoad(9999L, id -> {
            missingCalls.incrementAndGet();
            return null;
        })).isNull();
        assertThat(missingCalls.get()).isEqualTo(1);

        // 即使仍在 60s 缓存期内，一旦当前时刻跨过 expireTime 读时判定立即可用性为 false
        TenantAuthDTO alreadyExpired = cache.getOrLoad(2002L, id -> TenantAuthDTO.builder()
                .tenantId(id)
                .tenantCode("T2002")
                .status(CommonStatusEnum.NORMAL.getCode())
                .expireTime(LocalDateTime.now().minusSeconds(1))
                .build());
        assertThat(alreadyExpired).isNotNull();
        assertThat(alreadyExpired.isAvailableAt(LocalDateTime.now())).isFalse();
    }

    @Test
    @DisplayName("本地有界缓存超过 10000 条上限时应当自动淘汰旧键维持容量不溢出")
    void shouldEnforceTenThousandLocalCacheCapacityCap() {
        TenantStatusCache cache = new TenantStatusCache(offlineAdaptiveSupport(), defaultProps());

        for (long i = 1; i <= 10_050; i++) {
            cache.getOrLoad(i, id -> TenantAuthDTO.builder()
                    .tenantId(id)
                    .tenantCode("T" + id)
                    .status(CommonStatusEnum.NORMAL.getCode())
                    .build());
        }

        assertThat(cache.localSize()).isLessThanOrEqualTo(TenantStatusCache.LOCAL_CACHE_MAX_CAPACITY);
    }

    @Test
    @DisplayName("Redis 启用时应当以领域相对键委派纯 L2 RedisCacheTemplate 并支持主动驱逐")
    @SuppressWarnings("unchecked")
    void shouldDelegateToRedisCacheTemplateWithRelativeKeyAndEvict() {
        RedisCacheTemplate redisCacheTemplate = mock(RedisCacheTemplate.class);
        TenantStatusCache cache = new TenantStatusCache(
                adaptiveSupport(null, redisCacheTemplate, emptyEnvProvider()),
                defaultProps()
        );

        String key = TenantStatusCache.buildCacheKey(3001L);
        assertThat(key).isEqualTo("tenant:status:3001");
        assertThat(key).doesNotContain("ateng:solid:");

        LocalDateTime futureExpire = LocalDateTime.of(2099, 12, 31, 23, 59, 59);
        TenantAuthDTO expectedDto = TenantAuthDTO.builder()
                .tenantId(3001L)
                .tenantCode("T3001")
                .tenantName("企业A")
                .packageId(88L)
                .expireTime(futureExpire)
                .status(CommonStatusEnum.NORMAL.getCode())
                .build();

        when(redisCacheTemplate.getOrLoad(
                eq(key),
                eq(TenantAuthDTO.class),
                any(Function.class),
                eq(Duration.ofSeconds(60)),
                isNull()
        )).thenAnswer(inv -> {
            Function<String, TenantAuthDTO> fn = inv.getArgument(2);
            return fn.apply(inv.getArgument(0));
        });

        TenantAuthDTO loaded = cache.getOrLoad(3001L, id -> expectedDto);
        assertThat(loaded).isEqualTo(expectedDto);
        verify(redisCacheTemplate).getOrLoad(
                eq(key),
                eq(TenantAuthDTO.class),
                any(Function.class),
                eq(Duration.ofSeconds(60)),
                isNull()
        );

        cache.evict(3001L);
        verify(redisCacheTemplate, times(1)).evict(key);
    }

    @Test
    @DisplayName("RedisCacheTemplate 命中 DTO 或 L2 空哨兵（null）时应当直接返回且不调用 loader")
    @SuppressWarnings("unchecked")
    void shouldReturnL2CachedDtoOrNullSentinelDirectlyWithoutInvokingLoader() {
        RedisCacheTemplate redisCacheTemplate = mock(RedisCacheTemplate.class);
        TenantStatusCache cache = new TenantStatusCache(
                adaptiveSupport(null, redisCacheTemplate, emptyEnvProvider()),
                defaultProps()
        );

        String hitKey = TenantStatusCache.buildCacheKey(3002L);
        TenantAuthDTO cachedDto = TenantAuthDTO.builder()
                .tenantId(3002L)
                .tenantCode("T3002")
                .status(CommonStatusEnum.NORMAL.getCode())
                .build();
        when(redisCacheTemplate.getOrLoad(
                eq(hitKey),
                eq(TenantAuthDTO.class),
                any(Function.class),
                eq(Duration.ofSeconds(60)),
                isNull()
        )).thenReturn(cachedDto);

        AtomicInteger calls = new AtomicInteger();
        assertThat(cache.getOrLoad(3002L, id -> {
            calls.incrementAndGet();
            return null;
        })).isEqualTo(cachedDto);

        String sentinelKey = TenantStatusCache.buildCacheKey(3003L);
        when(redisCacheTemplate.getOrLoad(
                eq(sentinelKey),
                eq(TenantAuthDTO.class),
                any(Function.class),
                eq(Duration.ofSeconds(60)),
                isNull()
        )).thenReturn(null);
        assertThat(cache.getOrLoad(3003L, id -> {
            calls.incrementAndGet();
            return cachedDto;
        })).isNull();

        assertThat(calls.get()).isZero();
        assertThat(cache.localSize()).isZero();
    }

    @Test
    @DisplayName("RedisCacheTemplate 非连接异常时应当尝试清理脏键并降级本地有界缓存（即使 evict 也失败）")
    @SuppressWarnings("unchecked")
    void shouldCleanDirtyKeyAndFallbackToLocalOnRedisError() {
        RedisCacheTemplate redisCacheTemplate = mock(RedisCacheTemplate.class);
        TenantStatusCache cache = new TenantStatusCache(
                adaptiveSupport(null, redisCacheTemplate, emptyEnvProvider()),
                defaultProps()
        );

        String outageKey = TenantStatusCache.buildCacheKey(5005L);
        TenantAuthDTO dto = TenantAuthDTO.builder()
                .tenantId(5005L)
                .tenantCode("T5005")
                .status(CommonStatusEnum.NORMAL.getCode())
                .build();

        when(redisCacheTemplate.getOrLoad(
                eq(outageKey),
                eq(TenantAuthDTO.class),
                any(Function.class),
                eq(Duration.ofSeconds(60)),
                isNull()
        )).thenThrow(new IllegalArgumentException("redis error"));
        doThrow(new RuntimeException("evict failed")).when(redisCacheTemplate).evict(outageKey);

        AtomicInteger loaderCalls = new AtomicInteger();
        assertThat(cache.getOrLoad(5005L, id -> {
            loaderCalls.incrementAndGet();
            return dto;
        })).isEqualTo(dto);
        // 第 2 次继续故障时直接命中本地降级缓存，不再重复打库
        assertThat(cache.getOrLoad(5005L, id -> {
            loaderCalls.incrementAndGet();
            return null;
        })).isEqualTo(dto);
        assertThat(loaderCalls.get()).isEqualTo(1);
        verify(redisCacheTemplate, times(2)).evict(outageKey);
    }

    @Test
    @DisplayName("Redis 连接或超时异常时应当跳过冗余远程驱逐并直接降级本地缓存")
    @SuppressWarnings("unchecked")
    void shouldSkipRemoteEvictOnRedisTimeoutOutage() {
        RedisCacheTemplate redisCacheTemplate = mock(RedisCacheTemplate.class);
        TenantStatusCache cache = new TenantStatusCache(
                adaptiveSupport(null, redisCacheTemplate, emptyEnvProvider()),
                defaultProps()
        );

        String timeoutKey = TenantStatusCache.buildCacheKey(5006L);
        TenantAuthDTO dto = TenantAuthDTO.builder()
                .tenantId(5006L)
                .tenantCode("T5006")
                .status(CommonStatusEnum.NORMAL.getCode())
                .build();

        when(redisCacheTemplate.getOrLoad(
                eq(timeoutKey),
                eq(TenantAuthDTO.class),
                any(Function.class),
                eq(Duration.ofSeconds(60)),
                isNull()
        )).thenThrow(new QueryTimeoutException("redis timeout"));

        assertThat(cache.getOrLoad(5006L, id -> dto)).isEqualTo(dto);
        verify(redisCacheTemplate, never()).evict(timeoutKey);
    }

    @Test
    @DisplayName("回源业务异常不得降级吞没，且应当遵循自定义与兜底 TTL")
    @SuppressWarnings("unchecked")
    void shouldPropagateBusinessExceptionAndResolveCustomOrDefaultStatusTtl() {
        RedisCacheTemplate redisCacheTemplate = mock(RedisCacheTemplate.class);
        SolidTenantProperties customProps = defaultProps();
        customProps.setStatusCacheTtl(Duration.ofSeconds(120));

        TenantStatusCache customCache = new TenantStatusCache(
                adaptiveSupport(null, redisCacheTemplate, emptyEnvProvider()),
                customProps
        );

        String customKey = TenantStatusCache.buildCacheKey(6006L);
        when(redisCacheTemplate.getOrLoad(
                eq(customKey),
                eq(TenantAuthDTO.class),
                any(Function.class),
                eq(Duration.ofSeconds(120)),
                isNull()
        )).thenAnswer(inv -> {
            Function<String, TenantAuthDTO> fn = inv.getArgument(2);
            return fn.apply(inv.getArgument(0));
        });
        AtomicInteger bizErrCalls = new AtomicInteger();
        assertThatThrownBy(() -> customCache.getOrLoad(6006L, id -> {
            bizErrCalls.incrementAndGet();
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "db error");
        })).isInstanceOf(BusinessException.class);
        assertThat(bizErrCalls.get()).isEqualTo(1);

        SolidTenantProperties zeroProps = defaultProps();
        zeroProps.setStatusCacheTtl(Duration.ZERO);
        TenantStatusCache zeroCache = new TenantStatusCache(
                adaptiveSupport(null, redisCacheTemplate, emptyEnvProvider()),
                zeroProps
        );
        zeroCache.getOrLoad(6007L, id -> null);
        verify(redisCacheTemplate).getOrLoad(
                eq(TenantStatusCache.buildCacheKey(6007L)),
                eq(TenantAuthDTO.class),
                any(Function.class),
                eq(Duration.ofSeconds(60)),
                isNull()
        );
    }

    @Test
    @DisplayName("ateng.boot.redis.enabled=false 时应当忽略 RedisCacheTemplate 并走本地有界缓存")
    void shouldBypassRedisCacheTemplateWhenRedisDisabled() {
        RedisCacheTemplate redisCacheTemplate = mock(RedisCacheTemplate.class);
        TenantStatusCache cache = new TenantStatusCache(
                adaptiveSupport(null, redisCacheTemplate, redisDisabledEnvProvider()),
                defaultProps()
        );

        TenantAuthDTO dto = TenantAuthDTO.builder()
                .tenantId(7007L)
                .tenantCode("T7007")
                .status(CommonStatusEnum.NORMAL.getCode())
                .build();
        assertThat(cache.getOrLoad(7007L, id -> dto)).isEqualTo(dto);
        verifyNoInteractions(redisCacheTemplate);
    }
}

package io.github.atengk.solid.system.support;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.properties.SolidTenantProperties;
import io.github.atengk.solid.system.api.dto.TenantAuthDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link TenantStatusCache} 租户状态双模零漂移缓存 Layer 1 单元测试。
 *
 * @author Ateng
 * @since 2026-11-25
 */
class TenantStatusCacheTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StringRedisTemplate> redisProviderOf(StringRedisTemplate redisTemplate) {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<Environment> emptyEnvProvider() {
        return mock(ObjectProvider.class);
    }

    private static SolidTenantProperties defaultProps() {
        SolidTenantProperties props = new SolidTenantProperties();
        props.setEnabled(true);
        props.setStatusCacheTtl(Duration.ofSeconds(60));
        return props;
    }

    @Test
    @DisplayName("传入空租户 ID 时应当直接返回 null 且不触发回源")
    void shouldReturnNullWhenTenantIdIsNull() {
        TenantStatusCache cache = new TenantStatusCache(redisProviderOf(null), defaultProps(), emptyEnvProvider());
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
        TenantStatusCache cache = new TenantStatusCache(redisProviderOf(null), defaultProps(), emptyEnvProvider());
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
        TenantStatusCache cache = new TenantStatusCache(redisProviderOf(null), defaultProps(), emptyEnvProvider());
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
        TenantStatusCache cache = new TenantStatusCache(redisProviderOf(null), defaultProps(), emptyEnvProvider());

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
    @DisplayName("Redis 启用时应当以紧凑 JSON 读写并支持负向哨兵、异常 JSON 自愈与失效删除")
    @SuppressWarnings("unchecked")
    void shouldUseCompactJsonInRedisWithSentinelCorruptionRecoveryAndEvict() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        TenantStatusCache cache = new TenantStatusCache(redisProviderOf(redis), defaultProps(), emptyEnvProvider());

        LocalDateTime futureExpire = LocalDateTime.of(2099, 12, 31, 23, 59, 59);
        String key = TenantStatusCache.buildCacheKey(3001L);
        TenantAuthDTO expectedDto = TenantAuthDTO.builder()
                .tenantId(3001L)
                .tenantCode("T3001")
                .tenantName("企业A")
                .packageId(88L)
                .expireTime(futureExpire)
                .status(CommonStatusEnum.NORMAL.getCode())
                .build();

        // 1. Redis 首次未命中 -> 回源并以紧凑 JSON 写入 60s TTL
        when(valueOps.get(key)).thenReturn(null);
        TenantAuthDTO loaded = cache.getOrLoad(3001L, id -> expectedDto);
        assertThat(loaded).isEqualTo(expectedDto);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(key), jsonCaptor.capture(), eq(Duration.ofSeconds(60)));
        String compactJson = jsonCaptor.getValue();
        assertThat(compactJson).doesNotContain("\n").doesNotContain("@class");

        // 2. Redis 命中紧凑 JSON（含 expireTime）-> 不回源并保留零漂移字段
        when(valueOps.get(key)).thenReturn(compactJson);
        AtomicInteger hitCalls = new AtomicInteger();
        TenantAuthDTO hit = cache.getOrLoad(3001L, id -> {
            hitCalls.incrementAndGet();
            return null;
        });
        assertThat(hitCalls.get()).isZero();
        assertThat(hit).isEqualTo(expectedDto);

        // 3. Redis 命中负向哨兵 -> 直接返回 null 且不回源
        String missingKey = TenantStatusCache.buildCacheKey(4004L);
        when(valueOps.get(missingKey)).thenReturn(null);
        assertThat(cache.getOrLoad(4004L, id -> null)).isNull();
        verify(valueOps).set(eq(missingKey), eq(JsonUtil.toJsonString(new TenantAuthDTO())), any(Duration.class));
        when(valueOps.get(missingKey)).thenReturn("{}");
        assertThat(cache.getOrLoad(4004L, id -> {
            hitCalls.incrementAndGet();
            return expectedDto;
        })).isNull();
        assertThat(hitCalls.get()).isZero();

        // 4. Redis 存有损坏 JSON -> 清理脏键并回源刷新
        String badKey = TenantStatusCache.buildCacheKey(5005L);
        when(valueOps.get(badKey)).thenReturn("{bad-json");
        TenantAuthDTO recovered = cache.getOrLoad(5005L, id -> expectedDto);
        assertThat(recovered).isEqualTo(expectedDto);
        verify(redis).delete(badKey);

        // 5. evict 调用 Redis delete
        cache.evict(3001L);
        verify(redis, times(1)).delete(key);
    }
}

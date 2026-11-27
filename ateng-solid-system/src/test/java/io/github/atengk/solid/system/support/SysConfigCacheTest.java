package io.github.atengk.solid.system.support;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.redis.cache.spi.TwoLevelCache;
import io.github.atengk.boot.redis.cache.twolevel.TwoLevelCacheManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.atengk.solid.system.support.CacheTestFixtures.adaptiveSupport;
import static io.github.atengk.solid.system.support.CacheTestFixtures.defaultProps;
import static io.github.atengk.solid.system.support.CacheTestFixtures.emptyEnvProvider;
import static io.github.atengk.solid.system.support.CacheTestFixtures.offlineAdaptiveSupport;
import static io.github.atengk.solid.system.support.CacheTestFixtures.propsWithTtl;
import static io.github.atengk.solid.system.support.CacheTestFixtures.redisDisabledEnvProvider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SysConfigCache} 系统参数键值对两级缓存与离线降级 Layer 1 单元测试。
 *
 * @author Ateng
 * @since 2026-11-25
 */
class SysConfigCacheTest {

    @Test
    @DisplayName("参数键空白或租户空值应当安全返回 null 且不回源，无 Redis 时支持哨兵防穿透与驱逐")
    void shouldHandleInvalidArgsAndLocalFallbackWithSentinelAndEvict() {
        SysConfigCache cache = new SysConfigCache(offlineAdaptiveSupport(), defaultProps());
        AtomicInteger calls = new AtomicInteger();

        assertThat(cache.getOrLoad(null, "sys.user.initPassword", () -> {
            calls.incrementAndGet();
            return "val";
        })).isNull();
        assertThat(cache.getOrLoad(0L, "  ", () -> {
            calls.incrementAndGet();
            return "val";
        })).isNull();
        assertThat(calls.get()).isZero();

        // 命中本地缓存且缺失哨兵只回源一次
        assertThat(cache.getOrLoad(1001L, "missing.key", () -> {
            calls.incrementAndGet();
            return null;
        })).isNull();
        assertThat(cache.getOrLoad(1001L, "missing.key", () -> {
            calls.incrementAndGet();
            return "shouldNotRun";
        })).isNull();
        assertThat(calls.get()).isEqualTo(1);

        // 驱逐后重新加载新值
        cache.evict(1001L, "missing.key");
        assertThat(cache.getOrLoad(1001L, "missing.key", () -> {
            calls.incrementAndGet();
            return "override-val";
        })).isEqualTo("override-val");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("传入空 loader 时应当快速抛出 NullPointerException")
    void shouldThrowWhenLoaderIsNull() {
        SysConfigCache cache = new SysConfigCache(offlineAdaptiveSupport(), defaultProps());
        assertThatThrownBy(() -> cache.getOrLoad(0L, "sys.user.initPassword", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Redis 启用时应当以领域相对键委派 TwoLevelCacheManager（config 空间）并支持命中与负向哨兵")
    void shouldDelegateToTwoLevelCacheWithRelativeKeyAndSentinel() {
        TwoLevelCacheManager cacheManager = mock(TwoLevelCacheManager.class);
        TwoLevelCache twoLevelCache = mock(TwoLevelCache.class);
        when(cacheManager.getTwoLevelCache(SysConfigCache.CACHE_SPACE)).thenReturn(twoLevelCache);

        SysConfigCache cache = new SysConfigCache(
                adaptiveSupport(cacheManager, null, emptyEnvProvider()),
                defaultProps()
        );

        String relativeKey = SysConfigCache.buildCacheKey(9201L, "sys.user.initPassword");
        assertThat(relativeKey).isEqualTo("9201:sys.user.initPassword");
        assertThat(relativeKey).doesNotContain("ateng:solid:");

        when(twoLevelCache.get(relativeKey, SysConfigCache.ConfigCacheEntry.class)).thenReturn(null);
        String loaded = cache.getOrLoad(9201L, " sys.user.initPassword ", () -> "Ateng@2026");
        assertThat(loaded).isEqualTo("Ateng@2026");

        ArgumentCaptor<SysConfigCache.ConfigCacheEntry> entryCaptor =
                ArgumentCaptor.forClass(SysConfigCache.ConfigCacheEntry.class);
        verify(twoLevelCache).put(eq(relativeKey), entryCaptor.capture(), eq(Duration.ofMinutes(30)));
        assertThat(entryCaptor.getValue().isExists()).isTrue();
        assertThat(entryCaptor.getValue().getValue()).isEqualTo("Ateng@2026");

        // 命中缓存不再回源
        when(twoLevelCache.get(relativeKey, SysConfigCache.ConfigCacheEntry.class)).thenReturn(entryCaptor.getValue());
        AtomicInteger hitCalls = new AtomicInteger();
        assertThat(cache.getOrLoad(9201L, "sys.user.initPassword", () -> {
            hitCalls.incrementAndGet();
            return "other";
        })).isEqualTo("Ateng@2026");
        assertThat(hitCalls.get()).isZero();

        // 不存在参数命中负向哨兵不回源
        String missingKey = SysConfigCache.buildCacheKey(9201L, "missing.param");
        when(twoLevelCache.get(missingKey, SysConfigCache.ConfigCacheEntry.class))
                .thenReturn(new SysConfigCache.ConfigCacheEntry(false, null));
        assertThat(cache.getOrLoad(9201L, "missing.param", () -> {
            hitCalls.incrementAndGet();
            return "fallback";
        })).isNull();
        assertThat(hitCalls.get()).isZero();
    }

    @Test
    @DisplayName("损坏数据自愈重放与 evict 应当正确协同且遵循自定义/兜底 TTL")
    void shouldRecoverCorruptedEntryEvictAndResolveTtl() {
        TwoLevelCacheManager cacheManager = mock(TwoLevelCacheManager.class);
        TwoLevelCache twoLevelCache = mock(TwoLevelCache.class);
        when(cacheManager.getTwoLevelCache(SysConfigCache.CACHE_SPACE)).thenReturn(twoLevelCache);

        SysConfigCache cache = new SysConfigCache(
                adaptiveSupport(cacheManager, null, emptyEnvProvider()),
                propsWithTtl(Duration.ofMinutes(30), Duration.ofMinutes(15))
        );

        String badKey = SysConfigCache.buildCacheKey(0L, "broken.key");
        when(twoLevelCache.get(badKey, SysConfigCache.ConfigCacheEntry.class))
                .thenThrow(new IllegalArgumentException("bad payload"));
        assertThat(cache.getOrLoad(0L, "broken.key", () -> "repaired")).isEqualTo("repaired");
        verify(twoLevelCache).evict(badKey);
        verify(twoLevelCache).put(eq(badKey), any(SysConfigCache.ConfigCacheEntry.class), eq(Duration.ofMinutes(15)));

        SysConfigCache zeroTtlCache = new SysConfigCache(
                adaptiveSupport(cacheManager, null, emptyEnvProvider()),
                propsWithTtl(Duration.ofMinutes(30), Duration.ZERO)
        );
        String defaultKey = SysConfigCache.buildCacheKey(0L, "zero.ttl.key");
        zeroTtlCache.getOrLoad(0L, "zero.ttl.key", () -> "val");
        verify(twoLevelCache).put(eq(defaultKey), any(SysConfigCache.ConfigCacheEntry.class), eq(Duration.ofMinutes(30)));

        cache.evict(0L, "broken.key");
        verify(twoLevelCache, times(2)).evict(badKey);
    }

    @Test
    @DisplayName("连接超时应当跳过冗余远程驱逐并降级本地；回写失败降级写入本地且透传 BusinessException")
    void shouldFallbackToLocalOnTimeoutAndPutFailureWithoutSwallowingBusinessException() {
        TwoLevelCacheManager cacheManager = mock(TwoLevelCacheManager.class);
        TwoLevelCache twoLevelCache = mock(TwoLevelCache.class);
        when(cacheManager.getTwoLevelCache(SysConfigCache.CACHE_SPACE)).thenReturn(twoLevelCache);

        SysConfigCache cache = new SysConfigCache(
                adaptiveSupport(cacheManager, null, emptyEnvProvider()),
                defaultProps()
        );

        String timeoutKey = SysConfigCache.buildCacheKey(1001L, "timeout.key");
        when(twoLevelCache.get(timeoutKey, SysConfigCache.ConfigCacheEntry.class))
                .thenThrow(new QueryTimeoutException("redis timeout"));
        AtomicInteger timeoutCalls = new AtomicInteger();
        assertThat(cache.getOrLoad(1001L, "timeout.key", () -> {
            timeoutCalls.incrementAndGet();
            return "local-val";
        })).isEqualTo("local-val");
        assertThat(cache.getOrLoad(1001L, "timeout.key", () -> {
            timeoutCalls.incrementAndGet();
            return "ignored";
        })).isEqualTo("local-val");
        assertThat(timeoutCalls.get()).isEqualTo(1);
        verify(twoLevelCache, never()).evict(timeoutKey);

        // put 失败时回写本地有界缓存
        String writeErrKey = SysConfigCache.buildCacheKey(1001L, "write.err");
        when(twoLevelCache.get(writeErrKey, SysConfigCache.ConfigCacheEntry.class)).thenReturn(null);
        doThrow(new RuntimeException("put failed"))
                .when(twoLevelCache).put(eq(writeErrKey), any(SysConfigCache.ConfigCacheEntry.class), eq(Duration.ofMinutes(30)));
        assertThat(cache.getOrLoad(1001L, "write.err", () -> "saved-local")).isEqualTo("saved-local");
        assertThat(cache.localSize()).isEqualTo(2);

        // 回源业务异常不得被吞没
        String bizKey = SysConfigCache.buildCacheKey(1001L, "biz.err");
        when(twoLevelCache.get(bizKey, SysConfigCache.ConfigCacheEntry.class)).thenReturn(null);
        AtomicInteger errCalls = new AtomicInteger();
        assertThatThrownBy(() -> cache.getOrLoad(1001L, "biz.err", () -> {
            errCalls.incrementAndGet();
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "db error");
        })).isInstanceOf(BusinessException.class);
        assertThat(errCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("本地有界缓存超过 10000 条上限时应当自动淘汰旧键")
    void shouldEnforceTenThousandLocalCap() {
        SysConfigCache cache = new SysConfigCache(offlineAdaptiveSupport(), defaultProps());
        for (int i = 0; i < 10_040; i++) {
            cache.getOrLoad(0L, "cfg_" + i, () -> "v");
        }
        assertThat(cache.localSize()).isLessThanOrEqualTo(AdaptiveRedisSupport.DEFAULT_LOCAL_MAX_CAPACITY);
    }

    @Test
    @DisplayName("ateng.boot.redis.enabled=false 时应当忽略 TwoLevelCacheManager 并走本地有界缓存")
    void shouldBypassTwoLevelCacheWhenRedisDisabled() {
        TwoLevelCacheManager cacheManager = mock(TwoLevelCacheManager.class);
        SysConfigCache cache = new SysConfigCache(
                adaptiveSupport(cacheManager, null, redisDisabledEnvProvider()),
                defaultProps()
        );

        assertThat(cache.getOrLoad(0L, "sys.user.initPassword", () -> "Ateng@2026")).isEqualTo("Ateng@2026");
        verifyNoInteractions(cacheManager);
    }
}

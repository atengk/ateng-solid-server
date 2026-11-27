package io.github.atengk.solid.system.support;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.concurrent.ConcurrentLruCache;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.redis.cache.spi.TwoLevelCache;
import io.github.atengk.boot.redis.cache.template.RedisCacheTemplate;
import io.github.atengk.boot.redis.cache.twolevel.TwoLevelCacheManager;
import io.github.atengk.boot.redis.lock.template.DistributedLockTemplate;
import io.github.atengk.boot.redis.lock.template.LockAction;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.properties.SolidTenantProperties;
import io.github.atengk.solid.system.api.dto.TenantAuthDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.atengk.solid.system.support.CacheTestFixtures.adaptiveSupport;
import static io.github.atengk.solid.system.support.CacheTestFixtures.emptyEnvProvider;
import static io.github.atengk.solid.system.support.CacheTestFixtures.offlineAdaptiveSupport;
import static io.github.atengk.solid.system.support.CacheTestFixtures.redisDisabledEnvProvider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AdaptiveRedisSupport} 并发分段互斥锁、恢复期本地陈旧缓存清理与本地幂等降级行为单元测试。
 *
 * @author Ateng
 * @since 2026-11-27
 */
class AdaptiveRedisSupportTest {

    @Test
    @DisplayName("离线或锁模板故障降级时同键应当走分段互斥锁串行执行，等待超时应当抛出 429 业务异常")
    void shouldSerializeSameLockKeyInLocalFallbackAndThrowTooManyRequestsOnTimeout() throws Exception {
        DistributedLockTemplate failingLockTemplate = mock(DistributedLockTemplate.class);
        when(failingLockTemplate.tryExecute(any(String.class), any(Duration.class), any(LockAction.class)))
                .thenThrow(new QueryTimeoutException("redis lock timeout"));
        AdaptiveRedisSupport support = adaptiveSupport(null, null, failingLockTemplate, null, emptyEnvProvider());

        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> holder = pool.submit(() -> support.executeWithMutexLock("tree:dept:1001", Duration.ofSeconds(3), () -> {
                int current = concurrentCount.incrementAndGet();
                maxConcurrent.accumulateAndGet(current, Math::max);
                firstEntered.countDown();
                try {
                    assertThat(releaseFirst.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    concurrentCount.decrementAndGet();
                }
            }));

            assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> support.executeWithMutexLock(
                    "tree:dept:1001",
                    Duration.ofMillis(80),
                    () -> concurrentCount.incrementAndGet()
            ))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(CommonResultCode.TOO_MANY_REQUESTS.getCode()));

            Future<?> queued = pool.submit(() -> support.executeWithMutexLock("tree:dept:1001", Duration.ofSeconds(3), () -> {
                int current = concurrentCount.incrementAndGet();
                maxConcurrent.accumulateAndGet(current, Math::max);
                concurrentCount.decrementAndGet();
            }));

            releaseFirst.countDown();
            holder.get(3, TimeUnit.SECONDS);
            queued.get(3, TimeUnit.SECONDS);
            assertThat(maxConcurrent.get()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Redis 故障降级后恢复访问应当同步驱逐当前键的本地陈旧缓存，防止二次断连读到过期值")
    void shouldEvictStaleLocalFallbackWhenTwoLevelOrL2RecoversOnRedis() {
        TwoLevelCacheManager manager = mock(TwoLevelCacheManager.class);
        TwoLevelCache twoLevelCache = mock(TwoLevelCache.class);
        when(manager.getTwoLevelCache("sys:config")).thenReturn(twoLevelCache);

        RedisCacheTemplate redisCacheTemplate = mock(RedisCacheTemplate.class);
        AdaptiveRedisSupport support = adaptiveSupport(manager, redisCacheTemplate, emptyEnvProvider());
        ConcurrentLruCache<String, String> localTwoLevel = new ConcurrentLruCache<>(100, Duration.ofMinutes(5));

        when(twoLevelCache.get("1001:k1", String.class))
                .thenThrow(new QueryTimeoutException("timeout 1"))
                .thenReturn("v2-fresh")
                .thenThrow(new QueryTimeoutException("timeout 2"));

        assertThat(support.getOrLoadTwoLevel(
                "sys:config", "1001:k1", String.class, () -> "v1-stale", Duration.ofMinutes(5), localTwoLevel
        )).isEqualTo("v1-stale");
        assertThat(localTwoLevel.get("1001:k1")).isEqualTo("v1-stale");

        assertThat(support.getOrLoadTwoLevel(
                "sys:config", "1001:k1", String.class, () -> "unused", Duration.ofMinutes(5), localTwoLevel
        )).isEqualTo("v2-fresh");
        assertThat(localTwoLevel.get("1001:k1")).isNull();

        assertThat(support.getOrLoadTwoLevel(
                "sys:config", "1001:k1", String.class, () -> "v3-reloaded", Duration.ofMinutes(5), localTwoLevel
        )).isEqualTo("v3-reloaded");

        // 验证 TenantStatusCache L2 恢复后同步清理本地过期条目
        SolidTenantProperties tenantProps = new SolidTenantProperties();
        tenantProps.setEnabled(true);
        tenantProps.setStatusCacheTtl(Duration.ofSeconds(60));
        TenantStatusCache tenantCache = new TenantStatusCache(support, tenantProps);

        TenantAuthDTO staleTenant = TenantAuthDTO.builder()
                .tenantId(2001L).tenantCode("T2001").tenantName("旧名称").status(CommonStatusEnum.NORMAL.getCode()).build();
        TenantAuthDTO freshTenant = TenantAuthDTO.builder()
                .tenantId(2001L).tenantCode("T2001").tenantName("新名称").status(CommonStatusEnum.NORMAL.getCode()).build();

        when(redisCacheTemplate.getOrLoad(eq("tenant:status:2001"), eq(TenantAuthDTO.class), any(), any(), any()))
                .thenThrow(new QueryTimeoutException("redis down"))
                .thenReturn(freshTenant);

        assertThat(tenantCache.getOrLoad(2001L, id -> staleTenant).getTenantName()).isEqualTo("旧名称");
        assertThat(tenantCache.localSize()).isEqualTo(1);

        assertThat(tenantCache.getOrLoad(2001L, id -> staleTenant).getTenantName()).isEqualTo("新名称");
        assertThat(tenantCache.localSize()).isZero();
    }

    @Test
    @DisplayName("本地幂等降级模式应当严格遵循 deleteOnFailure 参数决定异常后是否允许重试")
    void shouldRespectDeleteOnFailureFlagInLocalIdempotentFallback() {
        AdaptiveRedisSupport support = adaptiveSupport(null, null, null, null, redisDisabledEnvProvider());
        ConcurrentLruCache<String, Boolean> localDedup = new ConcurrentLruCache<>(100, Duration.ofMinutes(5));
        AtomicInteger keepCalls = new AtomicInteger(0);
        AtomicInteger deleteCalls = new AtomicInteger(0);

        assertThatThrownBy(() -> support.executeIdempotent(
                "idemp:keep", Duration.ofMinutes(5), false, () -> {
                    keepCalls.incrementAndGet();
                    throw new IllegalStateException("fatal");
                }, localDedup
        )).isInstanceOf(IllegalStateException.class);
        boolean secondKeep = support.executeIdempotent(
                "idemp:keep", Duration.ofMinutes(5), false, keepCalls::incrementAndGet, localDedup
        );
        assertThat(secondKeep).isFalse();
        assertThat(keepCalls.get()).isEqualTo(1);

        assertThatThrownBy(() -> support.executeIdempotent(
                "idemp:retry", Duration.ofMinutes(5), true, () -> {
                    deleteCalls.incrementAndGet();
                    throw new IllegalStateException("transient");
                }, localDedup
        )).isInstanceOf(IllegalStateException.class);
        boolean secondRetry = support.executeIdempotent(
                "idemp:retry", Duration.ofMinutes(5), true, deleteCalls::incrementAndGet, localDedup
        );
        assertThat(secondRetry).isTrue();
        assertThat(deleteCalls.get()).isEqualTo(2);
    }
}

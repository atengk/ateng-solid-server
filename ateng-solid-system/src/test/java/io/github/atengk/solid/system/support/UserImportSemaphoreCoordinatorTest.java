package io.github.atengk.solid.system.support;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.redis.lock.template.DistributedLockTemplate;
import io.github.atengk.boot.redis.lock.template.LockAction;
import io.github.atengk.boot.redis.properties.RedisProperties;
import io.github.atengk.boot.redis.semaphore.RedisSemaphoreTemplate;
import io.github.atengk.boot.redis.semaphore.SemaphoreSupplier;
import io.github.atengk.boot.redis.util.RedisUtil;
import io.github.atengk.solid.system.properties.SolidSystemProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.redisson.api.RSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.atengk.solid.system.support.CacheTestFixtures.adaptiveSupport;
import static io.github.atengk.solid.system.support.CacheTestFixtures.emptyEnvProvider;
import static io.github.atengk.solid.system.support.CacheTestFixtures.offlineAdaptiveSupport;
import static io.github.atengk.solid.system.support.CacheTestFixtures.providerOf;
import static io.github.atengk.solid.system.support.CacheTestFixtures.redisDisabledEnvProvider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link UserImportSemaphoreCoordinator} 分布式批量导入信号量校准与双模限流协调器 Layer 1 单元测试。
 *
 * @author Ateng
 * @since 2026-11-27
 */
class UserImportSemaphoreCoordinatorTest {

    @AfterEach
    void tearDown() {
        RedisUtil.init(null);
        // 清理可能由中断测试用例设置的中断标记
        Thread.interrupted();
    }

    @SuppressWarnings("unchecked")
    private static RedisUtil initMockRedisUtil(Map<String, Object> storage) {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, Object> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenAnswer(inv -> storage.get(inv.<String>getArgument(0)));
        doAnswer(inv -> {
            storage.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), any());

        RedisProperties properties = new RedisProperties();
        properties.setKeyPrefix("solid:");
        return new RedisUtil(redisTemplate, properties);
    }

    private static DistributedLockTemplate passthroughLockTemplate() {
        DistributedLockTemplate lockTemplate = mock(DistributedLockTemplate.class);
        when(lockTemplate.tryExecute(anyString(), any(Duration.class), ArgumentMatchers.<LockAction<RuntimeException>>any()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    LockAction<RuntimeException> action = inv.getArgument(2);
                    action.run();
                    return true;
                });
        return lockTemplate;
    }

    private static SolidSystemProperties propsWithConcurrency(int maxConcurrent) {
        SolidSystemProperties props = new SolidSystemProperties();
        props.getUser().setMaxConcurrentImports(maxConcurrent);
        return props;
    }

    @Test
    @DisplayName("Redis 关闭或配置 <= 0 时应跳过集群校准并退化为钳位后的本地信号量限流与中断保护")
    void shouldSkipCalibrationUseClampedLocalSemaphoreAndPreserveInterruptWhenRedisDisabled() throws Exception {
        RedisSemaphoreTemplate semaphoreTemplate = mock(RedisSemaphoreTemplate.class);
        Map<String, Object> storage = new HashMap<>();
        RedisUtil redisUtil = initMockRedisUtil(storage);
        AdaptiveRedisSupport adaptiveSupport = adaptiveSupport(
                null,
                null,
                passthroughLockTemplate(),
                null,
                redisDisabledEnvProvider()
        );
        // 传入 0 验证最小并发钳位为 1
        UserImportSemaphoreCoordinator coordinator = new UserImportSemaphoreCoordinator(
                adaptiveSupport,
                providerOf(semaphoreTemplate),
                providerOf(redisUtil),
                propsWithConcurrency(0)
        );

        coordinator.afterSingletonsInstantiated();
        verifyNoInteractions(semaphoreTemplate);
        assertThat(storage).isEmpty();

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> coordinator.executeWithPermit(() -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return "first";
        }));
        holder.start();

        Thread interruptedWaiter = null;
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            // 验证本地信号量排队等待被中断时返回 429 并保留中断标记
            AtomicReference<Throwable> waiterError = new AtomicReference<>();
            AtomicBoolean waiterInterrupted = new AtomicBoolean(false);
            CountDownLatch waiterStarted = new CountDownLatch(1);
            interruptedWaiter = new Thread(() -> {
                waiterStarted.countDown();
                try {
                    coordinator.executeWithPermit(() -> "interrupted-waiter");
                } catch (Throwable ex) {
                    waiterError.set(ex);
                    waiterInterrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            interruptedWaiter.start();
            assertThat(waiterStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(50);
            interruptedWaiter.interrupt();
            interruptedWaiter.join(5000);
            assertThat(waiterError.get()).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) waiterError.get()).getCode())
                    .isEqualTo(CommonResultCode.TOO_MANY_REQUESTS.getCode());
            assertThat(waiterInterrupted).isTrue();

            // 正常等待 3s 超时返回 429
            assertThatThrownBy(() -> coordinator.executeWithPermit(() -> "second"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(CommonResultCode.TOO_MANY_REQUESTS.getCode()));
        } finally {
            release.countDown();
            holder.join(5000);
            if (interruptedWaiter != null) {
                interruptedWaiter.join(1000);
            }
        }
        verifyNoInteractions(semaphoreTemplate);
    }

    @Test
    @DisplayName("首次启动无历史伴生元数据时应通过 trySetPermits 初始化并持久化许可基线")
    void shouldInitializePermitsOnFirstBootWhenCompanionMetaMissing() {
        Map<String, Object> storage = new HashMap<>();
        RedisUtil redisUtil = initMockRedisUtil(storage);
        RedisSemaphoreTemplate semaphoreTemplate = mock(RedisSemaphoreTemplate.class);
        DistributedLockTemplate lockTemplate = passthroughLockTemplate();
        when(semaphoreTemplate.trySetPermits(UserImportSemaphoreCoordinator.SEMAPHORE_NAME, 4))
                .thenReturn(true);

        UserImportSemaphoreCoordinator coordinator = new UserImportSemaphoreCoordinator(
                adaptiveSupport(null, null, lockTemplate, null, emptyEnvProvider()),
                providerOf(semaphoreTemplate),
                providerOf(redisUtil),
                propsWithConcurrency(4)
        );

        coordinator.afterSingletonsInstantiated();

        verify(lockTemplate).tryExecute(
                eq(UserImportSemaphoreCoordinator.CALIBRATION_LOCK_KEY),
                any(Duration.class),
                ArgumentMatchers.<LockAction<RuntimeException>>any()
        );
        verify(semaphoreTemplate).trySetPermits(UserImportSemaphoreCoordinator.SEMAPHORE_NAME, 4);
        assertThat(storage.get("solid:" + UserImportSemaphoreCoordinator.PERMITS_META_KEY)).isEqualTo(4);
    }

    @Test
    @DisplayName("启动校准应覆盖扩容、删键自愈后扩容、缩容成功/竞争失败与等值跳过全部分支")
    void shouldAdjustClusterPermitsAcrossScaleUpSelfHealScaleDownAndEqualBranches() {
        // 1. 正常扩容场景：2 -> 5（trySetPermits 返回 false，再 addPermits(delta = 3)）
        Map<String, Object> scaleUpStorage = new HashMap<>();
        scaleUpStorage.put("solid:" + UserImportSemaphoreCoordinator.PERMITS_META_KEY, 2);
        RedisUtil scaleUpRedisUtil = initMockRedisUtil(scaleUpStorage);
        RedisSemaphoreTemplate scaleUpTemplate = mock(RedisSemaphoreTemplate.class);
        when(scaleUpTemplate.trySetPermits(UserImportSemaphoreCoordinator.SEMAPHORE_NAME, 5)).thenReturn(false);
        UserImportSemaphoreCoordinator scaleUpCoordinator = new UserImportSemaphoreCoordinator(
                adaptiveSupport(null, null, passthroughLockTemplate(), null, emptyEnvProvider()),
                providerOf(scaleUpTemplate),
                providerOf(scaleUpRedisUtil),
                propsWithConcurrency(5)
        );
        scaleUpCoordinator.afterSingletonsInstantiated();
        verify(scaleUpTemplate).trySetPermits(UserImportSemaphoreCoordinator.SEMAPHORE_NAME, 5);
        verify(scaleUpTemplate).addPermits(UserImportSemaphoreCoordinator.SEMAPHORE_NAME, 3);
        verify(scaleUpTemplate, never()).resetPermits(anyString(), anyInt());
        assertThat(scaleUpStorage.get("solid:" + UserImportSemaphoreCoordinator.PERMITS_META_KEY)).isEqualTo(5);

        // 2. 运维删键自愈后紧接扩容：2 -> 5，trySetPermits 返回 true 时禁止叠加 addPermits
        Map<String, Object> healStorage = new HashMap<>();
        healStorage.put("solid:" + UserImportSemaphoreCoordinator.PERMITS_META_KEY, 2);
        RedisUtil healRedisUtil = initMockRedisUtil(healStorage);
        RedisSemaphoreTemplate healTemplate = mock(RedisSemaphoreTemplate.class);
        when(healTemplate.trySetPermits(UserImportSemaphoreCoordinator.SEMAPHORE_NAME, 5)).thenReturn(true);
        UserImportSemaphoreCoordinator healCoordinator = new UserImportSemaphoreCoordinator(
                adaptiveSupport(null, null, passthroughLockTemplate(), null, emptyEnvProvider()),
                providerOf(healTemplate),
                providerOf(healRedisUtil),
                propsWithConcurrency(5)
        );
        healCoordinator.afterSingletonsInstantiated();
        verify(healTemplate).trySetPermits(UserImportSemaphoreCoordinator.SEMAPHORE_NAME, 5);
        verify(healTemplate, never()).addPermits(anyString(), anyInt());
        assertThat(healStorage.get("solid:" + UserImportSemaphoreCoordinator.PERMITS_META_KEY)).isEqualTo(5);

        // 3. 缩容成功：5 -> 2，resetPermits 返回 true 时回写伴生元数据为 2
        Map<String, Object> scaleDownStorage = new HashMap<>();
        scaleDownStorage.put("solid:" + UserImportSemaphoreCoordinator.PERMITS_META_KEY, 5);
        RedisUtil scaleDownRedisUtil = initMockRedisUtil(scaleDownStorage);
        RedisSemaphoreTemplate scaleDownTemplate = mock(RedisSemaphoreTemplate.class);
        when(scaleDownTemplate.resetPermits(UserImportSemaphoreCoordinator.SEMAPHORE_NAME, 2)).thenReturn(true);
        UserImportSemaphoreCoordinator scaleDownCoordinator = new UserImportSemaphoreCoordinator(
                adaptiveSupport(null, null, passthroughLockTemplate(), null, emptyEnvProvider()),
                providerOf(scaleDownTemplate),
                providerOf(scaleDownRedisUtil),
                propsWithConcurrency(2)
        );
        scaleDownCoordinator.afterSingletonsInstantiated();
        verify(scaleDownTemplate).resetPermits(UserImportSemaphoreCoordinator.SEMAPHORE_NAME, 2);
        verify(scaleDownTemplate, never()).addPermits(anyString(), anyInt());
        assertThat(scaleDownStorage.get("solid:" + UserImportSemaphoreCoordinator.PERMITS_META_KEY)).isEqualTo(2);

        // 4. 缩容竞争失败：resetPermits 返回 false 时必须保留原伴生元数据基线 5
        Map<String, Object> scaleDownFailStorage = new HashMap<>();
        scaleDownFailStorage.put("solid:" + UserImportSemaphoreCoordinator.PERMITS_META_KEY, 5);
        RedisUtil scaleDownFailRedisUtil = initMockRedisUtil(scaleDownFailStorage);
        RedisSemaphoreTemplate scaleDownFailTemplate = mock(RedisSemaphoreTemplate.class);
        when(scaleDownFailTemplate.resetPermits(UserImportSemaphoreCoordinator.SEMAPHORE_NAME, 2)).thenReturn(false);
        UserImportSemaphoreCoordinator scaleDownFailCoordinator = new UserImportSemaphoreCoordinator(
                adaptiveSupport(null, null, passthroughLockTemplate(), null, emptyEnvProvider()),
                providerOf(scaleDownFailTemplate),
                providerOf(scaleDownFailRedisUtil),
                propsWithConcurrency(2)
        );
        scaleDownFailCoordinator.afterSingletonsInstantiated();
        verify(scaleDownFailTemplate).resetPermits(UserImportSemaphoreCoordinator.SEMAPHORE_NAME, 2);
        assertThat(scaleDownFailStorage.get("solid:" + UserImportSemaphoreCoordinator.PERMITS_META_KEY)).isEqualTo(5);

        // 5. 等值场景：3 == 3，不触碰 RedisSemaphoreTemplate
        Map<String, Object> equalStorage = new HashMap<>();
        equalStorage.put("solid:" + UserImportSemaphoreCoordinator.PERMITS_META_KEY, 3);
        RedisUtil equalRedisUtil = initMockRedisUtil(equalStorage);
        RedisSemaphoreTemplate equalTemplate = mock(RedisSemaphoreTemplate.class);
        UserImportSemaphoreCoordinator equalCoordinator = new UserImportSemaphoreCoordinator(
                adaptiveSupport(null, null, passthroughLockTemplate(), null, emptyEnvProvider()),
                providerOf(equalTemplate),
                providerOf(equalRedisUtil),
                propsWithConcurrency(3)
        );
        equalCoordinator.afterSingletonsInstantiated();
        verifyNoInteractions(equalTemplate);
        assertThat(equalStorage.get("solid:" + UserImportSemaphoreCoordinator.PERMITS_META_KEY)).isEqualTo(3);
    }

    @Test
    @DisplayName("严格互斥锁未抢到、未装配或加锁异常时必须中止校准，严禁无锁改写信号量或阻断启动")
    void shouldStrictlyAbortCalibrationWhenLockMissingContendedOrFails() {
        Map<String, Object> storage = new HashMap<>();
        RedisUtil redisUtil = initMockRedisUtil(storage);
        RedisSemaphoreTemplate semaphoreTemplate = mock(RedisSemaphoreTemplate.class);

        // 1. 锁争用返回 false
        DistributedLockTemplate contendedLock = mock(DistributedLockTemplate.class);
        when(contendedLock.tryExecute(anyString(), any(Duration.class), ArgumentMatchers.<LockAction<RuntimeException>>any()))
                .thenReturn(false);
        new UserImportSemaphoreCoordinator(
                adaptiveSupport(null, null, contendedLock, null, emptyEnvProvider()),
                providerOf(semaphoreTemplate),
                providerOf(redisUtil),
                propsWithConcurrency(2)
        ).afterSingletonsInstantiated();

        // 2. DistributedLockTemplate 未装配（null）时严禁无锁直通校准
        new UserImportSemaphoreCoordinator(
                adaptiveSupport(null, null, null, null, emptyEnvProvider()),
                providerOf(semaphoreTemplate),
                providerOf(redisUtil),
                propsWithConcurrency(2)
        ).afterSingletonsInstantiated();

        // 3. 加锁抛出 Redis 超时异常时不阻断启动且不修改信号量
        DistributedLockTemplate failingLock = mock(DistributedLockTemplate.class);
        when(failingLock.tryExecute(anyString(), any(Duration.class), ArgumentMatchers.<LockAction<RuntimeException>>any()))
                .thenThrow(new QueryTimeoutException("lock timeout"));
        new UserImportSemaphoreCoordinator(
                adaptiveSupport(null, null, failingLock, null, emptyEnvProvider()),
                providerOf(semaphoreTemplate),
                providerOf(redisUtil),
                propsWithConcurrency(2)
        ).afterSingletonsInstantiated();

        verifyNoInteractions(semaphoreTemplate);
        assertThat(storage).isEmpty();
    }

    @Test
    @DisplayName("协同真实 RedisSemaphoreTemplate 应映射 solid:semaphore:user:import，正常执行释放许可且超时 429 不降级")
    void shouldIntegrateWithRealRedisSemaphoreTemplateAndMapPhysicalKey() throws InterruptedException {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RSemaphore semaphore = mock(RSemaphore.class);
        when(redissonClient.getSemaphore("solid:semaphore:user:import")).thenReturn(semaphore);
        when(semaphore.trySetPermits(3)).thenReturn(true);
        when(semaphore.tryAcquire(1, Duration.ofSeconds(3))).thenReturn(true, false);

        RedisProperties redisProperties = new RedisProperties();
        redisProperties.setKeyPrefix("solid:");
        RedisSemaphoreTemplate realTemplate = new RedisSemaphoreTemplate(redissonClient, redisProperties);

        UserImportSemaphoreCoordinator coordinator = new UserImportSemaphoreCoordinator(
                adaptiveSupport(null, null, null, null, emptyEnvProvider()),
                providerOf(realTemplate),
                providerOf(null),
                propsWithConcurrency(3)
        );

        String success = coordinator.executeWithPermit(() -> "cluster-ok");
        assertThat(success).isEqualTo("cluster-ok");
        verify(redissonClient, atLeastOnce()).getSemaphore("solid:semaphore:user:import");
        verify(semaphore).release(1);

        // 第二次真实 tryAcquire 返回 false -> 真实模板抛 429，协调器不得降级本地执行
        AtomicBoolean fallbackInvoked = new AtomicBoolean(false);
        assertThatThrownBy(() -> coordinator.executeWithPermit(() -> {
            fallbackInvoked.set(true);
            return "should-not-run";
        }))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(CommonResultCode.TOO_MANY_REQUESTS.getCode()));
        assertThat(fallbackInvoked).isFalse();
    }

    @Test
    @DisplayName("集群信号量返回 429 或线程被中断时严禁降级到本地信号量执行")
    void shouldNotFallbackWhenClusterSemaphoreReturns429OrInterrupted() {
        Map<String, Object> storage = new HashMap<>();
        RedisUtil redisUtil = initMockRedisUtil(storage);
        RedisSemaphoreTemplate semaphoreTemplate = mock(RedisSemaphoreTemplate.class);
        when(semaphoreTemplate.execute(
                eq(UserImportSemaphoreCoordinator.SEMAPHORE_NAME),
                eq(2),
                eq(1),
                eq(Duration.ofSeconds(3)),
                ArgumentMatchers.<SemaphoreSupplier<String, RuntimeException>>any()
        )).thenThrow(new BusinessException(CommonResultCode.TOO_MANY_REQUESTS.getCode(), "busy"));

        UserImportSemaphoreCoordinator coordinator = new UserImportSemaphoreCoordinator(
                adaptiveSupport(null, null, passthroughLockTemplate(), null, emptyEnvProvider()),
                providerOf(semaphoreTemplate),
                providerOf(redisUtil),
                propsWithConcurrency(2)
        );

        AtomicBoolean invoked = new AtomicBoolean(false);
        assertThatThrownBy(() -> coordinator.executeWithPermit(() -> {
            invoked.set(true);
            return "should-not-run";
        }))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(CommonResultCode.TOO_MANY_REQUESTS.getCode()));
        assertThat(invoked).isFalse();

        // 中断异常也不得降级到本地执行，并保留中断标记
        when(semaphoreTemplate.execute(
                eq(UserImportSemaphoreCoordinator.SEMAPHORE_NAME),
                eq(2),
                eq(1),
                eq(Duration.ofSeconds(3)),
                ArgumentMatchers.<SemaphoreSupplier<String, RuntimeException>>any()
        )).thenAnswer(inv -> {
            Thread.currentThread().interrupt();
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "interrupted");
        });

        assertThatThrownBy(() -> coordinator.executeWithPermit(() -> {
            invoked.set(true);
            return "should-not-run";
        }))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(CommonResultCode.TOO_MANY_REQUESTS.getCode()));
        assertThat(invoked).isFalse();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    @DisplayName("Redis 信号量进入业务逻辑前连接失败应降级本地信号量，业务异常透传不二次执行")
    void shouldFallbackOnPreEntryInfraErrorAndPropagateInActionFailure() {
        RedisSemaphoreTemplate semaphoreTemplate = mock(RedisSemaphoreTemplate.class);
        UserImportSemaphoreCoordinator coordinator = new UserImportSemaphoreCoordinator(
                adaptiveSupport(null, null, passthroughLockTemplate(), null, emptyEnvProvider()),
                providerOf(semaphoreTemplate),
                providerOf(null),
                propsWithConcurrency(2)
        );

        when(semaphoreTemplate.execute(
                eq(UserImportSemaphoreCoordinator.SEMAPHORE_NAME),
                eq(2),
                eq(1),
                eq(Duration.ofSeconds(3)),
                ArgumentMatchers.<SemaphoreSupplier<String, RuntimeException>>any()
        )).thenThrow(new QueryTimeoutException("redis timeout"));

        AtomicInteger runs = new AtomicInteger();
        String fallbackResult = coordinator.executeWithPermit(() -> {
            runs.incrementAndGet();
            return "local-ok";
        });
        assertThat(fallbackResult).isEqualTo("local-ok");
        assertThat(runs.get()).isEqualTo(1);

        // 进入 lambda 后抛出的业务异常严禁再次降级重跑
        when(semaphoreTemplate.execute(
                eq(UserImportSemaphoreCoordinator.SEMAPHORE_NAME),
                eq(2),
                eq(1),
                eq(Duration.ofSeconds(3)),
                ArgumentMatchers.<SemaphoreSupplier<String, RuntimeException>>any()
        )).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            SemaphoreSupplier<String, RuntimeException> supplier = inv.getArgument(4);
            return supplier.get();
        });

        AtomicInteger bizRuns = new AtomicInteger();
        assertThatThrownBy(() -> coordinator.executeWithPermit(() -> {
            bizRuns.incrementAndGet();
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "hash failed");
        }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("hash failed");
        assertThat(bizRuns.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("离线无提供器默认装配时协调器应平滑运行")
    void shouldOperateCleanlyWithOfflineAdaptiveSupport() {
        UserImportSemaphoreCoordinator coordinator = new UserImportSemaphoreCoordinator(
                offlineAdaptiveSupport(),
                providerOf(null),
                providerOf(null),
                propsWithConcurrency(2)
        );
        coordinator.afterSingletonsInstantiated();
        assertThat(coordinator.executeWithPermit(() -> "ok")).isEqualTo("ok");
    }
}

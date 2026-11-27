package io.github.atengk.solid.system.support;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.redis.semaphore.RedisSemaphoreTemplate;
import io.github.atengk.boot.redis.util.RedisUtil;
import io.github.atengk.solid.system.properties.SolidSystemProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 用户批量导入并发信号量双模协调器：Redis 启用且可用时委托 {@link RedisSemaphoreTemplate} 执行跨实例限流，
 * 并在容器单例就绪后通过 {@code DistributedLockTemplate} 严格互斥对比伴生许可数元数据执行启动增量/缩容校准；
 * Redis 显式关闭或建连前故障时平滑降级至进程内 {@link Semaphore}。
 *
 * @author Ateng
 * @since 2026-11-27
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserImportSemaphoreCoordinator implements SmartInitializingSingleton {

    /**
     * 分布式信号量业务名称（由 {@link RedisSemaphoreTemplate} 结合 {@code key-prefix} 自动映射为 {@code solid:semaphore:user:import}）
     */
    public static final String SEMAPHORE_NAME = "user:import";

    /**
     * 用于启动比对许可数配置变化的伴生元数据相对键（通过 {@link RedisUtil#buildKey(String)} 映射前缀）
     */
    public static final String PERMITS_META_KEY = "semaphore:user:import:permits";

    /**
     * 集群启动校准分布式互斥锁相对键
     */
    public static final String CALIBRATION_LOCK_KEY = "user:import:semaphore:calibrate";

    private static final int DEFAULT_MAX_CONCURRENT_IMPORTS = 2;
    private static final int ACQUIRE_PERMITS = 1;
    private static final Duration SEMAPHORE_WAIT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration CALIBRATION_LOCK_WAIT = Duration.ofSeconds(3);

    private final AdaptiveRedisSupport adaptiveRedisSupport;
    private final ObjectProvider<RedisSemaphoreTemplate> redisSemaphoreTemplateProvider;
    private final ObjectProvider<RedisUtil> redisUtilProvider;
    private final SolidSystemProperties systemProperties;

    private final AtomicReference<Semaphore> localSemaphoreRef = new AtomicReference<>();

    @Override
    public void afterSingletonsInstantiated() {
        calibrateClusterPermits();
    }

    /**
     * 在分布式锁保护下比对目标并发许可数与 Redis 伴生元数据基线，按需执行首次初始化、扩容增量或缩容重置。
     */
    public void calibrateClusterPermits() {
        RedisSemaphoreTemplate semaphoreTemplate = adaptiveRedisSupport.resolveActive(redisSemaphoreTemplateProvider);
        RedisUtil redisUtil = adaptiveRedisSupport.resolveActive(redisUtilProvider);
        if (semaphoreTemplate == null || redisUtil == null) {
            return;
        }

        int desiredPermits = resolveMaxConcurrentImports();
        try {
            boolean executed = adaptiveRedisSupport.tryExecuteStrictlyWithLock(
                    CALIBRATION_LOCK_KEY,
                    CALIBRATION_LOCK_WAIT,
                    () -> doCalibrateClusterPermits(semaphoreTemplate, desiredPermits)
            );
            if (!executed) {
                log.info("未获取到批量导入信号量校准严格分布式锁（锁模板未启用或被其他节点持有），跳过本次启动校准: desiredPermits={}",
                        desiredPermits);
            }
        } catch (RuntimeException ex) {
            if (Thread.currentThread().isInterrupted()) {
                log.warn("批量导入信号量启动校准被中断: desiredPermits={}", desiredPermits, ex);
            } else {
                log.warn("批量导入信号量启动校准失败，将在运行期按降级路径兜底: desiredPermits={}", desiredPermits, ex);
            }
        }
    }

    /**
     * 在批量导入许可保护下执行受管任务；遇到集群 429 限流、等待中断或已进入业务闭包后抛出的异常均直接向上透传。
     *
     * @param action 受信号量并发保护的业务闭包
     * @param <T>    返回值类型
     * @return 业务执行结果
     * @throws BusinessException 排队超时（429）、排队中断（429）或业务闭包本身抛出的异常
     */
    public <T> T executeWithPermit(Supplier<T> action) {
        Objects.requireNonNull(action, "action 不能为空");
        RedisSemaphoreTemplate semaphoreTemplate = adaptiveRedisSupport.resolveActive(redisSemaphoreTemplateProvider);
        if (semaphoreTemplate == null) {
            return executeLocally(action);
        }

        int desiredPermits = resolveMaxConcurrentImports();
        AtomicBoolean actionInvoked = new AtomicBoolean(false);
        AtomicReference<RuntimeException> actionFailure = new AtomicReference<>();
        try {
            return semaphoreTemplate.execute(
                    SEMAPHORE_NAME,
                    desiredPermits,
                    ACQUIRE_PERMITS,
                    SEMAPHORE_WAIT_TIMEOUT,
                    () -> runTracked(action, actionInvoked, actionFailure)
            );
        } catch (RuntimeException ex) {
            if (actionFailure.get() != null) {
                throw actionFailure.get();
            }
            if (actionInvoked.get()) {
                throw ex;
            }
            if (isTooManyRequests(ex)) {
                throw new BusinessException(CommonResultCode.TOO_MANY_REQUESTS, "当前用户批量导入任务繁忙，请稍后重试", ex);
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new BusinessException(CommonResultCode.TOO_MANY_REQUESTS, "批量导入排队被中断，请稍后重试", ex);
            }
            log.warn("RedisSemaphoreTemplate 获取或执行导入许可异常，降级使用本地信号量限流: maxConcurrent={}",
                    desiredPermits, ex);
            return executeLocally(action);
        }
    }

    private void doCalibrateClusterPermits(RedisSemaphoreTemplate semaphoreTemplate, int desiredPermits) {
        String fullMetaKey = RedisUtil.buildKey(PERMITS_META_KEY);
        Integer previousPermits = RedisUtil.get(fullMetaKey, Integer.class);
        if (previousPermits == null) {
            boolean initialized = semaphoreTemplate.trySetPermits(SEMAPHORE_NAME, desiredPermits);
            RedisUtil.set(fullMetaKey, desiredPermits);
            log.info("首次初始化用户批量导入集群信号量许可数: permits={}, newlyCreated={}", desiredPermits, initialized);
            return;
        }
        if (desiredPermits > previousPermits) {
            // 防止运维手动删除信号量键自愈后紧接扩容时 addPermits 仅写入差值导致配额缺口
            if (semaphoreTemplate.trySetPermits(SEMAPHORE_NAME, desiredPermits)) {
                RedisUtil.set(fullMetaKey, desiredPermits);
                log.info("批量导入集群信号量键缺失，按扩容配置重新初始化许可数: {} -> {}", previousPermits, desiredPermits);
                return;
            }
            int delta = desiredPermits - previousPermits;
            semaphoreTemplate.addPermits(SEMAPHORE_NAME, delta);
            RedisUtil.set(fullMetaKey, desiredPermits);
            log.info("扩容用户批量导入集群信号量许可数: {} -> {} (+{})", previousPermits, desiredPermits, delta);
            return;
        }
        if (desiredPermits < previousPermits) {
            boolean resetSuccess = semaphoreTemplate.resetPermits(SEMAPHORE_NAME, desiredPermits);
            if (resetSuccess) {
                RedisUtil.set(fullMetaKey, desiredPermits);
                log.info("缩容重置用户批量导入集群信号量许可数: {} -> {}", previousPermits, desiredPermits);
            } else {
                log.warn("缩容重置用户批量导入集群信号量许可数竞争失败，保留既有元数据基线以便后续校准: {} -> {}",
                        previousPermits, desiredPermits);
            }
        }
    }

    private <T> T executeLocally(Supplier<T> action) {
        Semaphore semaphore = resolveLocalSemaphore();
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(SEMAPHORE_WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(CommonResultCode.TOO_MANY_REQUESTS, "批量导入排队被中断，请稍后重试", ex);
        }
        if (!acquired) {
            throw new BusinessException(CommonResultCode.TOO_MANY_REQUESTS, "当前用户批量导入任务繁忙，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            semaphore.release();
        }
    }

    private static <T> T runTracked(
            Supplier<T> action,
            AtomicBoolean actionInvoked,
            AtomicReference<RuntimeException> actionFailure
    ) {
        actionInvoked.set(true);
        try {
            return action.get();
        } catch (RuntimeException ex) {
            actionFailure.set(ex);
            throw ex;
        }
    }

    private static boolean isTooManyRequests(RuntimeException ex) {
        return ex instanceof BusinessException bizEx
                && Objects.equals(bizEx.getCode(), CommonResultCode.TOO_MANY_REQUESTS.getCode());
    }

    private Semaphore resolveLocalSemaphore() {
        Semaphore existing = localSemaphoreRef.get();
        if (existing != null) {
            return existing;
        }
        Semaphore created = new Semaphore(resolveMaxConcurrentImports());
        return localSemaphoreRef.compareAndSet(null, created) ? created : localSemaphoreRef.get();
    }

    private int resolveMaxConcurrentImports() {
        int configured = systemProperties != null && systemProperties.getUser() != null
                ? systemProperties.getUser().getMaxConcurrentImports()
                : DEFAULT_MAX_CONCURRENT_IMPORTS;
        return Math.max(configured, 1);
    }
}

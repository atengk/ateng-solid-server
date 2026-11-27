package io.github.atengk.solid.system.support;

import io.github.atengk.boot.core.concurrent.ConcurrentLruCache;
import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.redis.cache.spi.TwoLevelCache;
import io.github.atengk.boot.redis.cache.template.RedisCacheTemplate;
import io.github.atengk.boot.redis.cache.twolevel.TwoLevelCacheManager;
import io.github.atengk.boot.redis.idempotent.RedisIdempotentTemplate;
import io.github.atengk.boot.redis.lock.template.DistributedLockTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 系统管理域可选 Redis 自适应协同门面，按 {@code ateng.boot.redis.enabled} 与容器装配状态自动切换
 * {@link TwoLevelCacheManager}、{@link RedisCacheTemplate}、{@link DistributedLockTemplate}、
 * {@link RedisIdempotentTemplate} 受管能力与进程内有界降级路径。
 *
 * @author Ateng
 * @since 2026-11-27
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdaptiveRedisSupport {

    /**
     * 无 Redis 或降级模式下默认本地有界缓存容量上限
     */
    public static final int DEFAULT_LOCAL_MAX_CAPACITY = 10_000;

    private static final Duration DEFAULT_LOCK_WAIT_TIME = Duration.ofSeconds(5);

    private final ObjectProvider<Environment> environmentProvider;
    private final ObjectProvider<TwoLevelCacheManager> twoLevelCacheManagerProvider;
    private final ObjectProvider<RedisCacheTemplate> redisCacheTemplateProvider;
    private final ObjectProvider<DistributedLockTemplate> distributedLockTemplateProvider;
    private final ObjectProvider<RedisIdempotentTemplate> redisIdempotentTemplateProvider;

    /**
     * 判断当前运行环境是否处于 Redis 启用状态（未显式关闭 {@code ateng.boot.redis.enabled}）。
     *
     * @return 若允许访问 Redis 返回 {@code true}；否则返回 {@code false}
     */
    public boolean isRedisActive() {
        return RedisActivationResolver.isRedisEnabled(environmentProvider);
    }

    /**
     * 解析正值有效的缓存有效期；配置为空、零或负数时回落至默认值。
     *
     * @param configured 外置配置有效期
     * @param defaultTtl 默认兜底有效期
     * @return 生效的缓存有效期
     */
    public static Duration resolveEffectiveTtl(Duration configured, Duration defaultTtl) {
        Objects.requireNonNull(defaultTtl, "defaultTtl 不能为空");
        return isPositiveDuration(configured) ? configured : defaultTtl;
    }

    /**
     * 优先通过 {@link TwoLevelCacheManager} 读取或加载两级缓存；Redis 未启用或故障时平滑降级至本地有界缓存。
     *
     * @param cacheSpace    两级缓存空间名
     * @param key           领域相对缓存键
     * @param valueType     期望反序列化载荷类型
     * @param loader        未命中时的回源加载器
     * @param ttl           期望写入的 L2 过期时间
     * @param localFallback 进程内本地有界降级缓存
     * @param <T>           载荷类型
     * @return 命中或回源加载的结果对象
     */
    public <T> T getOrLoadTwoLevel(
            String cacheSpace,
            String key,
            Class<T> valueType,
            Supplier<T> loader,
            Duration ttl,
            ConcurrentLruCache<String, T> localFallback
    ) {
        if (!StringUtils.hasText(cacheSpace) || !StringUtils.hasText(key)) {
            return null;
        }
        Objects.requireNonNull(valueType, "valueType 不能为空");
        Objects.requireNonNull(loader, "loader 不能为空");
        Objects.requireNonNull(localFallback, "localFallback 不能为空");

        TwoLevelCache twoLevelCache = resolveTwoLevelCache(cacheSpace);
        if (twoLevelCache == null) {
            return localFallback.computeIfAbsent(key, k -> loader.get());
        }

        try {
            T cached = twoLevelCache.get(key, valueType);
            if (cached != null) {
                return cached;
            }
        } catch (RuntimeException readEx) {
            if (isConnectivityFailure(readEx)) {
                log.warn("读取两级缓存连接或超时异常，跳过远程驱逐直接降级本地有界缓存: space={}, key={}", cacheSpace, key, readEx);
                return localFallback.computeIfAbsent(key, k -> loader.get());
            }
            log.warn("读取两级缓存异常或数据损坏，尝试清理后回源: space={}, key={}", cacheSpace, key, readEx);
            if (!evictTwoLevelSafely(twoLevelCache, cacheSpace, key)) {
                return localFallback.computeIfAbsent(key, k -> loader.get());
            }
        }

        // 业务回源与缓存回写解耦，防止数据库或业务异常被误判为 Redis 故障触发二次重复加载
        T loaded = loader.get();
        if (loaded != null) {
            writeTwoLevelSafely(twoLevelCache, cacheSpace, key, loaded, ttl, localFallback);
        }
        return loaded;
    }

    /**
     * 主动驱逐指定两级缓存空间与本地降级缓存中的键（Redis 开启时经 Pub/Sub 广播跨节点淘汰 L1）。
     *
     * @param cacheSpace    两级缓存空间名
     * @param key           领域相对缓存键
     * @param localFallback 进程内本地有界降级缓存
     */
    public void evictTwoLevel(
            String cacheSpace,
            String key,
            ConcurrentLruCache<String, ?> localFallback
    ) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        if (localFallback != null) {
            localFallback.remove(key);
        }
        TwoLevelCache twoLevelCache = resolveTwoLevelCache(cacheSpace);
        if (twoLevelCache != null) {
            evictTwoLevelSafely(twoLevelCache, cacheSpace, key);
        }
    }

    /**
     * 优先通过 {@link RedisCacheTemplate} 读取或加载纯 L2 分布式缓存（Redisson 互斥防击穿、空值哨兵与 Jitter TTL）；
     * Redis 未启用或故障时平滑执行 {@code localFallbackLoader} 降级至本地有界缓存。
     *
     * @param key                 受 {@code ateng.boot.redis.key-prefix} 托管的领域相对缓存键
     * @param valueType           期望反序列化载荷类型
     * @param loader              未命中时的回源加载器
     * @param ttl                 期望写入的分布式缓存过期时间
     * @param localFallbackLoader Redis 不可用时的本地有界缓存读取/回源闭包
     * @param <T>                 载荷类型
     * @return 命中或回源加载的结果对象（未查到返回 {@code null}）
     */
    public <T> T getOrLoadL2(
            String key,
            Class<T> valueType,
            Supplier<T> loader,
            Duration ttl,
            Supplier<T> localFallbackLoader
    ) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        Objects.requireNonNull(valueType, "valueType 不能为空");
        Objects.requireNonNull(loader, "loader 不能为空");
        Objects.requireNonNull(localFallbackLoader, "localFallbackLoader 不能为空");

        RedisCacheTemplate template = resolveActiveTemplate(redisCacheTemplateProvider);
        if (template == null) {
            return localFallbackLoader.get();
        }

        // 区分回源业务异常与 Redis/反序列化故障，防止业务异常被吞没并触发本地二次回源
        AtomicReference<RuntimeException> loaderFailure = new AtomicReference<>();
        try {
            return template.getOrLoad(
                    key,
                    valueType,
                    k -> {
                        try {
                            return loader.get();
                        } catch (RuntimeException loaderEx) {
                            loaderFailure.set(loaderEx);
                            throw loaderEx;
                        }
                    },
                    ttl,
                    null
            );
        } catch (RuntimeException redisEx) {
            if (loaderFailure.get() != null) {
                throw loaderFailure.get();
            }
            if (isConnectivityFailure(redisEx)) {
                log.warn("访问 RedisCacheTemplate 连接或超时异常，跳过远程驱逐直接降级本地缓存回源: key={}", key, redisEx);
            } else {
                log.warn("访问 RedisCacheTemplate 异常，清理脏键并降级本地缓存回源: key={}", key, redisEx);
                evictL2Safely(template, key);
            }
            return localFallbackLoader.get();
        }
    }

    /**
     * 主动驱逐纯 L2 分布式缓存与本地降级缓存中的键。
     *
     * @param key              受 {@code ateng.boot.redis.key-prefix} 托管的领域相对缓存键
     * @param localEvictAction 本地缓存清理回调（可空）
     */
    public void evictL2(String key, Runnable localEvictAction) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        if (localEvictAction != null) {
            localEvictAction.run();
        }
        RedisCacheTemplate template = resolveActiveTemplate(redisCacheTemplateProvider);
        if (template != null) {
            evictL2Safely(template, key);
        }
    }

    /**
     * 通过 {@link DistributedLockTemplate} 争用分布式锁并受管执行任务（自动看门狗续期）；
     * Redis 未启用或锁模板故障时平滑降级直通执行（依靠数据库约束兜底）。
     *
     * @param lockKey  受 {@code ateng.boot.redis.key-prefix} 托管的相对分布式锁键
     * @param waitTime 最长等待获取锁时长（空或负值时回落 {@code 5s}）
     * @param action   持锁执行的业务逻辑
     * @return 成功获取锁并执行返回 {@code true}；等待超时未抢到锁返回 {@code false}
     */
    public boolean tryExecuteWithLock(String lockKey, Duration waitTime, Runnable action) {
        Objects.requireNonNull(action, "action 不能为空");
        DistributedLockTemplate lockTemplate = resolveActiveTemplate(distributedLockTemplateProvider);
        if (lockTemplate == null || !StringUtils.hasText(lockKey)) {
            action.run();
            return true;
        }

        Duration effectiveWait = waitTime != null && !waitTime.isNegative() ? waitTime : DEFAULT_LOCK_WAIT_TIME;
        AtomicBoolean actionInvoked = new AtomicBoolean(false);
        AtomicReference<RuntimeException> actionFailure = new AtomicReference<>();
        try {
            return lockTemplate.tryExecute(lockKey, effectiveWait, () -> runTracked(action, actionInvoked, actionFailure));
        } catch (RuntimeException lockEx) {
            rethrowIfActionAlreadyEntered(actionInvoked, actionFailure, lockEx);
            if (Thread.currentThread().isInterrupted()) {
                throw lockEx;
            }
            log.warn("DistributedLockTemplate 加锁失败，降级直通执行: lockKey={}", lockKey, lockEx);
            action.run();
            return true;
        }
    }

    /**
     * 通过 {@link RedisIdempotentTemplate} 执行幂等去重保护的动作；
     * 重复投递静默跳过并返回 {@code false}；Redis 未启用或抢占异常时降级至本地有界去重表执行。
     *
     * @param key                受 {@code ateng.boot.redis.key-prefix} 托管的相对幂等键
     * @param ttl                幂等凭据保留时长
     * @param deleteOnFailure    业务执行失败时是否删除幂等凭据允许上游重试
     * @param action             待保护的业务动作
     * @param localDedupFallback 本地有界 LRU 去重降级缓存
     * @return 首次消费并成功执行返回 {@code true}；命中重复幂等跳过返回 {@code false}
     */
    public boolean executeIdempotent(
            String key,
            Duration ttl,
            boolean deleteOnFailure,
            Runnable action,
            ConcurrentLruCache<String, Boolean> localDedupFallback
    ) {
        Objects.requireNonNull(action, "action 不能为空");
        Objects.requireNonNull(localDedupFallback, "localDedupFallback 不能为空");
        if (!StringUtils.hasText(key)) {
            action.run();
            return true;
        }

        RedisIdempotentTemplate template = resolveActiveTemplate(redisIdempotentTemplateProvider);
        if (template == null) {
            return executeIdempotentLocally(key, action, localDedupFallback);
        }

        AtomicBoolean actionInvoked = new AtomicBoolean(false);
        AtomicReference<RuntimeException> actionFailure = new AtomicReference<>();
        try {
            template.execute(key, ttl, deleteOnFailure, () -> runTracked(action, actionInvoked, actionFailure));
            return true;
        } catch (IdempotentException duplicateEx) {
            if (actionFailure.get() != null) {
                throw actionFailure.get();
            }
            log.debug("幂等键已存在或正在处理中，静默跳过重复执行: key={}", key);
            return false;
        } catch (RuntimeException redisEx) {
            rethrowIfActionAlreadyEntered(actionInvoked, actionFailure, redisEx);
            log.warn("RedisIdempotentTemplate 抢占异常，降级使用本地有界去重表执行: key={}", key, redisEx);
            return executeIdempotentLocally(key, action, localDedupFallback);
        }
    }

    private static void runTracked(
            Runnable action,
            AtomicBoolean actionInvoked,
            AtomicReference<RuntimeException> actionFailure
    ) {
        actionInvoked.set(true);
        try {
            action.run();
        } catch (RuntimeException ex) {
            actionFailure.set(ex);
            throw ex;
        }
    }

    private static void rethrowIfActionAlreadyEntered(
            AtomicBoolean actionInvoked,
            AtomicReference<RuntimeException> actionFailure,
            RuntimeException outerEx
    ) {
        if (actionFailure.get() != null) {
            throw actionFailure.get();
        }
        if (actionInvoked.get()) {
            throw outerEx;
        }
    }

    private static boolean executeIdempotentLocally(
            String key,
            Runnable action,
            ConcurrentLruCache<String, Boolean> localDedupFallback
    ) {
        AtomicBoolean firstAttempt = new AtomicBoolean(false);
        localDedupFallback.computeIfAbsent(key, k -> {
            firstAttempt.set(true);
            return Boolean.TRUE;
        });
        if (!firstAttempt.get()) {
            return false;
        }
        try {
            action.run();
            return true;
        } catch (RuntimeException ex) {
            localDedupFallback.remove(key);
            throw ex;
        }
    }

    private TwoLevelCache resolveTwoLevelCache(String cacheSpace) {
        TwoLevelCacheManager manager = StringUtils.hasText(cacheSpace)
                ? resolveActiveTemplate(twoLevelCacheManagerProvider)
                : null;
        return manager != null ? manager.getTwoLevelCache(cacheSpace) : null;
    }

    private <T> T resolveActiveTemplate(ObjectProvider<T> provider) {
        if (!isRedisActive() || provider == null) {
            return null;
        }
        return provider.getIfAvailable();
    }

    private void evictL2Safely(RedisCacheTemplate template, String key) {
        try {
            template.evict(key);
        } catch (RuntimeException ex) {
            log.warn("驱逐 RedisCacheTemplate 缓存失败: key={}", key, ex);
        }
    }

    private boolean evictTwoLevelSafely(TwoLevelCache twoLevelCache, String cacheSpace, String key) {
        try {
            twoLevelCache.evict(key);
            return true;
        } catch (RuntimeException ex) {
            log.warn("驱逐两级缓存失败: space={}, key={}", cacheSpace, key, ex);
            return false;
        }
    }

    private <T> void writeTwoLevelSafely(
            TwoLevelCache twoLevelCache,
            String cacheSpace,
            String key,
            T loaded,
            Duration ttl,
            ConcurrentLruCache<String, T> localFallback
    ) {
        try {
            if (isPositiveDuration(ttl)) {
                twoLevelCache.put(key, loaded, ttl);
            } else {
                twoLevelCache.put(key, loaded);
            }
        } catch (RuntimeException writeEx) {
            log.warn("回写两级缓存失败，降级写入本地有界缓存: space={}, key={}", cacheSpace, key, writeEx);
            localFallback.put(key, loaded);
        }
    }

    private static boolean isPositiveDuration(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    private static boolean isConnectivityFailure(Throwable error) {
        // 连接/超时类异常不再补发远程 evict，避免 Redis 宕机时放大双倍超时开销
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof TransientDataAccessException
                    || cursor instanceof DataAccessResourceFailureException
                    || cursor instanceof TimeoutException
                    || cursor instanceof ConnectException
                    || cursor instanceof SocketTimeoutException) {
                return true;
            }
            String simpleName = cursor.getClass().getSimpleName();
            if (simpleName.endsWith("ConnectionException") || simpleName.endsWith("TimeoutException")) {
                return true;
            }
        }
        return false;
    }
}

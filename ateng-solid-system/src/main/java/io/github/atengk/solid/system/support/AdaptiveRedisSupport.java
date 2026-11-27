package io.github.atengk.solid.system.support;

import io.github.atengk.boot.core.concurrent.ConcurrentLruCache;
import io.github.atengk.boot.redis.cache.spi.TwoLevelCache;
import io.github.atengk.boot.redis.cache.template.RedisCacheTemplate;
import io.github.atengk.boot.redis.cache.twolevel.TwoLevelCacheManager;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 系统管理域可选 Redis 自适应协同门面，按 {@code ateng.boot.redis.enabled} 与容器装配状态自动切换
 * {@link TwoLevelCacheManager} / {@link RedisCacheTemplate} 受管能力与本地有界内存降级路径。
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

    private final ObjectProvider<Environment> environmentProvider;
    private final ObjectProvider<TwoLevelCacheManager> twoLevelCacheManagerProvider;
    private final ObjectProvider<RedisCacheTemplate> redisCacheTemplateProvider;

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

        RedisCacheTemplate template = resolveRedisCacheTemplate();
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
        RedisCacheTemplate template = resolveRedisCacheTemplate();
        if (template != null) {
            evictL2Safely(template, key);
        }
    }

    private TwoLevelCache resolveTwoLevelCache(String cacheSpace) {
        if (!isRedisActive() || !StringUtils.hasText(cacheSpace) || twoLevelCacheManagerProvider == null) {
            return null;
        }
        TwoLevelCacheManager manager = twoLevelCacheManagerProvider.getIfAvailable();
        if (manager == null) {
            return null;
        }
        return manager.getTwoLevelCache(cacheSpace);
    }

    private RedisCacheTemplate resolveRedisCacheTemplate() {
        if (!isRedisActive() || redisCacheTemplateProvider == null) {
            return null;
        }
        return redisCacheTemplateProvider.getIfAvailable();
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

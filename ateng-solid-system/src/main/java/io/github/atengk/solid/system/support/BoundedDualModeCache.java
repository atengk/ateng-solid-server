package io.github.atengk.solid.system.support;

import io.github.atengk.boot.core.concurrent.ConcurrentLruCache;
import io.github.atengk.boot.core.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 通用 Redis + 有界本地 LRU 双模缓存协作者：优先通过 {@link StringRedisTemplate} 存储紧凑 JSON，
 * 当 Redis 未启用或出现运行期异常时平滑降级至底座 {@link ConcurrentLruCache}。
 *
 * @param <V> 缓存载荷类型
 * @author Ateng
 * @since 2026-11-25
 */
@Slf4j
public class BoundedDualModeCache<V> {

    /**
     * 未启用 Redis 时默认本地降级缓存容量上限
     */
    public static final int DEFAULT_LOCAL_MAX_CAPACITY = 10_000;

    private final ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider;
    private final ObjectProvider<Environment> environmentProvider;
    private final Supplier<Duration> ttlSupplier;
    private final Duration defaultTtl;
    private final Function<String, V> deserializer;
    private final ConcurrentLruCache<String, V> localCache;

    /**
     * 构造双模有界缓存实例（本地容量上限 {@value #DEFAULT_LOCAL_MAX_CAPACITY}）。
     *
     * @param stringRedisTemplateProvider Redis 模板提供器
     * @param environmentProvider         Spring 环境提供器（用于探测 {@code ateng.boot.redis.enabled}）
     * @param ttlSupplier                 动态 TTL 读取器
     * @param defaultTtl                  默认 TTL 兜底值
     * @param deserializer                JSON 字符串反序列化器（解析失败须抛出 {@link RuntimeException} 触发脏键自愈）
     */
    public BoundedDualModeCache(
            ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider,
            ObjectProvider<Environment> environmentProvider,
            Supplier<Duration> ttlSupplier,
            Duration defaultTtl,
            Function<String, V> deserializer
    ) {
        this.stringRedisTemplateProvider = stringRedisTemplateProvider;
        this.environmentProvider = environmentProvider;
        this.ttlSupplier = ttlSupplier;
        this.defaultTtl = Objects.requireNonNull(defaultTtl, "defaultTtl 不能为空");
        this.deserializer = Objects.requireNonNull(deserializer, "deserializer 不能为空");
        this.localCache = new ConcurrentLruCache<>(DEFAULT_LOCAL_MAX_CAPACITY, resolveEffectiveTtl());
    }

    /**
     * 按完整缓存键读取缓存；未命中时执行 {@code loader} 回源并写入对应缓存介质。
     *
     * @param fullKey 完整缓存键
     * @param loader  回源加载函数
     * @return 缓存载荷或回源结果
     */
    public V getOrLoad(String fullKey, Supplier<V> loader) {
        if (!StringUtils.hasText(fullKey)) {
            return null;
        }
        StringRedisTemplate redis = RedisActivationResolver.resolveActive(
                stringRedisTemplateProvider, environmentProvider
        );
        if (redis != null) {
            return getOrLoadFromRedis(redis, fullKey, loader);
        }
        return localCache.computeIfAbsent(fullKey, k -> loader.get());
    }

    /**
     * 主动驱逐指定完整缓存键在本地与 Redis 中的缓存记录。
     *
     * @param fullKey 完整缓存键
     */
    public void evict(String fullKey) {
        if (!StringUtils.hasText(fullKey)) {
            return;
        }
        localCache.remove(fullKey);
        StringRedisTemplate redis = RedisActivationResolver.resolveActive(
                stringRedisTemplateProvider, environmentProvider
        );
        if (redis != null) {
            try {
                redis.delete(fullKey);
            } catch (RuntimeException ex) {
                log.warn("删除 Redis 缓存失败: key={}", fullKey, ex);
            }
        }
    }

    /**
     * 返回当前本地降级缓存条目数（供测试与诊断观测）。
     *
     * @return 本地缓存大小
     */
    public int localSize() {
        return localCache.size();
    }

    private V getOrLoadFromRedis(StringRedisTemplate redis, String fullKey, Supplier<V> loader) {
        try {
            String rawValue = redis.opsForValue().get(fullKey);
            if (StringUtils.hasText(rawValue)) {
                try {
                    V cached = deserializer.apply(rawValue);
                    if (cached != null) {
                        return cached;
                    }
                } catch (RuntimeException parseEx) {
                    log.warn("解析 Redis 缓存失败，自动清理脏键并回源: key={}", fullKey, parseEx);
                    redis.delete(fullKey);
                }
            }
        } catch (RuntimeException redisReadEx) {
            log.warn("读取 Redis 缓存异常，降级为本地缓存回源: key={}", fullKey, redisReadEx);
            return localCache.computeIfAbsent(fullKey, k -> loader.get());
        }

        // 业务回源与 Redis 回写解耦：防止回源业务异常被误判为 Redis 故障并触发本地二次重复查询
        V loaded = loader.get();
        if (loaded != null) {
            try {
                redis.opsForValue().set(fullKey, JsonUtil.toJsonString(loaded), resolveEffectiveTtl());
            } catch (RuntimeException redisWriteEx) {
                log.warn("回写 Redis 缓存失败，已降级写入本地缓存: key={}", fullKey, redisWriteEx);
                localCache.computeIfAbsent(fullKey, k -> loaded);
            }
        }
        return loaded;
    }

    private Duration resolveEffectiveTtl() {
        Duration configured = ttlSupplier != null ? ttlSupplier.get() : null;
        if (configured == null || configured.isZero() || configured.isNegative()) {
            return defaultTtl;
        }
        return configured;
    }
}

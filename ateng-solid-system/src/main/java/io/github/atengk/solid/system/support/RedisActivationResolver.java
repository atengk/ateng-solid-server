package io.github.atengk.solid.system.support;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 可选 Redis 客户端可用性解析器：在 {@code ateng.boot.redis.enabled=false}（单机降级或离线测试）场景下抑制误连默认 {@code localhost:6379}。
 *
 * @author Ateng
 * @since 2026-11-25
 */
public final class RedisActivationResolver {

    private static final String REDIS_ENABLED_KEY = "ateng.boot.redis.enabled";

    private RedisActivationResolver() {
    }

    /**
     * 当且仅当 {@code ateng.boot.redis.enabled} 未显式关闭且容器中存在 {@link StringRedisTemplate} 时返回可用实例。
     *
     * @param redisProvider       {@link StringRedisTemplate} 提供器
     * @param environmentProvider Spring {@link Environment} 提供器（未注入时默认按开启处理，兼容单元测试传入模拟对象）
     * @return 可用的 {@link StringRedisTemplate}；Redis 已关闭或实例不存在时返回 {@code null}
     */
    public static StringRedisTemplate resolveActive(
            ObjectProvider<StringRedisTemplate> redisProvider,
            ObjectProvider<Environment> environmentProvider
    ) {
        Environment environment = environmentProvider != null ? environmentProvider.getIfAvailable() : null;
        if (environment != null && !environment.getProperty(REDIS_ENABLED_KEY, Boolean.class, Boolean.TRUE)) {
            return null;
        }
        return redisProvider != null ? redisProvider.getIfAvailable() : null;
    }
}

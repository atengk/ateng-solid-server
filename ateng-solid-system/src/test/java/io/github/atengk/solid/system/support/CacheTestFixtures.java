package io.github.atengk.solid.system.support;

import io.github.atengk.boot.redis.cache.template.RedisCacheTemplate;
import io.github.atengk.boot.redis.cache.twolevel.TwoLevelCacheManager;
import io.github.atengk.boot.redis.idempotent.RedisIdempotentTemplate;
import io.github.atengk.boot.redis.lock.template.DistributedLockTemplate;
import io.github.atengk.solid.system.properties.SolidSystemProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 缓存与协同组件单元测试共享装配夹具，集中提供可选 Redis 模板提供器、自适应门面、环境与缓存 TTL 配置构造。
 *
 * @author Ateng
 * @since 2026-11-25
 */
public final class CacheTestFixtures {

    private CacheTestFixtures() {
    }

    @SuppressWarnings("unchecked")
    public static <T> ObjectProvider<T> providerOf(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    @SuppressWarnings("unchecked")
    public static ObjectProvider<Environment> emptyEnvProvider() {
        return mock(ObjectProvider.class);
    }

    public static ObjectProvider<Environment> redisDisabledEnvProvider() {
        MockEnvironment env = new MockEnvironment().withProperty("ateng.boot.redis.enabled", "false");
        return providerOf(env);
    }

    public static AdaptiveRedisSupport adaptiveSupport(
            TwoLevelCacheManager twoLevelCacheManager,
            RedisCacheTemplate redisCacheTemplate,
            ObjectProvider<Environment> environmentProvider
    ) {
        return adaptiveSupport(twoLevelCacheManager, redisCacheTemplate, null, null, environmentProvider);
    }

    public static AdaptiveRedisSupport adaptiveSupport(
            TwoLevelCacheManager twoLevelCacheManager,
            RedisCacheTemplate redisCacheTemplate,
            DistributedLockTemplate distributedLockTemplate,
            RedisIdempotentTemplate redisIdempotentTemplate,
            ObjectProvider<Environment> environmentProvider
    ) {
        return new AdaptiveRedisSupport(
                environmentProvider,
                providerOf(twoLevelCacheManager),
                providerOf(redisCacheTemplate),
                providerOf(distributedLockTemplate),
                providerOf(redisIdempotentTemplate)
        );
    }

    public static AdaptiveRedisSupport offlineAdaptiveSupport() {
        return adaptiveSupport(null, null, null, null, emptyEnvProvider());
    }

    static SolidSystemProperties defaultProps() {
        return propsWithTtl(Duration.ofMinutes(30), Duration.ofMinutes(30));
    }

    static SolidSystemProperties propsWithTtl(Duration dictTtl, Duration configTtl) {
        SolidSystemProperties props = new SolidSystemProperties();
        props.getCache().setDictTtl(dictTtl);
        props.getCache().setConfigTtl(configTtl);
        return props;
    }
}

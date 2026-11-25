package io.github.atengk.solid.system.support;

import io.github.atengk.solid.system.properties.SolidSystemProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 缓存单元测试共享装配夹具，集中提供 Redis 模板、环境与缓存 TTL 配置构造。
 *
 * @author Ateng
 * @since 2026-11-25
 */
final class CacheTestFixtures {

    private CacheTestFixtures() {
    }

    @SuppressWarnings("unchecked")
    static ObjectProvider<StringRedisTemplate> redisProviderOf(StringRedisTemplate redisTemplate) {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redisTemplate);
        return provider;
    }

    @SuppressWarnings("unchecked")
    static ObjectProvider<Environment> emptyEnvProvider() {
        return mock(ObjectProvider.class);
    }

    static SolidSystemProperties defaultProps() {
        SolidSystemProperties props = new SolidSystemProperties();
        props.getCache().setDictTtl(Duration.ofMinutes(30));
        props.getCache().setConfigTtl(Duration.ofMinutes(30));
        return props;
    }
}

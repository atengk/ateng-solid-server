package io.github.atengk.solid.system.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.atengk.solid.system.support.CacheTestFixtures.defaultProps;
import static io.github.atengk.solid.system.support.CacheTestFixtures.emptyEnvProvider;
import static io.github.atengk.solid.system.support.CacheTestFixtures.redisProviderOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SysConfigCache} 系统参数键值对双模缓存 Layer 1 单元测试。
 *
 * @author Ateng
 * @since 2026-11-25
 */
class SysConfigCacheTest {

    @Test
    @DisplayName("参数键空白或租户空值应当安全返回 null 且不回源，无 Redis 时支持哨兵防穿透与驱逐")
    void shouldHandleInvalidArgsAndLocalFallbackWithSentinelAndEvict() {
        SysConfigCache cache = new SysConfigCache(redisProviderOf(null), defaultProps(), emptyEnvProvider());
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
    @DisplayName("Redis 启用时应当按 ateng:solid:config:{tenantId}:{configKey} 读写 30m 紧凑 JSON 并自愈脏键")
    @SuppressWarnings("unchecked")
    void shouldUseCompactJsonInRedisWithSentinelAndCorruptionRecovery() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        SysConfigCache cache = new SysConfigCache(redisProviderOf(redis), defaultProps(), emptyEnvProvider());

        String key = SysConfigCache.buildCacheKey(9201L, "sys.user.initPassword");
        assertThat(key).isEqualTo("ateng:solid:config:9201:sys.user.initPassword");

        when(valueOps.get(key)).thenReturn(null);
        String loaded = cache.getOrLoad(9201L, "sys.user.initPassword", () -> "Ateng@2026");
        assertThat(loaded).isEqualTo("Ateng@2026");

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(key), jsonCaptor.capture(), eq(Duration.ofMinutes(30)));
        String compactJson = jsonCaptor.getValue();
        assertThat(compactJson).doesNotContain("\n").doesNotContain("@class");

        when(valueOps.get(key)).thenReturn(compactJson);
        AtomicInteger hitCalls = new AtomicInteger();
        assertThat(cache.getOrLoad(9201L, "sys.user.initPassword", () -> {
            hitCalls.incrementAndGet();
            return "other";
        })).isEqualTo("Ateng@2026");
        assertThat(hitCalls.get()).isZero();

        // 损坏 JSON 删键回源
        String badKey = SysConfigCache.buildCacheKey(0L, "broken.key");
        when(valueOps.get(badKey)).thenReturn("{bad-json");
        assertThat(cache.getOrLoad(0L, "broken.key", () -> "repaired")).isEqualTo("repaired");
        verify(redis).delete(badKey);

        cache.evict(9201L, "sys.user.initPassword");
        verify(redis, times(1)).delete(key);
    }
}

package io.github.atengk.solid.system.support;

import io.github.atengk.solid.system.vo.DictDataVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
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
 * {@link DictDataCache} 动态字典双模缓存 Layer 1 单元测试。
 *
 * @author Ateng
 * @since 2026-11-25
 */
class DictDataCacheTest {

    @Test
    @DisplayName("传入空白字典类型时应当直接返回空列表且不触发回源")
    void shouldReturnEmptyWhenDictTypeBlank() {
        DictDataCache cache = new DictDataCache(redisProviderOf(null), defaultProps(), emptyEnvProvider());
        AtomicInteger calls = new AtomicInteger();

        assertThat(cache.getOrLoad("  ", key -> {
            calls.incrementAndGet();
            return List.of();
        })).isEmpty();
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("无 Redis 降级模式下应当命中本地有界缓存、空列表防穿透并在 evict 后重新回源")
    void shouldCacheEmptyListAndEvictInBoundedLocalMode() {
        DictDataCache cache = new DictDataCache(redisProviderOf(null), defaultProps(), emptyEnvProvider());
        AtomicInteger calls = new AtomicInteger();

        List<DictDataVO> first = cache.getOrLoad("sys_user_gender", key -> {
            calls.incrementAndGet();
            return List.of(DictDataVO.builder()
                    .id(11001L)
                    .dictType("sys_user_gender")
                    .dictLabel("男")
                    .dictValue("0")
                    .tagType("primary")
                    .sort(1)
                    .isDefault(0)
                    .status(0)
                    .build());
        });
        List<DictDataVO> second = cache.getOrLoad("sys_user_gender", key -> {
            calls.incrementAndGet();
            return List.of();
        });

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(second.get(0).getDictLabel()).isEqualTo("男");
        assertThat(calls.get()).isEqualTo(1);

        // 停用或不存在类型缓存空集合防穿透
        AtomicInteger emptyCalls = new AtomicInteger();
        assertThat(cache.getOrLoad("disabled_type", key -> {
            emptyCalls.incrementAndGet();
            return Collections.emptyList();
        })).isEmpty();
        assertThat(cache.getOrLoad("disabled_type", key -> {
            emptyCalls.incrementAndGet();
            return Collections.emptyList();
        })).isEmpty();
        assertThat(emptyCalls.get()).isEqualTo(1);

        cache.evict("sys_user_gender");
        cache.getOrLoad("sys_user_gender", key -> {
            calls.incrementAndGet();
            return List.of();
        });
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Redis 启用时应当按 ateng:solid:dict:{dictType} 读写 30m 紧凑 JSON 并命中空列表哨兵")
    @SuppressWarnings("unchecked")
    void shouldWriteAndHitCompactJsonAndEmptySentinelInRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        DictDataCache cache = new DictDataCache(redisProviderOf(redis), defaultProps(), emptyEnvProvider());

        String key = DictDataCache.buildCacheKey("sys_yes_no");
        DictDataVO item = sampleDictItem("sys_yes_no", "Y", "是");

        when(valueOps.get(key)).thenReturn(null);
        assertThat(cache.getOrLoad("sys_yes_no", k -> List.of(item))).hasSize(1);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(key), jsonCaptor.capture(), eq(Duration.ofMinutes(30)));
        String compactJson = jsonCaptor.getValue();
        assertThat(compactJson).doesNotContain("\n").doesNotContain("@class");

        when(valueOps.get(key)).thenReturn(compactJson);
        AtomicInteger hitCalls = new AtomicInteger();
        List<DictDataVO> hit = cache.getOrLoad("sys_yes_no", k -> {
            hitCalls.incrementAndGet();
            return List.of();
        });
        assertThat(hitCalls.get()).isZero();
        assertThat(hit).extracting(DictDataVO::getDictValue).containsExactly("Y");

        // 命中空列表哨兵 "[]" 直接返回空且不回源
        String disabledKey = DictDataCache.buildCacheKey("disabled_dict");
        when(valueOps.get(disabledKey)).thenReturn("[]");
        assertThat(cache.getOrLoad("disabled_dict", k -> {
            hitCalls.incrementAndGet();
            return List.of(item);
        })).isEmpty();
        assertThat(hitCalls.get()).isZero();
    }

    @Test
    @DisplayName("Redis 存在损坏字典 JSON 时应当删键自愈回源，并在 evict 时删除对应缓存键")
    @SuppressWarnings("unchecked")
    void shouldRecoverCorruptJsonAndDeleteOnEvictInRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        DictDataCache cache = new DictDataCache(redisProviderOf(redis), defaultProps(), emptyEnvProvider());

        DictDataVO item = sampleDictItem("sys_yes_no", "Y", "是");
        String corruptKey = DictDataCache.buildCacheKey("bad_dict");
        when(valueOps.get(corruptKey)).thenReturn("{corrupt");

        assertThat(cache.getOrLoad("bad_dict", k -> List.of(item))).hasSize(1);
        verify(redis).delete(corruptKey);

        cache.evict("sys_yes_no");
        verify(redis, times(1)).delete(DictDataCache.buildCacheKey("sys_yes_no"));
    }

    private static DictDataVO sampleDictItem(String dictType, String value, String label) {
        return DictDataVO.builder()
                .id(11101L)
                .dictType(dictType)
                .dictLabel(label)
                .dictValue(value)
                .tagType("primary")
                .sort(1)
                .isDefault(0)
                .status(0)
                .build();
    }

    @Test
    @DisplayName("本地有界缓存超过 10000 上限时应当自动淘汰旧键维持容量边界")
    void shouldEnforceTenThousandCapInLocalFallback() {
        DictDataCache cache = new DictDataCache(redisProviderOf(null), defaultProps(), emptyEnvProvider());
        for (int i = 0; i < 10_040; i++) {
            cache.getOrLoad("dict_" + i, k -> Collections.emptyList());
        }
        assertThat(cache.localSize()).isLessThanOrEqualTo(BoundedDualModeCache.DEFAULT_LOCAL_MAX_CAPACITY);
    }
}

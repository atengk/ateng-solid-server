package io.github.atengk.solid.system.support;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.redis.cache.spi.TwoLevelCache;
import io.github.atengk.boot.redis.cache.twolevel.TwoLevelCacheManager;
import io.github.atengk.solid.system.vo.DictDataVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.atengk.solid.system.support.CacheTestFixtures.adaptiveSupport;
import static io.github.atengk.solid.system.support.CacheTestFixtures.defaultProps;
import static io.github.atengk.solid.system.support.CacheTestFixtures.emptyEnvProvider;
import static io.github.atengk.solid.system.support.CacheTestFixtures.offlineAdaptiveSupport;
import static io.github.atengk.solid.system.support.CacheTestFixtures.propsWithTtl;
import static io.github.atengk.solid.system.support.CacheTestFixtures.redisDisabledEnvProvider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link DictDataCache} 动态字典两级缓存与离线降级 Layer 1 单元测试。
 *
 * @author Ateng
 * @since 2026-11-25
 */
class DictDataCacheTest {

    @Test
    @DisplayName("传入空白字典类型时应当直接返回空列表且不触发回源")
    void shouldReturnEmptyWhenDictTypeBlank() {
        DictDataCache cache = new DictDataCache(offlineAdaptiveSupport(), defaultProps());
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
        DictDataCache cache = new DictDataCache(offlineAdaptiveSupport(), defaultProps());
        AtomicInteger calls = new AtomicInteger();

        List<DictDataVO> first = cache.getOrLoad("sys_user_gender", key -> {
            calls.incrementAndGet();
            return List.of(sampleDictItem("sys_user_gender", "0", "男"));
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
    @DisplayName("Redis 启用时应当以领域相对键委派 TwoLevelCacheManager（dict 空间）并按配置 TTL 写入类型安全载荷")
    void shouldDelegateToTwoLevelCacheWithRelativeKeyAndDynamicTtl() {
        TwoLevelCacheManager cacheManager = mock(TwoLevelCacheManager.class);
        TwoLevelCache twoLevelCache = mock(TwoLevelCache.class);
        when(cacheManager.getTwoLevelCache(DictDataCache.CACHE_SPACE)).thenReturn(twoLevelCache);

        DictDataCache cache = new DictDataCache(
                adaptiveSupport(cacheManager, null, emptyEnvProvider()),
                defaultProps()
        );

        String relativeKey = DictDataCache.buildCacheKey("sys_yes_no");
        assertThat(relativeKey).isEqualTo("sys_yes_no");
        assertThat(relativeKey).doesNotContain("ateng:solid:");

        DictDataVO item = sampleDictItem("sys_yes_no", "Y", "是");
        when(twoLevelCache.get(relativeKey, DictDataCache.DictCachePayload.class)).thenReturn(null);
        assertThat(cache.getOrLoad(" sys_yes_no ", k -> List.of(item))).hasSize(1);

        ArgumentCaptor<DictDataCache.DictCachePayload> payloadCaptor =
                ArgumentCaptor.forClass(DictDataCache.DictCachePayload.class);
        verify(twoLevelCache).put(eq(relativeKey), payloadCaptor.capture(), eq(Duration.ofMinutes(30)));
        assertThat(payloadCaptor.getValue().getItems()).extracting(DictDataVO::getDictValue).containsExactly("Y");

        // 命中 TwoLevelCache 载荷不再回源
        when(twoLevelCache.get(relativeKey, DictDataCache.DictCachePayload.class))
                .thenReturn(payloadCaptor.getValue());
        AtomicInteger hitCalls = new AtomicInteger();
        List<DictDataVO> hit = cache.getOrLoad("sys_yes_no", k -> {
            hitCalls.incrementAndGet();
            return List.of();
        });
        assertThat(hitCalls.get()).isZero();
        assertThat(hit).extracting(DictDataVO::getDictValue).containsExactly("Y");

        // 空列表防穿透载荷命中后不回源
        String disabledKey = DictDataCache.buildCacheKey("disabled_dict");
        when(twoLevelCache.get(disabledKey, DictDataCache.DictCachePayload.class))
                .thenReturn(new DictDataCache.DictCachePayload(Collections.emptyList()));
        assertThat(cache.getOrLoad("disabled_dict", k -> {
            hitCalls.incrementAndGet();
            return List.of(item);
        })).isEmpty();
        assertThat(hitCalls.get()).isZero();
    }

    @Test
    @DisplayName("传入空 loader 时应当快速抛出 NullPointerException")
    void shouldThrowWhenLoaderIsNull() {
        DictDataCache cache = new DictDataCache(offlineAdaptiveSupport(), defaultProps());
        assertThatThrownBy(() -> cache.getOrLoad("sys_yes_no", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("TwoLevelCache 读异常损坏时应当自愈淘汰并回源重写")
    void shouldRecoverFromCorruptedPayloadAndRewriteTwoLevelCache() {
        TwoLevelCacheManager cacheManager = mock(TwoLevelCacheManager.class);
        TwoLevelCache twoLevelCache = mock(TwoLevelCache.class);
        when(cacheManager.getTwoLevelCache(DictDataCache.CACHE_SPACE)).thenReturn(twoLevelCache);

        DictDataCache cache = new DictDataCache(
                adaptiveSupport(cacheManager, null, emptyEnvProvider()),
                defaultProps()
        );
        DictDataVO item = sampleDictItem("sys_yes_no", "Y", "是");

        when(twoLevelCache.get("bad_dict", DictDataCache.DictCachePayload.class))
                .thenThrow(new IllegalArgumentException("corrupt payload"));
        assertThat(cache.getOrLoad("bad_dict", k -> List.of(item))).hasSize(1);

        verify(twoLevelCache, times(1)).evict("bad_dict");
        verify(twoLevelCache).put(eq("bad_dict"), any(DictDataCache.DictCachePayload.class), eq(Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("Redis 连接或超时异常时应当跳过冗余远程驱逐并直接降级本地有界缓存")
    void shouldSkipRemoteEvictAndFallbackToLocalOnConnectivityTimeout() {
        TwoLevelCacheManager cacheManager = mock(TwoLevelCacheManager.class);
        TwoLevelCache twoLevelCache = mock(TwoLevelCache.class);
        when(cacheManager.getTwoLevelCache(DictDataCache.CACHE_SPACE)).thenReturn(twoLevelCache);

        DictDataCache cache = new DictDataCache(
                adaptiveSupport(cacheManager, null, emptyEnvProvider()),
                defaultProps()
        );
        DictDataVO item = sampleDictItem("down_dict", "1", "有效");

        when(twoLevelCache.get("down_dict", DictDataCache.DictCachePayload.class))
                .thenThrow(new QueryTimeoutException("redis timeout"));

        AtomicInteger calls = new AtomicInteger();
        assertThat(cache.getOrLoad("down_dict", k -> {
            calls.incrementAndGet();
            return List.of(item);
        })).hasSize(1);
        assertThat(cache.getOrLoad("down_dict", k -> {
            calls.incrementAndGet();
            return List.of();
        })).hasSize(1);

        assertThat(calls.get()).isEqualTo(1);
        verify(twoLevelCache, never()).evict("down_dict");
    }

    @Test
    @DisplayName("损坏清理 evict 同样异常时应当平滑回落本地有界缓存")
    void shouldFallbackToLocalWhenCorruptionEvictAlsoFails() {
        TwoLevelCacheManager cacheManager = mock(TwoLevelCacheManager.class);
        TwoLevelCache twoLevelCache = mock(TwoLevelCache.class);
        when(cacheManager.getTwoLevelCache(DictDataCache.CACHE_SPACE)).thenReturn(twoLevelCache);

        DictDataCache cache = new DictDataCache(
                adaptiveSupport(cacheManager, null, emptyEnvProvider()),
                defaultProps()
        );
        DictDataVO item = sampleDictItem("down_dict", "1", "有效");

        when(twoLevelCache.get("down_dict", DictDataCache.DictCachePayload.class))
                .thenThrow(new RuntimeException("redis error"));
        doThrow(new RuntimeException("evict failed")).when(twoLevelCache).evict("down_dict");

        AtomicInteger calls = new AtomicInteger();
        assertThat(cache.getOrLoad("down_dict", k -> {
            calls.incrementAndGet();
            return List.of(item);
        })).hasSize(1);
        assertThat(cache.getOrLoad("down_dict", k -> {
            calls.incrementAndGet();
            return List.of();
        })).hasSize(1);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("回源抛出 BusinessException 时应当原样透传且不降级重放")
    void shouldPropagateLoaderBusinessExceptionWithoutFallbackReload() {
        TwoLevelCacheManager cacheManager = mock(TwoLevelCacheManager.class);
        TwoLevelCache twoLevelCache = mock(TwoLevelCache.class);
        when(cacheManager.getTwoLevelCache(DictDataCache.CACHE_SPACE)).thenReturn(twoLevelCache);

        DictDataCache cache = new DictDataCache(
                adaptiveSupport(cacheManager, null, emptyEnvProvider()),
                defaultProps()
        );
        when(twoLevelCache.get("biz_err_dict", DictDataCache.DictCachePayload.class)).thenReturn(null);

        AtomicInteger errCalls = new AtomicInteger();
        assertThatThrownBy(() -> cache.getOrLoad("biz_err_dict", k -> {
            errCalls.incrementAndGet();
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "db error");
        })).isInstanceOf(BusinessException.class);
        assertThat(errCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("evict 应当驱动 TwoLevelCache 执行跨节点淘汰并遵循自定义与兜底 TTL")
    void shouldDelegateEvictAndResolveCustomOrDefaultTtl() {
        TwoLevelCacheManager cacheManager = mock(TwoLevelCacheManager.class);
        TwoLevelCache twoLevelCache = mock(TwoLevelCache.class);
        when(cacheManager.getTwoLevelCache(DictDataCache.CACHE_SPACE)).thenReturn(twoLevelCache);

        DictDataCache customCache = new DictDataCache(
                adaptiveSupport(cacheManager, null, emptyEnvProvider()),
                propsWithTtl(Duration.ofMinutes(12), Duration.ofMinutes(30))
        );
        customCache.getOrLoad("custom_dict", k -> List.of(sampleDictItem("custom_dict", "Y", "是")));
        verify(twoLevelCache).put(eq("custom_dict"), any(DictDataCache.DictCachePayload.class), eq(Duration.ofMinutes(12)));

        DictDataCache invalidTtlCache = new DictDataCache(
                adaptiveSupport(cacheManager, null, emptyEnvProvider()),
                propsWithTtl(Duration.ZERO, Duration.ofMinutes(30))
        );
        invalidTtlCache.getOrLoad("zero_ttl_dict", k -> List.of(sampleDictItem("zero_ttl_dict", "N", "否")));
        verify(twoLevelCache).put(eq("zero_ttl_dict"), any(DictDataCache.DictCachePayload.class), eq(Duration.ofMinutes(30)));

        customCache.evict("sys_yes_no");
        verify(twoLevelCache, times(1)).evict("sys_yes_no");
    }

    @Test
    @DisplayName("ateng.boot.redis.enabled=false 时即使容器存在 TwoLevelCacheManager 也应当跳过直连并走本地缓存")
    void shouldBypassTwoLevelCacheWhenRedisDisabled() {
        TwoLevelCacheManager cacheManager = mock(TwoLevelCacheManager.class);
        DictDataCache cache = new DictDataCache(
                adaptiveSupport(cacheManager, null, redisDisabledEnvProvider()),
                defaultProps()
        );

        assertThat(cache.getOrLoad("sys_user_gender", k -> List.of(sampleDictItem("sys_user_gender", "0", "男"))))
                .hasSize(1);
        verifyNoInteractions(cacheManager);
    }

    @Test
    @DisplayName("本地有界缓存超过 10000 上限时应当自动淘汰旧键维持容量边界")
    void shouldEnforceTenThousandCapInLocalFallback() {
        DictDataCache cache = new DictDataCache(offlineAdaptiveSupport(), defaultProps());
        for (int i = 0; i < 10_040; i++) {
            cache.getOrLoad("dict_" + i, k -> Collections.emptyList());
        }
        assertThat(cache.localSize()).isLessThanOrEqualTo(AdaptiveRedisSupport.DEFAULT_LOCAL_MAX_CAPACITY);
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
}

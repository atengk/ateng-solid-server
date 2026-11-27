package io.github.atengk.solid.system.support;

import io.github.atengk.boot.core.concurrent.ConcurrentLruCache;
import io.github.atengk.solid.system.properties.SolidSystemProperties;
import io.github.atengk.solid.system.vo.DictDataVO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 动态数据字典两级缓存：委派 {@code TwoLevelCacheManager} 空间 {@code dict}（相对键 {@code {dictType}}，
 * 受 {@code ateng.boot.redis.key-prefix} 托管），L2 TTL 默认 30m；停用或不存在字典类型缓存空列表防穿透，
 * 写操作提交后精准驱逐并广播全节点淘汰 L1；无 Redis 时经 {@link AdaptiveRedisSupport} 降级至本地有界 LRU。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Component
public class DictDataCache {

    /**
     * 两级缓存空间名（默认完整 L2 物理键：{@code solid:dict:{dictType}}）
     */
    public static final String CACHE_SPACE = "dict";

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private final AdaptiveRedisSupport adaptiveRedisSupport;
    private final SolidSystemProperties systemProperties;
    private final ConcurrentLruCache<String, DictCachePayload> localFallback;

    /**
     * 构造动态字典两级缓存组件。
     *
     * @param adaptiveRedisSupport 可选 Redis 自适应门面
     * @param systemProperties     系统域治理配置（读取 {@code cache.dict-ttl}）
     */
    public DictDataCache(AdaptiveRedisSupport adaptiveRedisSupport, SolidSystemProperties systemProperties) {
        this.adaptiveRedisSupport = Objects.requireNonNull(adaptiveRedisSupport, "adaptiveRedisSupport 不能为空");
        this.systemProperties = systemProperties;
        this.localFallback = new ConcurrentLruCache<>(
                AdaptiveRedisSupport.DEFAULT_LOCAL_MAX_CAPACITY,
                resolveEffectiveTtl()
        );
    }

    /**
     * 构建标准动态字典领域相对缓存键。
     *
     * @param dictType 字典类型编码
     * @return 规范化后的相对缓存键
     */
    public static String buildCacheKey(String dictType) {
        return dictType != null ? dictType.trim() : "";
    }

    /**
     * 按字典类型读取启用态明细列表；未命中时调用 {@code loader} 回源并写入缓存（空列表同样缓存防穿透）。
     *
     * @param dictType 字典类型编码
     * @param loader   回源加载函数
     * @return 不可变字典明细列表（绝不返回 {@code null}）
     */
    public List<DictDataVO> getOrLoad(String dictType, Function<String, List<DictDataVO>> loader) {
        if (!StringUtils.hasText(dictType)) {
            return Collections.emptyList();
        }
        Objects.requireNonNull(loader, "loader 不能为空");
        String normalizedKey = buildCacheKey(dictType);
        DictCachePayload payload = adaptiveRedisSupport.getOrLoadTwoLevel(
                CACHE_SPACE,
                normalizedKey,
                DictCachePayload.class,
                () -> new DictCachePayload(immutableCopy(loader.apply(normalizedKey))),
                resolveEffectiveTtl(),
                localFallback
        );
        return payload != null ? immutableCopy(payload.getItems()) : Collections.emptyList();
    }

    /**
     * 主动驱逐指定字典类型的缓存记录（含本地 L1、L2 与跨节点 Pub/Sub 失效广播）。
     *
     * @param dictType 字典类型编码
     */
    public void evict(String dictType) {
        if (!StringUtils.hasText(dictType)) {
            return;
        }
        adaptiveRedisSupport.evictTwoLevel(CACHE_SPACE, buildCacheKey(dictType), localFallback);
    }

    /**
     * 返回本地降级缓存当前条目数（供单元测试诊断）。
     *
     * @return 本地缓存大小
     */
    public int localSize() {
        return localFallback.size();
    }

    private Duration resolveEffectiveTtl() {
        Duration configured = systemProperties != null && systemProperties.getCache() != null
                ? systemProperties.getCache().getDictTtl()
                : null;
        return AdaptiveRedisSupport.resolveEffectiveTtl(configured, DEFAULT_TTL);
    }

    private static List<DictDataVO> immutableCopy(List<DictDataVO> source) {
        if (CollectionUtils.isEmpty(source)) {
            return Collections.emptyList();
        }
        return List.copyOf(source);
    }

    /**
     * 字典明细列表类型安全缓存包装载荷，避免 Redis 集合反序列化产生泛型擦除。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class DictCachePayload {
        private List<DictDataVO> items;
    }
}

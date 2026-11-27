package io.github.atengk.solid.system.support;

import io.github.atengk.boot.core.concurrent.ConcurrentLruCache;
import io.github.atengk.solid.system.properties.SolidSystemProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 系统参数键值对两级缓存：委派 {@code TwoLevelCacheManager} 空间 {@code config}（相对键 {@code {tenantId}:{configKey}}，
 * 受 {@code ateng.boot.redis.key-prefix} 托管），L2 TTL 默认 30m；通过显式存在标记哨兵防御不存在参数键的缓存穿透，
 * 写操作提交后精准驱逐并广播全节点淘汰 L1；无 Redis 时经 {@link AdaptiveRedisSupport} 降级至本地有界 LRU。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Component
public class SysConfigCache {

    /**
     * 两级缓存空间名（默认完整 L2 物理键：{@code solid:config:{tenantId}:{configKey}}）
     */
    public static final String CACHE_SPACE = "config";

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private final AdaptiveRedisSupport adaptiveRedisSupport;
    private final SolidSystemProperties systemProperties;
    private final ConcurrentLruCache<String, ConfigCacheEntry> localFallback;

    /**
     * 构造系统参数两级缓存组件。
     *
     * @param adaptiveRedisSupport 可选 Redis 自适应门面
     * @param systemProperties     系统域治理配置（读取 {@code cache.config-ttl}）
     */
    public SysConfigCache(AdaptiveRedisSupport adaptiveRedisSupport, SolidSystemProperties systemProperties) {
        this.adaptiveRedisSupport = Objects.requireNonNull(adaptiveRedisSupport, "adaptiveRedisSupport 不能为空");
        this.systemProperties = systemProperties;
        this.localFallback = new ConcurrentLruCache<>(
                AdaptiveRedisSupport.DEFAULT_LOCAL_MAX_CAPACITY,
                resolveEffectiveTtl()
        );
    }

    /**
     * 构建标准系统参数领域相对缓存键。
     *
     * @param tenantId  租户主键
     * @param configKey 参数键名
     * @return 相对缓存键字符串
     */
    public static String buildCacheKey(Long tenantId, String configKey) {
        return tenantId + ":" + (configKey != null ? configKey.trim() : "");
    }

    /**
     * 按租户与参数键读取参数值；未命中时执行 {@code loader} 回源并写入缓存（不存在键同样写入负向哨兵）。
     *
     * @param tenantId  租户主键
     * @param configKey 参数键名
     * @param loader    回源加载函数
     * @return 参数值；参数不存在或入参非法时返回 {@code null}
     */
    public String getOrLoad(Long tenantId, String configKey, Supplier<String> loader) {
        if (tenantId == null || !StringUtils.hasText(configKey)) {
            return null;
        }
        Objects.requireNonNull(loader, "loader 不能为空");
        ConfigCacheEntry entry = adaptiveRedisSupport.getOrLoadTwoLevel(
                CACHE_SPACE,
                buildCacheKey(tenantId, configKey),
                ConfigCacheEntry.class,
                () -> {
                    String loaded = loader.get();
                    return loaded != null ? new ConfigCacheEntry(true, loaded) : new ConfigCacheEntry(false, null);
                },
                resolveEffectiveTtl(),
                localFallback
        );
        return entry != null && entry.isExists() ? entry.getValue() : null;
    }

    /**
     * 主动驱逐指定租户与参数键在本地、L2 与跨节点中的缓存。
     *
     * @param tenantId  租户主键
     * @param configKey 参数键名
     */
    public void evict(Long tenantId, String configKey) {
        if (tenantId == null || !StringUtils.hasText(configKey)) {
            return;
        }
        adaptiveRedisSupport.evictTwoLevel(CACHE_SPACE, buildCacheKey(tenantId, configKey), localFallback);
    }

    /**
     * 返回本地降级缓存条目数（供单元测试诊断）。
     *
     * @return 本地缓存大小
     */
    public int localSize() {
        return localFallback.size();
    }

    private Duration resolveEffectiveTtl() {
        Duration configured = systemProperties != null && systemProperties.getCache() != null
                ? systemProperties.getCache().getConfigTtl()
                : null;
        return AdaptiveRedisSupport.resolveEffectiveTtl(configured, DEFAULT_TTL);
    }

    /**
     * 缓存哨兵载荷：区分键不存在与空字符串值，防止空值穿透回源数据库。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class ConfigCacheEntry {
        private boolean exists;
        private String value;
    }
}

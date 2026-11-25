package io.github.atengk.solid.system.support;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.solid.system.properties.SolidSystemProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 系统参数键值对双模缓存：键名 {@code ateng:solid:config:{tenantId}:{configKey}}，TTL 默认 30m；
 * 通过显式存在标记哨兵防御不存在参数键的缓存穿透，写操作提交后精确驱逐。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Component
public class SysConfigCache {

    /**
     * 系统参数缓存键前缀（完整键：{@code ateng:solid:config:{tenantId}:{configKey}}）
     */
    public static final String CACHE_KEY_PREFIX = "ateng:solid:config:";

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    private final BoundedDualModeCache<ConfigCacheEntry> cache;

    /**
     * 构造系统参数双模缓存组件。
     *
     * @param stringRedisTemplateProvider 可选 Redis 模板提供器
     * @param systemProperties            系统域治理配置（读取 {@code cache.config-ttl}）
     * @param environmentProvider         Spring 环境提供器
     */
    public SysConfigCache(
            ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider,
            SolidSystemProperties systemProperties,
            ObjectProvider<Environment> environmentProvider
    ) {
        this.cache = new BoundedDualModeCache<>(
                stringRedisTemplateProvider,
                environmentProvider,
                () -> systemProperties != null && systemProperties.getCache() != null
                        ? systemProperties.getCache().getConfigTtl()
                        : DEFAULT_TTL,
                DEFAULT_TTL,
                SysConfigCache::deserializeEntry
        );
    }

    /**
     * 构建标准系统参数缓存键。
     *
     * @param tenantId  租户主键
     * @param configKey 参数键名
     * @return 缓存键字符串
     */
    public static String buildCacheKey(Long tenantId, String configKey) {
        return CACHE_KEY_PREFIX + tenantId + ":" + (configKey != null ? configKey.trim() : "");
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
        String normalizedKey = configKey.trim();
        ConfigCacheEntry entry = cache.getOrLoad(
                buildCacheKey(tenantId, normalizedKey),
                () -> {
                    String loaded = loader.get();
                    return loaded != null ? new ConfigCacheEntry(true, loaded) : new ConfigCacheEntry(false, null);
                }
        );
        return entry != null && entry.isExists() ? entry.getValue() : null;
    }

    /**
     * 主动驱逐指定租户与参数键在本地与 Redis 中的缓存。
     *
     * @param tenantId  租户主键
     * @param configKey 参数键名
     */
    public void evict(Long tenantId, String configKey) {
        if (tenantId == null || !StringUtils.hasText(configKey)) {
            return;
        }
        cache.evict(buildCacheKey(tenantId, configKey.trim()));
    }

    /**
     * 返回本地降级缓存条目数（供单元测试诊断）。
     *
     * @return 本地缓存大小
     */
    public int localSize() {
        return cache.localSize();
    }

    private static ConfigCacheEntry deserializeEntry(String rawJson) {
        ConfigCacheEntry entry = JsonUtil.parseObject(rawJson, ConfigCacheEntry.class);
        if (entry == null) {
            throw new IllegalArgumentException("系统参数缓存 JSON 解析结果为空");
        }
        return entry;
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

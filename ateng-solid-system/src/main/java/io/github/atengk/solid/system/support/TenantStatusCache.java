package io.github.atengk.solid.system.support;

import io.github.atengk.boot.core.concurrent.ConcurrentLruCache;
import io.github.atengk.solid.common.properties.SolidTenantProperties;
import io.github.atengk.solid.system.api.dto.TenantAuthDTO;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongFunction;

/**
 * 租户状态与授权有效期零漂移纯 L2 分布式缓存：委派 {@code RedisCacheTemplate}（领域相对键
 * {@code tenant:status:{tenantId}}，受 {@code ateng.boot.redis.key-prefix} 托管，具备 Redisson 防击穿互斥锁、
 * DCL、负向空哨兵与随机抖动 TTL）；未启用 Redis 时经 {@link AdaptiveRedisSupport} 降级为容量上限 10,000 的本地有界缓存。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Component
public class TenantStatusCache {

    /**
     * 租户状态领域相对缓存键前缀（默认完整物理键：{@code solid:tenant:status:{tenantId}}）
     */
    public static final String CACHE_KEY_PREFIX = "tenant:status:";

    /**
     * 未启用 Redis 时本地降级缓存最大容量上限
     */
    public static final int LOCAL_CACHE_MAX_CAPACITY = AdaptiveRedisSupport.DEFAULT_LOCAL_MAX_CAPACITY;

    private static final Duration DEFAULT_TTL = Duration.ofSeconds(60);
    private static final TenantAuthDTO NOT_FOUND_SENTINEL = new TenantAuthDTO();

    private final AdaptiveRedisSupport adaptiveRedisSupport;
    private final SolidTenantProperties tenantProperties;
    private final ConcurrentLruCache<Long, TenantAuthDTO> localFallback;

    /**
     * 构造租户状态纯 L2 分布式缓存组件。
     *
     * @param adaptiveRedisSupport 可选 Redis 自适应门面
     * @param tenantProperties     多租户治理配置（读取 {@code status-cache-ttl}）
     */
    public TenantStatusCache(AdaptiveRedisSupport adaptiveRedisSupport, SolidTenantProperties tenantProperties) {
        this.adaptiveRedisSupport = Objects.requireNonNull(adaptiveRedisSupport, "adaptiveRedisSupport 不能为空");
        this.tenantProperties = tenantProperties;
        this.localFallback = new ConcurrentLruCache<>(LOCAL_CACHE_MAX_CAPACITY, resolveTtl());
    }

    /**
     * 构建标准租户状态领域相对缓存键。
     *
     * @param tenantId 租户主键
     * @return 领域相对缓存键字符串
     */
    public static String buildCacheKey(Long tenantId) {
        return CACHE_KEY_PREFIX + tenantId;
    }

    /**
     * 按租户主键读取缓存，未命中时执行回源加载并写入缓存（不存在租户同步写入负向哨兵防穿透）。
     *
     * @param tenantId 租户主键
     * @param loader   数据库回源加载函数
     * @return 租户认证载荷；租户不存在时返回 {@code null}
     */
    public TenantAuthDTO getOrLoad(Long tenantId, LongFunction<TenantAuthDTO> loader) {
        if (tenantId == null) {
            return null;
        }
        Objects.requireNonNull(loader, "loader 不能为空");
        return adaptiveRedisSupport.getOrLoadL2(
                buildCacheKey(tenantId),
                TenantAuthDTO.class,
                () -> loader.apply(tenantId),
                resolveTtl(),
                () -> getOrLoadFromLocal(tenantId, loader)
        );
    }

    /**
     * 主动失效指定租户在 Redis 与本地降级缓存中的状态条目。
     *
     * @param tenantId 租户主键
     */
    public void evict(Long tenantId) {
        if (tenantId == null) {
            return;
        }
        adaptiveRedisSupport.evictL2(buildCacheKey(tenantId), () -> localFallback.remove(tenantId));
    }

    /**
     * 返回当前本地降级缓存条数（供测试与诊断观测）。
     *
     * @return 本地缓存大小
     */
    public int localSize() {
        return localFallback.size();
    }

    private TenantAuthDTO getOrLoadFromLocal(Long tenantId, LongFunction<TenantAuthDTO> loader) {
        TenantAuthDTO cached = localFallback.computeIfAbsent(tenantId, id -> {
            TenantAuthDTO loaded = loader.apply(id);
            return isExistingTenant(loaded) ? loaded : NOT_FOUND_SENTINEL;
        });
        return unwrapPayload(cached);
    }

    private Duration resolveTtl() {
        Duration configured = tenantProperties != null ? tenantProperties.getStatusCacheTtl() : null;
        return AdaptiveRedisSupport.resolveEffectiveTtl(configured, DEFAULT_TTL);
    }

    private static boolean isExistingTenant(TenantAuthDTO dto) {
        return dto != null && dto.getTenantId() != null;
    }

    private static TenantAuthDTO unwrapPayload(TenantAuthDTO payload) {
        return isExistingTenant(payload) ? payload : null;
    }
}

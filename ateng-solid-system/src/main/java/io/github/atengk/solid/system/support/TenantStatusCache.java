package io.github.atengk.solid.system.support;

import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.solid.common.properties.SolidTenantProperties;
import io.github.atengk.solid.system.api.dto.TenantAuthDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongFunction;

/**
 * 租户状态与授权有效期零漂移双模缓存：优先使用 Redis 紧凑 JSON 存储（TTL 60s + 负向空对象哨兵），未启用 Redis 时降级为容量上限 10,000 的本地有界缓存。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantStatusCache {

    /**
     * 租户状态缓存键前缀（完整键：{@code ateng:solid:tenant:status:{tenantId}}）
     */
    public static final String CACHE_KEY_PREFIX = "ateng:solid:tenant:status:";

    /**
     * 未启用 Redis 时本地降级缓存最大容量上限
     */
    public static final int LOCAL_CACHE_MAX_CAPACITY = 10_000;

    private static final Duration DEFAULT_TTL = Duration.ofSeconds(60);
    private static final TenantAuthDTO NOT_FOUND_SENTINEL = new TenantAuthDTO();

    private final ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider;
    private final SolidTenantProperties tenantProperties;
    private final ObjectProvider<Environment> environmentProvider;

    private final ConcurrentHashMap<Long, LocalCacheEntry> localCache = new ConcurrentHashMap<>();
    private final LinkedHashSet<Long> localInsertionOrder = new LinkedHashSet<>();
    private final ReentrantLock localWriteLock = new ReentrantLock();

    /**
     * 构建标准租户状态缓存键。
     *
     * @param tenantId 租户主键
     * @return 缓存键字符串
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
        StringRedisTemplate redis = RedisActivationResolver.resolveActive(
                stringRedisTemplateProvider, environmentProvider);
        if (redis != null) {
            return getOrLoadFromRedis(redis, tenantId, loader);
        }
        return getOrLoadFromLocal(tenantId, loader);
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
        localWriteLock.lock();
        try {
            localCache.remove(tenantId);
            localInsertionOrder.remove(tenantId);
        } finally {
            localWriteLock.unlock();
        }
        StringRedisTemplate redis = RedisActivationResolver.resolveActive(
                stringRedisTemplateProvider, environmentProvider);
        if (redis != null) {
            try {
                redis.delete(buildCacheKey(tenantId));
            } catch (RuntimeException ex) {
                log.warn("删除 Redis 租户状态缓存失败: tenantId={}", tenantId, ex);
            }
        }
    }

    /**
     * 返回当前本地降级缓存条数（供测试与诊断观测）。
     *
     * @return 本地缓存大小
     */
    public int localSize() {
        return localCache.size();
    }

    private TenantAuthDTO getOrLoadFromRedis(
            StringRedisTemplate redis,
            Long tenantId,
            LongFunction<TenantAuthDTO> loader
    ) {
        String key = buildCacheKey(tenantId);
        try {
            String rawJson = redis.opsForValue().get(key);
            if (StringUtils.hasText(rawJson)) {
                try {
                    TenantAuthDTO cached = JsonUtil.parseObject(rawJson, TenantAuthDTO.class);
                    if (cached == null || cached.getTenantId() == null) {
                        return null;
                    }
                    return cached;
                } catch (RuntimeException parseEx) {
                    log.warn("解析 Redis 租户状态缓存失败，自动清理脏键并回源: key={}", key, parseEx);
                    redis.delete(key);
                }
            }
            TenantAuthDTO loaded = loader.apply(tenantId);
            TenantAuthDTO payload = isExistingTenant(loaded) ? loaded : NOT_FOUND_SENTINEL;
            redis.opsForValue().set(key, JsonUtil.toJsonString(payload), resolveTtl());
            return isExistingTenant(loaded) ? loaded : null;
        } catch (RuntimeException redisEx) {
            log.warn("访问 Redis 租户状态缓存异常，降级为本地缓存回源: tenantId={}", tenantId, redisEx);
            return getOrLoadFromLocal(tenantId, loader);
        }
    }

    private TenantAuthDTO getOrLoadFromLocal(Long tenantId, LongFunction<TenantAuthDTO> loader) {
        Instant now = Instant.now();
        LocalCacheEntry cachedEntry = localCache.get(tenantId);
        if (cachedEntry != null && !cachedEntry.isExpired(now)) {
            return unwrapPayload(cachedEntry.payload());
        }

        TenantAuthDTO loaded = loader.apply(tenantId);
        TenantAuthDTO payload = isExistingTenant(loaded) ? loaded : NOT_FOUND_SENTINEL;
        putLocalEntry(tenantId, payload, now.plus(resolveTtl()), now);
        return isExistingTenant(loaded) ? loaded : null;
    }

    private void putLocalEntry(Long tenantId, TenantAuthDTO payload, Instant expiresAt, Instant now) {
        localWriteLock.lock();
        try {
            // 优先清理过期条目，仍超限则按近似 LRU/插入序淘汰最旧键，防止本地缓存无限膨胀
            if (localCache.size() >= LOCAL_CACHE_MAX_CAPACITY && !localCache.containsKey(tenantId)) {
                evictExpiredLocalLocked(now);
                while (localCache.size() >= LOCAL_CACHE_MAX_CAPACITY) {
                    if (!evictOldestLocalLocked()) {
                        break;
                    }
                }
            }
            localCache.put(tenantId, new LocalCacheEntry(payload, expiresAt));
            localInsertionOrder.remove(tenantId);
            localInsertionOrder.add(tenantId);
        } finally {
            localWriteLock.unlock();
        }
    }

    private void evictExpiredLocalLocked(Instant now) {
        Iterator<Map.Entry<Long, LocalCacheEntry>> iterator = localCache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, LocalCacheEntry> entry = iterator.next();
            if (entry.getValue().isExpired(now)) {
                iterator.remove();
                localInsertionOrder.remove(entry.getKey());
            }
        }
    }

    private boolean evictOldestLocalLocked() {
        Iterator<Long> orderIterator = localInsertionOrder.iterator();
        if (!orderIterator.hasNext()) {
            return false;
        }
        Long oldestKey = orderIterator.next();
        orderIterator.remove();
        localCache.remove(oldestKey);
        return true;
    }

    private Duration resolveTtl() {
        Duration configured = tenantProperties != null ? tenantProperties.getStatusCacheTtl() : null;
        if (configured == null || configured.isZero() || configured.isNegative()) {
            return DEFAULT_TTL;
        }
        return configured;
    }

    private static boolean isExistingTenant(TenantAuthDTO dto) {
        return dto != null && dto.getTenantId() != null;
    }

    private static TenantAuthDTO unwrapPayload(TenantAuthDTO payload) {
        return isExistingTenant(payload) ? payload : null;
    }

    private record LocalCacheEntry(TenantAuthDTO payload, Instant expiresAt) {
        private boolean isExpired(Instant now) {
            return !expiresAt.isAfter(now);
        }
    }
}

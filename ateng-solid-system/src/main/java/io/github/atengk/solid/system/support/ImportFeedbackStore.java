package io.github.atengk.solid.system.support;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.redis.util.RedisUtil;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.properties.SolidSystemProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * 导入错误批注工作簿暂存器（基于 {@link RedisUtil} 的元数据/字节双键存储 + 本地有界降级，15 分钟 TTL 并坚持先验元数据归属权）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportFeedbackStore {

    /**
     * 批注文件元数据 Redis 相对键前缀（交由 {@link RedisUtil#buildKey(String)} 拼接前缀）
     */
    public static final String META_KEY_PREFIX = "excel:feedback:meta:";

    /**
     * 批注文件字节内容 Redis 相对键前缀（交由 {@link RedisUtil#buildKey(String)} 拼接前缀）
     */
    public static final String BIN_KEY_PREFIX = "excel:feedback:bin:";

    /**
     * 批注文件允许暂存的最大体积（4MB 硬上限）
     */
    public static final int MAX_FEEDBACK_BYTES = 4 * 1024 * 1024;

    /**
     * 无 Redis 场景下本地有界暂存最大条目上限
     */
    public static final int MAX_LOCAL_ENTRIES = 50;

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(15);
    private static final Pattern SAFE_TOKEN_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{16,64}$");

    private final AdaptiveRedisSupport adaptiveRedisSupport;
    private final ObjectProvider<RedisUtil> redisUtilProvider;
    private final SolidSystemProperties systemProperties;

    private final ConcurrentHashMap<String, LocalFeedbackEntry> localStore = new ConcurrentHashMap<>();
    private final LinkedHashSet<String> localOrder = new LinkedHashSet<>();
    private final ReentrantLock localLock = new ReentrantLock();

    /**
     * 暂存错误批注工作簿并返回 32 位 URL 安全访问令牌。
     *
     * @param tenantId       操作所属工作租户主键
     * @param operatorUserId 操作人用户主键
     * @param filename       建议下载文件名（含 {@code .xlsx} 后缀）
     * @param content        批注工作簿字节数组（上限 4MB）
     * @return 32 位反馈令牌
     */
    public String save(Long tenantId, Long operatorUserId, String filename, byte[] content) {
        if (tenantId == null || operatorUserId == null) {
            throw new BusinessException(CommonResultCode.UNAUTHORIZED, "缺失批注暂存归属身份信息");
        }
        if (content == null || content.length == 0) {
            throw new BusinessException(CommonBizResultCode.EXCEL_VALIDATION_FAILED, "错误批注文件生成结果为空");
        }
        if (content.length > MAX_FEEDBACK_BYTES) {
            throw new BusinessException(CommonBizResultCode.EXCEL_VALIDATION_FAILED, "错误批注文件体积超出 4MB 暂存上限");
        }

        String token = IdUtil.fastSimpleUUID();
        String normalizedFilename = StringUtils.hasText(filename) ? filename.trim() : "用户导入错误批注.xlsx";
        Duration ttl = resolveTtl();
        FeedbackMeta meta = new FeedbackMeta(tenantId, operatorUserId, normalizedFilename);

        RedisUtil redisUtil = adaptiveRedisSupport.resolveActive(redisUtilProvider);
        if (redisUtil != null) {
            try {
                // 先写 bin 再写 meta，避免并发读命中 meta 时 bin 尚未落盘
                String encodedBin = Base64.getEncoder().encodeToString(content);
                RedisUtil.set(RedisUtil.buildKey(BIN_KEY_PREFIX + token), encodedBin, ttl);
                RedisUtil.set(RedisUtil.buildKey(META_KEY_PREFIX + token), meta, ttl);
                return token;
            } catch (RuntimeException ex) {
                log.warn("写入 Redis 批注暂存失败，自动降级为本地有界暂存: token={}", token, ex);
            }
        }

        saveLocal(token, meta, content, Instant.now().plus(ttl));
        return token;
    }

    /**
     * 按令牌加载批注工作簿并先通过元数据校验当前租户与操作人归属，再按需读取二进制工作簿内容。
     *
     * @param token             32 位批注访问令牌
     * @param effectiveTenantId 当前生效工作租户主键
     * @param currentUserId     当前登录用户主键
     * @return 归属校验通过的批注文件载体
     * @throws BusinessException 不存在或过期抛出 {@code 404}；归属不匹配抛出 {@code 403}
     */
    public FeedbackFile load(String token, Long effectiveTenantId, Long currentUserId) {
        if (!StringUtils.hasText(token) || !SAFE_TOKEN_PATTERN.matcher(token.trim()).matches()) {
            throw new BusinessException(CommonResultCode.NOT_FOUND, "批注文件已过期或不存在，请重新执行导入");
        }
        String normalizedToken = token.trim();

        RedisUtil redisUtil = adaptiveRedisSupport.resolveActive(redisUtilProvider);
        if (redisUtil != null) {
            FeedbackMeta redisMeta = readMetaFromRedis(normalizedToken);
            if (redisMeta != null) {
                // 先核验 meta 归属权，越权或跨租户请求直接 403，绝不触发 4MB bin 大对象读取
                assertOwnership(redisMeta, effectiveTenantId, currentUserId);
                byte[] content = readBinFromRedis(normalizedToken);
                if (content == null || content.length == 0) {
                    throw new BusinessException(CommonResultCode.NOT_FOUND, "批注文件已过期或不存在，请重新执行导入");
                }
                return new FeedbackFile(redisMeta.getFilename(), content);
            }
        }

        // Redis 未启用、查空或瞬态异常降级写入本地后，回查本地有界暂存（同样先验 meta 再拷贝字节）
        LocalFeedbackEntry local = loadLocalEntry(normalizedToken);
        if (local == null || local.meta() == null || local.content() == null || local.content().length == 0) {
            throw new BusinessException(CommonResultCode.NOT_FOUND, "批注文件已过期或不存在，请重新执行导入");
        }
        assertOwnership(local.meta(), effectiveTenantId, currentUserId);
        return new FeedbackFile(local.meta().getFilename(), local.content().clone());
    }

    /**
     * 返回当前本地降级暂存条目数（供单元测试诊断，触发惰性过期清理）。
     *
     * @return 本地未过期条目数
     */
    public int localSize() {
        localLock.lock();
        try {
            evictExpiredLocked(Instant.now());
            return localStore.size();
        } finally {
            localLock.unlock();
        }
    }

    private FeedbackMeta readMetaFromRedis(String token) {
        try {
            return RedisUtil.get(RedisUtil.buildKey(META_KEY_PREFIX + token), FeedbackMeta.class);
        } catch (RuntimeException ex) {
            log.warn("读取 Redis 批注元数据异常，尝试本地降级暂存回查: token={}", token, ex);
            return null;
        }
    }

    private byte[] readBinFromRedis(String token) {
        try {
            String encodedBin = RedisUtil.get(RedisUtil.buildKey(BIN_KEY_PREFIX + token), String.class);
            if (!StringUtils.hasText(encodedBin)) {
                return null;
            }
            return Base64.getDecoder().decode(encodedBin);
        } catch (RuntimeException ex) {
            log.warn("读取或解码 Redis 批注字节内容异常: token={}", token, ex);
            return null;
        }
    }

    private static void assertOwnership(FeedbackMeta meta, Long effectiveTenantId, Long currentUserId) {
        if (!Objects.equals(meta.getTenantId(), effectiveTenantId)
                || !Objects.equals(meta.getOperatorUserId(), currentUserId)) {
            throw new BusinessException(CommonResultCode.FORBIDDEN, "无权下载他人或其他租户的导入批注文件");
        }
    }

    private void saveLocal(String token, FeedbackMeta meta, byte[] content, Instant expiresAt) {
        Instant now = Instant.now();
        byte[] copy = content.clone();
        localLock.lock();
        try {
            evictExpiredLocked(now);
            while (localStore.size() >= MAX_LOCAL_ENTRIES) {
                if (!evictOldestLocked()) {
                    break;
                }
            }
            localStore.put(token, new LocalFeedbackEntry(meta, copy, expiresAt));
            localOrder.remove(token);
            localOrder.add(token);
        } finally {
            localLock.unlock();
        }
    }

    private LocalFeedbackEntry loadLocalEntry(String token) {
        Instant now = Instant.now();
        localLock.lock();
        try {
            LocalFeedbackEntry entry = localStore.get(token);
            if (entry == null) {
                return null;
            }
            if (entry.isExpired(now)) {
                localStore.remove(token);
                localOrder.remove(token);
                return null;
            }
            localOrder.remove(token);
            localOrder.add(token);
            return entry;
        } finally {
            localLock.unlock();
        }
    }

    private void evictExpiredLocked(Instant now) {
        Iterator<Map.Entry<String, LocalFeedbackEntry>> iterator = localStore.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, LocalFeedbackEntry> entry = iterator.next();
            if (entry.getValue().isExpired(now)) {
                iterator.remove();
                localOrder.remove(entry.getKey());
            }
        }
    }

    private boolean evictOldestLocked() {
        Iterator<String> iterator = localOrder.iterator();
        if (!iterator.hasNext()) {
            return false;
        }
        String oldestToken = iterator.next();
        iterator.remove();
        localStore.remove(oldestToken);
        return true;
    }

    private Duration resolveTtl() {
        Duration configured = systemProperties != null && systemProperties.getUser() != null
                ? systemProperties.getUser().getImportFeedbackTtl()
                : null;
        return AdaptiveRedisSupport.resolveEffectiveTtl(configured, DEFAULT_TTL);
    }

    /**
     * 已校验归属的批注文件下载载荷。
     *
     * @param filename 下载文件名
     * @param content  工作簿字节数组
     */
    public record FeedbackFile(String filename, byte[] content) {
    }

    private record LocalFeedbackEntry(FeedbackMeta meta, byte[] content, Instant expiresAt) {
        private boolean isExpired(Instant now) {
            return !expiresAt.isAfter(now);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class FeedbackMeta {
        private Long tenantId;
        private Long operatorUserId;
        private String filename;
    }
}

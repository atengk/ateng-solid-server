package io.github.atengk.solid.system.support;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.properties.SolidSystemProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * 导入错误批注工作簿暂存器（Redis 双 Key + 本地有界降级，15 分钟 TTL 并强制校验租户与操作人归属）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportFeedbackStore {

    /**
     * 批注文件元数据 Redis 键前缀（完整键：{@code ateng:solid:excel:feedback:meta:{token}}）
     */
    public static final String META_KEY_PREFIX = "ateng:solid:excel:feedback:meta:";

    /**
     * 批注文件字节内容 Redis 键前缀（完整键：{@code ateng:solid:excel:feedback:bin:{token}}）
     */
    public static final String BIN_KEY_PREFIX = "ateng:solid:excel:feedback:bin:";

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

    private final ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider;
    private final SolidSystemProperties systemProperties;
    private final ObjectProvider<Environment> environmentProvider;

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

        StringRedisTemplate redis = RedisActivationResolver.resolveActive(
                stringRedisTemplateProvider, environmentProvider
        );
        if (redis != null) {
            try {
                // 先写 bin 再写 meta，避免并发读命中 meta 时 bin 尚未落盘
                String encodedBin = Base64.getEncoder().encodeToString(content);
                redis.opsForValue().set(BIN_KEY_PREFIX + token, encodedBin, ttl);
                redis.opsForValue().set(META_KEY_PREFIX + token, JsonUtil.toJsonString(meta), ttl);
                return token;
            } catch (RuntimeException ex) {
                log.warn("写入 Redis 批注暂存失败，自动降级为本地有界暂存: token={}", token, ex);
            }
        }

        saveLocal(token, meta, content, Instant.now().plus(ttl));
        return token;
    }

    /**
     * 按令牌加载批注工作簿并校验当前租户与操作人归属。
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

        StoredFeedback stored = loadFromRedisOrLocal(normalizedToken);
        if (stored == null || stored.meta() == null || stored.content() == null || stored.content().length == 0) {
            throw new BusinessException(CommonResultCode.NOT_FOUND, "批注文件已过期或不存在，请重新执行导入");
        }
        if (!Objects.equals(stored.meta().getTenantId(), effectiveTenantId)
                || !Objects.equals(stored.meta().getOperatorUserId(), currentUserId)) {
            throw new BusinessException(CommonResultCode.FORBIDDEN, "无权下载他人或其他租户的导入批注文件");
        }
        return new FeedbackFile(stored.meta().getFilename(), stored.content());
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

    private StoredFeedback loadFromRedisOrLocal(String token) {
        StringRedisTemplate redis = RedisActivationResolver.resolveActive(
                stringRedisTemplateProvider, environmentProvider
        );
        if (redis != null) {
            try {
                String metaJson = redis.opsForValue().get(META_KEY_PREFIX + token);
                String encodedBin = redis.opsForValue().get(BIN_KEY_PREFIX + token);
                if (StringUtils.hasText(metaJson) && StringUtils.hasText(encodedBin)) {
                    FeedbackMeta meta = JsonUtil.parseObject(metaJson, FeedbackMeta.class);
                    byte[] content = Base64.getDecoder().decode(encodedBin);
                    return new StoredFeedback(meta, content);
                }
            } catch (RuntimeException ex) {
                log.warn("读取 Redis 批注暂存异常，尝试本地降级暂存回查: token={}", token, ex);
            }
        }
        // Redis 瞬态异常降级写入本地后若 Redis 恢复查空，仍须回查本地有界暂存避免误报 404
        return loadLocal(token);
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

    private StoredFeedback loadLocal(String token) {
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
            return new StoredFeedback(entry.meta(), entry.content().clone());
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
        if (configured == null || configured.isZero() || configured.isNegative()) {
            return DEFAULT_TTL;
        }
        return configured;
    }

    /**
     * 已校验归属的批注文件下载载荷。
     *
     * @param filename 下载文件名
     * @param content  工作簿字节数组
     */
    public record FeedbackFile(String filename, byte[] content) {
    }

    private record StoredFeedback(FeedbackMeta meta, byte[] content) {
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

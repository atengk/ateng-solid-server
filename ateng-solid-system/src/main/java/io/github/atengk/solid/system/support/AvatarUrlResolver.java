package io.github.atengk.solid.system.support;

import io.github.atengk.boot.storage.model.PresignedDownloadRequest;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.template.StorageOperations;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 用户头像访问地址动态签发器：透传空值与外链，对象存储未启用或签发异常时优雅降级返回原始对象键，否则签发 2 小时 inline 预签名地址。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AvatarUrlResolver {

    /**
     * 头像临时预签名地址默认有效期（2 小时）
     */
    public static final Duration PRESIGNED_URL_TTL = Duration.ofHours(2);

    private static final String INLINE_DISPOSITION = "inline";
    private static final String HTTP_PREFIX = "http://";
    private static final String HTTPS_PREFIX = "https://";

    private final ObjectProvider<StorageOperations> storageOperationsProvider;

    /**
     * 判断给定路径是否为 {@code http://} 或 {@code https://} 外部直链。
     *
     * @param path 头像路径或对象键
     * @return 为外链时返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isExternalHttpUrl(String path) {
        if (!StringUtils.hasText(path)) {
            return false;
        }
        String trimmed = path.trim();
        return trimmed.regionMatches(true, 0, HTTP_PREFIX, 0, HTTP_PREFIX.length())
                || trimmed.regionMatches(true, 0, HTTPS_PREFIX, 0, HTTPS_PREFIX.length());
    }

    /**
     * 将持久化的 {@code sys_user.avatar} 字段解析为前端可直接渲染的访问地址。
     *
     * @param avatarKey 数据库保存的相对对象键、外链或空值
     * @return 可访问预览地址或降级对象键；输入为空或纯空白时返回 {@code null}
     */
    public String resolve(String avatarKey) {
        if (!StringUtils.hasText(avatarKey)) {
            return null;
        }
        String normalized = avatarKey.trim();
        if (isExternalHttpUrl(normalized)) {
            return normalized;
        }
        StorageOperations storage = storageOperationsProvider.getIfAvailable();
        if (storage == null) {
            return normalized;
        }
        try {
            PresignedUrl presignedUrl = storage.getPresignedUrl(
                    PresignedDownloadRequest.builder()
                            .path(normalized)
                            .duration(PRESIGNED_URL_TTL)
                            .responseContentDisposition(INLINE_DISPOSITION)
                            .build()
            );
            if (presignedUrl != null && StringUtils.hasText(presignedUrl.getUrl())) {
                return presignedUrl.getUrl();
            }
            return normalized;
        } catch (RuntimeException ex) {
            log.warn("签发头像预签名地址失败，降级返回对象键: key={}", normalized, ex);
            return normalized;
        }
    }
}

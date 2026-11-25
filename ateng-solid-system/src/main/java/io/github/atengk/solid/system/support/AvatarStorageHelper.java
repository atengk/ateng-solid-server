package io.github.atengk.solid.system.support;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.guard.StorageGuard;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.model.StorageUploadRequest;
import io.github.atengk.boot.storage.properties.StorageGuardProperties;
import io.github.atengk.boot.storage.template.StorageOperations;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.command.UploadAvatarCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 用户头像上传守卫、事务回滚保护落盘与旧头像提交后异步清理协作者。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Component
@RequiredArgsConstructor
public class AvatarStorageHelper {

    private static final Set<String> AVATAR_ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private static final DataSize DEFAULT_AVATAR_MAX_SIZE = DataSize.ofMegabytes(2);
    private static final StorageGuard FALLBACK_AVATAR_GUARD = createDefaultAvatarGuard();

    private final ObjectProvider<StorageOperations> storageOperationsProvider;
    private final ObjectProvider<StorageGuard> storageGuardProvider;
    private final AvatarUrlResolver avatarUrlResolver;

    /**
     * 前置执行扩展名白名单、文件大小与二进制魔数守卫校验，并确保对象存储可用。
     *
     * @param cmd 头像上传指令
     * @return 已验证的扩展名与 MIME 类型元信息
     */
    public ValidatedAvatar validateUpload(UploadAvatarCommand cmd) {
        if (cmd == null || cmd.getContent() == null || cmd.getContent().length == 0) {
            throw new BusinessException(CommonBizResultCode.FILE_GUARD_REJECTED, "头像文件不能为空");
        }
        String extension = extractSafeExtension(cmd.getOriginalFilename());
        StorageGuard guard = resolveGuard();
        try {
            guard.validateFileSize(cmd.getContent().length);
            StorageGuard.GuardResult result = guard.inspect(
                    "avatar." + extension,
                    new ByteArrayInputStream(cmd.getContent()),
                    cmd.getContentType()
            );
            String mimeType = StringUtils.hasText(result.getDetectedMimeType())
                    ? result.getDetectedMimeType()
                    : cmd.getContentType();
            return new ValidatedAvatar(extension, StringUtils.hasText(mimeType) ? mimeType : "image/" + extension);
        } catch (StorageException ex) {
            throw new BusinessException(CommonBizResultCode.FILE_GUARD_REJECTED, ex.getMsg(), ex);
        }
    }

    /**
     * 获取当前启用的对象存储模板；未启用时明确拒绝上传。
     *
     * @return 可用的 {@link StorageOperations} 实例
     */
    public StorageOperations requireStorageOperations() {
        StorageOperations storage = storageOperationsProvider.getIfAvailable();
        if (storage == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "对象存储服务未启用，无法上传头像");
        }
        return storage;
    }

    /**
     * 在当前 Spring 事务内上传新头像并绑定事务回滚删除补偿，返回最终持久化的相对对象键。
     *
     * @param storage   对象存储操作模板
     * @param tenantId  租户主键
     * @param userId    用户主键
     * @param cmd       原始上传指令
     * @param validated 前置校验元信息
     * @return 写入成功后的相对对象键
     */
    public String uploadWithRollbackCompensation(
            StorageOperations storage,
            Long tenantId,
            Long userId,
            UploadAvatarCommand cmd,
            ValidatedAvatar validated
    ) {
        String objectPath = String.format(
                "avatar/%d/%d/%s.%s",
                tenantId,
                userId,
                UUID.randomUUID().toString().replace("-", ""),
                validated.extension()
        );
        try {
            StorageObject uploaded = storage.uploadWithRollbackDelete(
                    StorageUploadRequest.builder()
                            .path(objectPath)
                            .inputStream(new ByteArrayInputStream(cmd.getContent()))
                            .contentType(validated.mimeType())
                            .size(cmd.getContent().length)
                            .publicRead(false)
                            .overwrite(false)
                            .build()
            );
            return uploaded != null && StringUtils.hasText(uploaded.getPath())
                    ? uploaded.getPath().trim()
                    : objectPath;
        } catch (StorageException ex) {
            if (isFileGuardError(ex)) {
                throw new BusinessException(CommonBizResultCode.FILE_GUARD_REJECTED, ex.getMsg(), ex);
            }
            throw ex;
        }
    }

    /**
     * 注册数据库事务提交后的旧头像对象键安全删减任务（忽略空值与外部直链）。
     *
     * @param storage       对象存储操作模板
     * @param oldAvatarKey  用户旧头像字段值
     * @param newObjectPath 本次新上传的对象键
     */
    public void scheduleDeleteOldAvatarAfterCommit(StorageOperations storage, String oldAvatarKey, String newObjectPath) {
        if (StringUtils.hasText(oldAvatarKey)
                && !AvatarUrlResolver.isExternalHttpUrl(oldAvatarKey)
                && !Objects.equals(oldAvatarKey.trim(), newObjectPath)) {
            storage.deleteAfterCommit(oldAvatarKey.trim());
        }
    }

    /**
     * 解析头像可访问地址。
     *
     * @param objectPath 相对对象键或外链
     * @return 动态签发 URL 或降级键
     */
    public String resolvePreviewUrl(String objectPath) {
        return avatarUrlResolver.resolve(objectPath);
    }

    private StorageGuard resolveGuard() {
        StorageGuard configured = storageGuardProvider != null ? storageGuardProvider.getIfAvailable() : null;
        return configured != null && configured.isEnabled() ? configured : FALLBACK_AVATAR_GUARD;
    }

    private static String extractSafeExtension(String originalFilename) {
        if (!StringUtils.hasText(originalFilename)) {
            throw new BusinessException(CommonBizResultCode.FILE_GUARD_REJECTED, "头像文件名不能为空");
        }
        String cleanName = originalFilename.trim();
        int slashIdx = Math.max(cleanName.lastIndexOf('/'), cleanName.lastIndexOf('\\'));
        if (slashIdx >= 0) {
            cleanName = cleanName.substring(slashIdx + 1);
        }
        int dotIdx = cleanName.lastIndexOf('.');
        if (dotIdx <= 0 || dotIdx == cleanName.length() - 1) {
            throw new BusinessException(CommonBizResultCode.FILE_GUARD_REJECTED, "头像文件必须包含合法图片扩展名");
        }
        String ext = cleanName.substring(dotIdx + 1).toLowerCase(Locale.ROOT);
        if (!AVATAR_ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BusinessException(CommonBizResultCode.FILE_GUARD_REJECTED, "头像仅支持 jpg、jpeg、png、webp 格式");
        }
        return ext;
    }

    private static boolean isFileGuardError(StorageException ex) {
        return ex.getResultCode() == StorageResultCode.STORAGE_FILE_NOT_ALLOWED
                || ex.getResultCode() == StorageResultCode.STORAGE_FILE_TYPE_MISMATCH
                || ex.getResultCode() == StorageResultCode.STORAGE_SIZE_EXCEEDED
                || ex.getResultCode() == StorageResultCode.INVALID_PATH;
    }

    private static StorageGuard createDefaultAvatarGuard() {
        StorageGuardProperties props = new StorageGuardProperties();
        props.setEnabled(true);
        props.setStrictMagicCheck(true);
        props.setAllowedExtensions(AVATAR_ALLOWED_EXTENSIONS);
        props.setMaxFileSize(DEFAULT_AVATAR_MAX_SIZE);
        return new StorageGuard(props);
    }

    /**
     * 经守卫校验后的头像元信息载体。
     *
     * @param extension 规范化小写扩展名
     * @param mimeType  已识别或声明的 MIME 类型
     */
    public record ValidatedAvatar(String extension, String mimeType) {
    }
}

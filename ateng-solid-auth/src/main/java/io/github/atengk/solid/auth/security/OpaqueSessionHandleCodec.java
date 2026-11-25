package io.github.atengk.solid.auth.security;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.solid.auth.properties.SolidAuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 在线会话不透明句柄无状态签密编解码器：基于 AES-256-GCM 与 {@code loginType:tenantId} AAD 绑定保护原始令牌，防止令牌明文泄漏与跨租户重放。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Slf4j
@Component
public class OpaqueSessionHandleCodec {

    private static final int AES_256_KEY_BYTES = 32;
    private static final int GCM_TAG_BYTES = CryptoUtil.GCM_TAG_LENGTH / Byte.SIZE;
    private static final int MIN_DECODED_BYTES = CryptoUtil.DEFAULT_GCM_IV_SIZE + GCM_TAG_BYTES + 1;
    private static final int FULL_MASK_THRESHOLD = 10;
    private static final int SHORT_MASK_MIN_LENGTH = 4;
    private static final String MASK_PLACEHOLDER = "****";
    private static final String INVALID_HANDLE_MESSAGE = "无效或已失效的会话标识";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final byte[] aesKey;

    /**
     * 构造会话句柄编解码器；当 {@code sessionHandleSecret} 留空时自动生成进程内随机 32 字节密钥并输出告警日志。
     *
     * @param authProperties 管理端认证配置属性
     */
    public OpaqueSessionHandleCodec(SolidAuthProperties authProperties) {
        String configuredSecret = authProperties != null ? authProperties.getSessionHandleSecret() : null;
        if (!StringUtils.hasText(configuredSecret)) {
            byte[] ephemeralKey = new byte[AES_256_KEY_BYTES];
            SECURE_RANDOM.nextBytes(ephemeralKey);
            this.aesKey = ephemeralKey;
            log.warn("未配置 ateng.solid.auth.session-handle-secret，已自动生成进程内随机 32 字节临时密钥；多实例集群部署必须配置统一 session-handle-secret");
        } else {
            this.aesKey = deriveAes256Key(configuredSecret.trim());
        }
    }

    /**
     * 将原始访问令牌签密为绑定账号体系与生效租户的无状态 Base64URL 不透明会话句柄。
     *
     * @param loginType  账号登录体系标识（如 {@code admin}）
     * @param tenantId   当前生效租户主键字符串
     * @param tokenValue 原始 Sa-Token 访问令牌
     * @return 无填充 Base64URL 编码的不透明会话句柄
     */
    public String encode(String loginType, String tenantId, String tokenValue) {
        if (!StringUtils.hasText(loginType) || !StringUtils.hasText(tokenValue)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, INVALID_HANDLE_MESSAGE);
        }
        byte[] iv = CryptoUtil.generateGcmIv();
        byte[] plainBytes = tokenValue.getBytes(StandardCharsets.UTF_8);
        byte[] aad = buildAad(loginType, tenantId);
        byte[] cipherAndTag = CryptoUtil.encryptGcm(plainBytes, aesKey, iv, aad);

        byte[] combined = new byte[iv.length + cipherAndTag.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(cipherAndTag, 0, combined, iv.length, cipherAndTag.length);
        return URL_ENCODER.encodeToString(combined);
    }

    /**
     * 校验并还原不透明会话句柄中的原始访问令牌；格式损坏、密文被篡改或跨租户/跨登录体系 AAD 不匹配时统一拒绝。
     *
     * @param loginType 账号登录体系标识（如 {@code admin}）
     * @param tenantId  当前生效租户主键字符串
     * @param sessionId 不透明会话句柄
     * @return 还原后的原始访问令牌
     * @throws BusinessException 句柄为空、编码非法、解密失败或 AAD 校验失败时抛出 {@code 400}
     */
    public String decode(String loginType, String tenantId, String sessionId) {
        if (!StringUtils.hasText(loginType) || !StringUtils.hasText(sessionId)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, INVALID_HANDLE_MESSAGE);
        }
        try {
            byte[] combined = URL_DECODER.decode(sessionId.trim());
            if (combined.length < MIN_DECODED_BYTES) {
                throw new BusinessException(CommonResultCode.BAD_REQUEST, INVALID_HANDLE_MESSAGE);
            }
            byte[] iv = Arrays.copyOfRange(combined, 0, CryptoUtil.DEFAULT_GCM_IV_SIZE);
            byte[] cipherAndTag = Arrays.copyOfRange(combined, CryptoUtil.DEFAULT_GCM_IV_SIZE, combined.length);
            byte[] aad = buildAad(loginType, tenantId);
            byte[] plainBytes = CryptoUtil.decryptGcm(cipherAndTag, aesKey, iv, aad);
            String tokenValue = new String(plainBytes, StandardCharsets.UTF_8);
            if (!StringUtils.hasText(tokenValue)) {
                throw new BusinessException(CommonResultCode.BAD_REQUEST, INVALID_HANDLE_MESSAGE);
            }
            return tokenValue;
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, INVALID_HANDLE_MESSAGE);
        }
    }

    /**
     * 对原始令牌执行边界安全的展示脱敏：超过 10 位保留前 6 后 4，5~10 位保留前 2 后 2，更短则全掩码。
     *
     * @param tokenValue 原始令牌字符串
     * @return 脱敏展示串；输入为空白时返回空串
     */
    public String maskToken(String tokenValue) {
        if (!StringUtils.hasText(tokenValue)) {
            return "";
        }
        String trimmed = tokenValue.trim();
        int len = trimmed.length();
        if (len <= SHORT_MASK_MIN_LENGTH) {
            return MASK_PLACEHOLDER;
        }
        if (len <= FULL_MASK_THRESHOLD) {
            return trimmed.substring(0, 2) + MASK_PLACEHOLDER + trimmed.substring(len - 2);
        }
        return trimmed.substring(0, 6) + MASK_PLACEHOLDER + trimmed.substring(len - 4);
    }

    private static byte[] buildAad(String loginType, String tenantId) {
        String normalizedLoginType = loginType.trim();
        String normalizedTenantId = StringUtils.hasText(tenantId) ? tenantId.trim() : "";
        return (normalizedLoginType + ":" + normalizedTenantId).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] deriveAes256Key(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256 摘要算法", ex);
        }
    }
}

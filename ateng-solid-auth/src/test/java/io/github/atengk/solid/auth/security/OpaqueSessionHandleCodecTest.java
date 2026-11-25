package io.github.atengk.solid.auth.security;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.solid.auth.properties.SolidAuthProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OpaqueSessionHandleCodec} 不透明会话句柄签密编解码器 Layer 1 单元测试。
 *
 * @author Ateng
 * @since 2026-11-25
 */
class OpaqueSessionHandleCodecTest {

    private static OpaqueSessionHandleCodec createCodec(String secret) {
        SolidAuthProperties properties = new SolidAuthProperties();
        properties.setSessionHandleSecret(secret);
        return new OpaqueSessionHandleCodec(properties);
    }

    @Test
    @DisplayName("应当支持 AES-256-GCM 编解码闭环并输出无填充 Base64URL 句柄")
    void shouldEncodeAndDecodeTokenRoundTrip() {
        OpaqueSessionHandleCodec codec = createCodec("ateng-solid-unit-test-secret-key-2026");
        String rawToken = "satoken-admin-9a7f3c1e2b4d5a6f8c0e1d2a3b4c5d6e";

        String handleA = codec.encode("admin", "1001", rawToken);
        String handleB = codec.encode("admin", "1001", rawToken);

        assertThat(handleA)
                .isNotBlank()
                .matches("^[A-Za-z0-9_-]+$")
                .isNotEqualTo(rawToken)
                .isNotEqualTo(handleB);
        assertThat(codec.decode("admin", "1001", handleA)).isEqualTo(rawToken);
        assertThat(codec.decode("admin", "1001", handleB)).isEqualTo(rawToken);
    }

    @Test
    @DisplayName("篡改密文、非法 Base64URL 或跨租户/跨登录体系 AAD 应当统一抛出 400")
    void shouldRejectTamperedInvalidOrCrossTenantHandleWithBadRequest() {
        OpaqueSessionHandleCodec codec = createCodec("ateng-solid-unit-test-secret-key-2026");
        String rawToken = "satoken-admin-sample-token-123456";
        String handle = codec.encode("admin", "1001", rawToken);

        // 1. 篡改任意非尾部填充比特位破坏 IV/密文/GCM Tag
        int targetIndex = handle.length() / 2;
        char targetChar = handle.charAt(targetIndex);
        char flipped = targetChar == 'A' ? 'B' : 'A';
        String tampered = handle.substring(0, targetIndex) + flipped + handle.substring(targetIndex + 1);
        assertBadRequest(() -> codec.decode("admin", "1001", tampered));

        // 2. 跨租户 AAD 不匹配
        assertBadRequest(() -> codec.decode("admin", "1002", handle));

        // 3. 跨账号体系 AAD 不匹配
        assertBadRequest(() -> codec.decode("portal", "1001", handle));

        // 4. 非法 Base64URL、空白与过短载荷
        assertBadRequest(() -> codec.decode("admin", "1001", null));
        assertBadRequest(() -> codec.decode("admin", "1001", "   "));
        assertBadRequest(() -> codec.decode("admin", "1001", "not+valid/base64="));
        assertBadRequest(() -> codec.decode("admin", "1001", "AAAA"));
    }

    @Test
    @DisplayName("配置密钥留空时应当生成互不共享的进程内随机密钥（严禁硬编码默认密钥）")
    void shouldGenerateRandomSecretPerInstanceWhenConfigIsBlank() {
        OpaqueSessionHandleCodec instanceA = createCodec("");
        OpaqueSessionHandleCodec instanceB = createCodec("   ");

        String rawToken = "satoken-admin-ephemeral-token-987654";
        String handleFromA = instanceA.encode("admin", "0", rawToken);

        assertThat(instanceA.decode("admin", "0", handleFromA)).isEqualTo(rawToken);
        assertBadRequest(() -> instanceB.decode("admin", "0", handleFromA));
    }

    @Test
    @DisplayName("maskToken 应当对不同长度令牌执行边界安全掩码")
    void shouldMaskTokensAcrossLengthBoundaries() {
        OpaqueSessionHandleCodec codec = createCodec("ateng-solid-unit-test-secret-key-2026");

        assertThat(codec.maskToken(null)).isEmpty();
        assertThat(codec.maskToken("   ")).isEmpty();
        assertThat(codec.maskToken("abc")).isEqualTo("****");
        assertThat(codec.maskToken("1234")).isEqualTo("****");
        assertThat(codec.maskToken("12345")).isEqualTo("12****45");
        assertThat(codec.maskToken("1234567890")).isEqualTo("12****90");
        assertThat(codec.maskToken("f9a2c10011227e3b")).isEqualTo("f9a2c1****7e3b");
    }

    private static void assertBadRequest(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(CommonResultCode.BAD_REQUEST.getCode());
    }
}

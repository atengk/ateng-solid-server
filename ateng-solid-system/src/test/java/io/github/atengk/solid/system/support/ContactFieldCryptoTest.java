package io.github.atengk.solid.system.support;

import io.github.atengk.boot.mybatisplus.handler.EncryptTypeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ContactFieldCrypto} Layer 1 纯单元测试（验证联系字段归一化、确定性加密一致性及批量去重过滤）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
class ContactFieldCryptoTest {

    private String previousSecretKey;

    @BeforeEach
    void setUp() {
        previousSecretKey = EncryptTypeHandler.getGlobalSecretKey();
        EncryptTypeHandler.setGlobalSecretKey("ateng-solid-test-deterministic-key-32b");
    }

    @AfterEach
    void tearDown() {
        EncryptTypeHandler.setGlobalSecretKey(previousSecretKey);
    }

    @Test
    @DisplayName("null 与纯空白手机号/邮箱应当统一归一为 null")
    void shouldNormalizeBlankToNull() {
        assertThat(ContactFieldCrypto.normalizePhone(null)).isNull();
        assertThat(ContactFieldCrypto.normalizePhone("")).isNull();
        assertThat(ContactFieldCrypto.normalizePhone("   \t\n ")).isNull();
        assertThat(ContactFieldCrypto.normalizePhone(" 13800138000 ")).isEqualTo("13800138000");

        assertThat(ContactFieldCrypto.normalizeEmail(null)).isNull();
        assertThat(ContactFieldCrypto.normalizeEmail("")).isNull();
        assertThat(ContactFieldCrypto.normalizeEmail("  \r\n ")).isNull();
        assertThat(ContactFieldCrypto.normalizeEmail("  Alice.Admin@Example.COM ")).isEqualTo("alice.admin@example.com");

        assertThat(ContactFieldCrypto.encryptPhoneForQuery("   ")).isNull();
        assertThat(ContactFieldCrypto.encryptEmailForQuery("   ")).isNull();
    }

    @Test
    @DisplayName("邮箱经大小写及前后空白归一化后应当产出完全一致的 v1: 确定性密文")
    void shouldProduceIdenticalDeterministicCipherForNormalizedEmail() {
        String lowerCipher = ContactFieldCrypto.encryptEmailForQuery("alice@example.com");
        String upperCipher = ContactFieldCrypto.encryptEmailForQuery("  ALICE@Example.COM ");

        assertThat(lowerCipher)
                .isNotNull()
                .startsWith("v1:")
                .isEqualTo(upperCipher);
        assertThat(EncryptTypeHandler.decrypt(lowerCipher)).isEqualTo("alice@example.com");

        String phoneCipher1 = ContactFieldCrypto.encryptPhoneForQuery("13800138000");
        String phoneCipher2 = ContactFieldCrypto.encryptPhoneForQuery(" 13800138000 ");
        assertThat(phoneCipher1)
                .isNotNull()
                .startsWith("v1:")
                .isEqualTo(phoneCipher2);
        assertThat(EncryptTypeHandler.decrypt(phoneCipher1)).isEqualTo("13800138000");
    }

    @Test
    @DisplayName("批量确定性加密应当自动过滤 null 与空白、按归一化键去重并保持空入参返回空集合")
    void shouldDeduplicateAndFilterBlanksInBatchEncryption() {
        assertThat(ContactFieldCrypto.encryptPhonesForQuery(null)).isEmpty();
        assertThat(ContactFieldCrypto.encryptPhonesForQuery(Collections.emptyList())).isEmpty();
        assertThat(ContactFieldCrypto.encryptEmailsForQuery(null)).isEmpty();
        assertThat(ContactFieldCrypto.encryptEmailsForQuery(Collections.emptyList())).isEmpty();

        List<String> phoneCiphers = ContactFieldCrypto.encryptPhonesForQuery(
                Arrays.asList(null, "", "   ", "13800138000", " 13800138000 ", "13900139000")
        );
        assertThat(phoneCiphers).containsExactly(
                ContactFieldCrypto.encryptPhoneForQuery("13800138000"),
                ContactFieldCrypto.encryptPhoneForQuery("13900139000")
        );

        List<String> emailCiphers = ContactFieldCrypto.encryptEmailsForQuery(
                Arrays.asList(null, " ", "Alice@Example.com", "alice@example.com", "  BOB@example.com ")
        );
        assertThat(emailCiphers).containsExactly(
                ContactFieldCrypto.encryptEmailForQuery("alice@example.com"),
                ContactFieldCrypto.encryptEmailForQuery("bob@example.com")
        );
    }
}

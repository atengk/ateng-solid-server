package io.github.atengk.solid.common.validator;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PasswordPolicyValidator} Layer 1 纯单元测试。
 *
 * @author Ateng
 * @since 2026-11-25
 */
class PasswordPolicyValidatorTest {

    @Test
    @DisplayName("null 与空串应当被判定为不合规并抛出 100001 异常")
    void shouldRejectNullAndEmpty() {
        assertThat(PasswordPolicyValidator.isValid(null)).isFalse();
        assertThat(PasswordPolicyValidator.isValid("")).isFalse();

        assertThatThrownBy(() -> PasswordPolicyValidator.validate(null))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(CommonBizResultCode.PARAM_INVALID.getCode());

        assertThatThrownBy(() -> PasswordPolicyValidator.validate(""))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(CommonBizResultCode.PARAM_INVALID.getCode());
    }

    @Test
    @DisplayName("应当严格校验 8~20 位长度上下边界")
    void shouldEnforceLengthBoundaries() {
        // 7 位：长度不足
        assertThat(PasswordPolicyValidator.isValid("Abcdef1")).isFalse();
        // 8 位：恰好下边界且含 3 类字符
        assertThat(PasswordPolicyValidator.isValid("Abcdef12")).isTrue();
        // 20 位：恰好上边界且含 4 类字符
        assertThat(PasswordPolicyValidator.isValid("Abcdef1234567890!@#$")).isTrue();
        // 21 位：超出最大长度
        assertThat(PasswordPolicyValidator.isValid("Abcdef1234567890!@#$a")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Abc def12",
            "Abc\tdef12",
            "Abc\ndef12",
            "Abc\rdef12",
            "Abc\u00A0def12",
            " Abcdef12",
            "Abcdef12 "
    })
    @DisplayName("包含任意空白字符的口令均应当被拦截")
    void shouldRejectAnyWhitespace(String candidate) {
        assertThat(PasswordPolicyValidator.isValid(candidate)).isFalse();
        assertThatThrownBy(() -> PasswordPolicyValidator.validate(candidate))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(CommonBizResultCode.PARAM_INVALID.getCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "aaaaaaaa",
            "12345678",
            "abcdefgh",
            "ABCDEFGH",
            "!@#$%^&*",
            "abcd1234",
            "ABCD1234",
            "ABCDabcd",
            "abcd!@#$",
            "ABCD!@#$",
            "1234!@#$",
            "abcdefgh中文!"
    })
    @DisplayName("不足 3 类字符的口令应当被拦截")
    void shouldRejectLessThanThreeCategories(String candidate) {
        assertThat(PasswordPolicyValidator.isValid(candidate)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Abcdef12",
            "Abcdef!@",
            "ABC123!@",
            "abc123!@",
            "Ateng@Test2026"
    })
    @DisplayName("包含 3 类及 4 类字符的 8~20 位无空白口令应当放行")
    void shouldAcceptThreeOrFourCategories(String candidate) {
        assertThat(PasswordPolicyValidator.isValid(candidate)).isTrue();
        assertThatCode(() -> PasswordPolicyValidator.validate(candidate)).doesNotThrowAnyException();
    }
}

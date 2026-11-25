package io.github.atengk.solid.common.validator;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.solid.common.result.CommonBizResultCode;

/**
 * 全局统一强口令复杂度校验器：强制要求口令长度在 8~20 个字符之间，不包含空白字符，且至少包含大写字母、小写字母、数字和特殊字符中的 3 类。
 *
 * @author Ateng
 * @since 2026-11-25
 */
public final class PasswordPolicyValidator {

    /**
     * 口令最小允许长度
     */
    public static final int MIN_LENGTH = 8;

    /**
     * 口令最大允许长度
     */
    public static final int MAX_LENGTH = 20;

    /**
     * 至少需满足的字符类别数量
     */
    public static final int MIN_CATEGORY_COUNT = 3;

    private static final String POLICY_MESSAGE =
            "密码须为 8~20 位，不能包含空白字符，且至少包含大写字母、小写字母、数字、特殊字符中的 3 类";

    private PasswordPolicyValidator() {
    }

    /**
     * 判断候选口令是否满足平台强口令复杂度策略。
     *
     * @param password 待校验的原始口令，传入 {@code null} 时返回 {@code false}
     * @return 符合全部复杂度规则返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isValid(String password) {
        if (password == null) {
            return false;
        }
        int length = password.length();
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            return false;
        }

        boolean hasUpper = false;
        boolean hasLower = false;
        boolean hasDigit = false;
        boolean hasSpecial = false;

        for (int i = 0; i < length; i++) {
            char ch = password.charAt(i);
            if (Character.isWhitespace(ch) || Character.isSpaceChar(ch) || Character.isISOControl(ch)) {
                return false;
            }
            if (ch >= 'A' && ch <= 'Z') {
                hasUpper = true;
            } else if (ch >= 'a' && ch <= 'z') {
                hasLower = true;
            } else if (ch >= '0' && ch <= '9') {
                hasDigit = true;
            } else if (isAsciiSpecial(ch)) {
                hasSpecial = true;
            }
        }

        int categories = (hasUpper ? 1 : 0)
                + (hasLower ? 1 : 0)
                + (hasDigit ? 1 : 0)
                + (hasSpecial ? 1 : 0);
        return categories >= MIN_CATEGORY_COUNT;
    }

    /**
     * 校验候选口令是否符合复杂度策略，不合法时抛出语义化 {@link BusinessException}（错误码 100001）。
     *
     * @param password 待校验的原始口令，不可为空或违背复杂度规则
     * @throws BusinessException 当口令为空、长度越界、包含空白字符或字符类别不足 3 类时抛出
     */
    public static void validate(String password) {
        if (!isValid(password)) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, POLICY_MESSAGE);
        }
    }

    private static boolean isAsciiSpecial(char ch) {
        return ch > 32 && ch < 127
                && !(ch >= 'A' && ch <= 'Z')
                && !(ch >= 'a' && ch <= 'z')
                && !(ch >= '0' && ch <= '9');
    }
}

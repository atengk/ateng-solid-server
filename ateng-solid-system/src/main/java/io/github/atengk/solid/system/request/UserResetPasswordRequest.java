package io.github.atengk.solid.system.request;

import lombok.Data;
import lombok.ToString;

/**
 * 管理员重置用户登录密码请求体（密码留空时兜底读取系统参数 {@code sys.user.initPassword}）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@ToString(exclude = {"password"})
public class UserResetPasswordRequest {

    /**
     * 重置后的新明文口令（需满足强口令复杂度策略）
     */
    private String password;
}

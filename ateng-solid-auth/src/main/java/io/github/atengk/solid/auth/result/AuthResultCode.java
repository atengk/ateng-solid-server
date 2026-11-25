package io.github.atengk.solid.auth.result;

import io.github.atengk.boot.core.code.IResultCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 认证与会话治理域业务错误码枚举（号段：101000 ~ 101999）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Getter
@AllArgsConstructor
public enum AuthResultCode implements IResultCode {

    /**
     * 图形验证码为空、错误或已过期
     */
    CAPTCHA_INVALID(101001, "图形验证码无效或已过期，请重新输入"),

    /**
     * 登录用户名或密码不匹配（防枚举统一计数报错）
     */
    BAD_CREDENTIALS(101002, "用户名或密码错误"),

    /**
     * 用户账号已被停用
     */
    ACCOUNT_DISABLED(101003, "账号已被停用，请联系管理员处理"),

    /**
     * 连续登录失败达到阈值触发防爆破临时锁定
     */
    LOGIN_LOCKED(101004, "连续登录失败次数过多，请稍后重试"),

    /**
     * 所属租户不存在、已停用或已过授权有效期
     */
    TENANT_UNAVAILABLE(101005, "所属租户不存在、已停用或已过期，请联系平台管理员"),

    /**
     * 修改密码时提供的原密码校验未通过
     */
    OLD_PASSWORD_MISMATCH(101006, "当前密码输入有误，请核对后重试"),

    /**
     * 尝试强退当前请求正在使用的自身会话
     */
    CANNOT_KICKOUT_SELF(101007, "当前会话正在使用中，不可强退自身会话");

    /**
     * 业务状态码
     */
    private final Integer code;

    /**
     * 默认错误提示文案
     */
    private final String msg;
}

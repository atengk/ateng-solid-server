package io.github.atengk.solid.auth.security;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.solid.auth.result.AuthResultCode;
import lombok.Getter;

import java.io.Serial;

/**
 * 防爆破锁定专有业务异常，固定绑定错误码 {@code 101004} 并携带锁定维度与保底至少 1 秒的剩余倒计时秒数。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Getter
public class LoginLockedException extends BusinessException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 客户端 IP 熔断锁定维度
     */
    public static final String LOCK_TYPE_IP = "IP";

    /**
     * 作用域账号连续失败锁定维度
     */
    public static final String LOCK_TYPE_ACCOUNT = "ACCOUNT";

    /**
     * 锁定维度（{@code "IP"} 或 {@code "ACCOUNT"}）
     */
    private final String lockType;

    /**
     * 剩余锁定秒数（保底 {@code >= 1}）
     */
    private final long remainSeconds;

    /**
     * 构建携带结构化锁定维度与剩余倒计时的防爆破异常。
     *
     * @param lockType      锁定维度标识
     * @param remainSeconds 剩余锁定秒数
     */
    public LoginLockedException(String lockType, long remainSeconds) {
        super(AuthResultCode.LOGIN_LOCKED, buildMessage(lockType, Math.max(1L, remainSeconds)));
        this.lockType = lockType;
        this.remainSeconds = Math.max(1L, remainSeconds);
    }

    private static String buildMessage(String lockType, long safeSeconds) {
        if (LOCK_TYPE_IP.equals(lockType)) {
            return String.format("请求过于频繁，当前 IP 已被临时限制登录，请在 %d 秒后重试", safeSeconds);
        }
        return String.format("连续登录失败次数过多，账号已被临时锁定，请在 %d 秒后重试", safeSeconds);
    }
}

package io.github.atengk.solid.auth.security;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.web.handler.GlobalExceptionCustomizer;
import io.github.atengk.solid.auth.result.AuthResultCode;
import io.github.atengk.solid.auth.vo.LoginLockDetailVO;
import org.springframework.stereotype.Component;

/**
 * 认证域全局异常响应定制器：将 {@link LoginLockedException} 映射为携带 {@link LoginLockDetailVO} 的标准 {@code Result}（错误码 101004）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Component
public class AuthExceptionCustomizer implements GlobalExceptionCustomizer {

    @Override
    public Result<?> customize(Throwable throwable) {
        if (throwable instanceof LoginLockedException lockedException) {
            LoginLockDetailVO detail = LoginLockDetailVO.builder()
                    .lockType(lockedException.getLockType())
                    .remainSeconds(lockedException.getRemainSeconds())
                    .build();
            return new Result<>(AuthResultCode.LOGIN_LOCKED.getCode(), lockedException.getMsg(), detail);
        }
        return null;
    }
}

package io.github.atengk.solid.auth.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;

/**
 * 管理端账号登录请求载荷。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@ToString(exclude = "password")
public class AdminLoginRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 目标租户业务编码（单租户模式可缺省或固定为 000000）
     */
    @Size(max = 32, message = "租户编码长度不能超过 32 个字符")
    private String tenantCode;

    /**
     * 登录用户名
     */
    @NotBlank(message = "登录账号不能为空")
    @Size(max = 64, message = "登录账号长度不能超过 64 个字符")
    private String username;

    /**
     * 登录密码（明文经传输层加密通道提交后端做 PBKDF2 校验）
     */
    @NotBlank(message = "登录密码不能为空")
    @Size(max = 128, message = "登录密码长度不能超过 128 个字符")
    private String password;

    /**
     * 图形验证码唯一流水键
     */
    @Size(max = 128, message = "验证码标识长度不能超过 128 个字符")
    private String captchaKey;

    /**
     * 用户填写的图形验证码内容
     */
    @Size(max = 32, message = "验证码长度不能超过 32 个字符")
    private String captchaCode;

    /**
     * 授权模式标识（默认 {@code password}）
     */
    @Size(max = 32, message = "授权模式长度不能超过 32 个字符")
    private String grantType;
}

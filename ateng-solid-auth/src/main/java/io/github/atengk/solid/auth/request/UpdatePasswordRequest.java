package io.github.atengk.solid.auth.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;

/**
 * 个人中心自助修改密码请求载荷。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@ToString(exclude = {"oldPassword", "newPassword"})
public class UpdatePasswordRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 当前登录旧密码
     */
    @NotBlank(message = "当前密码不能为空")
    @Size(max = 128, message = "当前密码长度不能超过 128 个字符")
    private String oldPassword;

    /**
     * 待设置的新密码（须满足平台强口令策略）
     */
    @NotBlank(message = "新密码不能为空")
    @Size(max = 128, message = "新密码长度不能超过 128 个字符")
    private String newPassword;
}

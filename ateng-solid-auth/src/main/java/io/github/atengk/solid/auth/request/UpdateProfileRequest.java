package io.github.atengk.solid.auth.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;

/**
 * 个人中心基本资料更新请求载荷。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@ToString(exclude = {"email", "phone"})
public class UpdateProfileRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户昵称
     */
    @NotBlank(message = "用户昵称不能为空")
    @Size(min = 2, max = 30, message = "用户昵称长度须在 2~30 个字符之间")
    private String nickname;

    /**
     * 电子邮箱（传空串或空白可清空绑定）
     */
    @Size(max = 64, message = "电子邮箱长度不能超过 64 个字符")
    private String email;

    /**
     * 手机号码（传空串或空白可清空绑定）
     */
    @Size(max = 20, message = "手机号码长度不能超过 20 个字符")
    private String phone;

    /**
     * 性别字典值（动态校验 {@code sys_user_gender} 启用字典项）
     */
    @NotBlank(message = "用户性别不能为空")
    @Size(max = 16, message = "性别编码长度不能超过 16 个字符")
    private String gender;
}

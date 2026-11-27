package io.github.atengk.solid.system.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

import java.util.List;

/**
 * 修改管理端用户请求体（锁定登录账号与密码不通过本接口篡改）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@ToString(exclude = {"phone", "email"})
public class UserUpdateRequest {

    /**
     * 目标用户主键
     */
    @NotNull(message = "用户主键不能为空")
    @Positive(message = "用户主键必须为正整数")
    private Long id;

    /**
     * 用户昵称（2~30 个字符）
     */
    @NotBlank(message = "用户昵称不能为空")
    @Size(min = 2, max = 30, message = "用户昵称长度须在 2~30 个字符之间")
    private String nickname;

    /**
     * 归属部门主键（允许置空解除部门归属）
     */
    @Positive(message = "归属部门主键必须为正整数")
    private Long deptId;

    /**
     * 关联岗位主键集合（传入空集合即清空岗位关联）
     */
    @Size(max = 100, message = "关联岗位数量不能超过 100 个")
    private List<@NotNull(message = "岗位主键不能为空") @Positive(message = "岗位主键必须为正整数") Long> postIds;

    /**
     * 关联角色主键集合（传入空集合即清空角色关联；平台内置超管禁止剥离超管角色）
     */
    @Size(max = 100, message = "关联角色数量不能超过 100 个")
    private List<@NotNull(message = "角色主键不能为空") @Positive(message = "角色主键必须为正整数") Long> roleIds;

    /**
     * 手机号码（空白输入清空为 {@code null}）
     */
    @Size(max = 32, message = "手机号码长度不能超过 32 个字符")
    private String phone;

    /**
     * 电子邮箱（空白输入清空为 {@code null}）
     */
    @Size(max = 128, message = "电子邮箱长度不能超过 128 个字符")
    private String email;

    /**
     * 性别字典值（对齐 {@code sys_user_gender}）
     */
    @Size(max = 16, message = "性别字典编码长度不能超过 16 位")
    private String gender;

    /**
     * 账号启停状态（0=正常，1=停用；可空时保留原状态）
     */
    @Min(value = 0, message = "账号状态仅支持 0（正常）或 1（停用）")
    @Max(value = 1, message = "账号状态仅支持 0（正常）或 1（停用）")
    private Integer status;

    /**
     * 备注信息
     */
    @Size(max = 500, message = "备注长度不能超过 500 个字符")
    private String remark;
}

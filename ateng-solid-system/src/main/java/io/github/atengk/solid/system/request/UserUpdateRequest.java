package io.github.atengk.solid.system.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 修改管理端用户请求体（锁定登录账号与密码不通过本接口篡改）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
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
    private Long deptId;

    /**
     * 关联岗位主键集合（传入空集合即清空岗位关联）
     */
    private List<Long> postIds;

    /**
     * 关联角色主键集合（传入空集合即清空角色关联；平台内置超管禁止剥离超管角色）
     */
    private List<Long> roleIds;

    /**
     * 手机号码（空白输入清空为 {@code null}）
     */
    private String phone;

    /**
     * 电子邮箱（空白输入清空为 {@code null}）
     */
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

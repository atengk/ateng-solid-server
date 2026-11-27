package io.github.atengk.solid.system.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

import java.util.List;

/**
 * 新增管理端用户请求体。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@ToString(exclude = {"password", "phone", "email"})
public class UserCreateRequest {

    /**
     * 登录账号（2~30 位字母、数字或下划线，同租户未删除唯一）
     */
    @NotBlank(message = "用户账号不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_]{2,30}$", message = "用户账号须为 2~30 位字母、数字或下划线")
    private String username;

    /**
     * 用户昵称（2~30 个字符）
     */
    @NotBlank(message = "用户昵称不能为空")
    @Size(min = 2, max = 30, message = "用户昵称长度须在 2~30 个字符之间")
    private String nickname;

    /**
     * 初始登录密码（选填；留空时读取系统参数 {@code sys.user.initPassword}，统一执行强口令复杂度校验）
     */
    @Size(max = 128, message = "登录密码长度不能超过 128 个字符")
    private String password;

    /**
     * 归属部门主键（选填）
     */
    @Positive(message = "归属部门主键必须为正整数")
    private Long deptId;

    /**
     * 关联岗位主键集合（选填）
     */
    @Size(max = 100, message = "关联岗位数量不能超过 100 个")
    private List<@NotNull(message = "岗位主键不能为空") @Positive(message = "岗位主键必须为正整数") Long> postIds;

    /**
     * 关联角色主键集合（选填）
     */
    @Size(max = 100, message = "关联角色数量不能超过 100 个")
    private List<@NotNull(message = "角色主键不能为空") @Positive(message = "角色主键必须为正整数") Long> roleIds;

    /**
     * 手机号码（选填，同租户未删除唯一）
     */
    @Size(max = 32, message = "手机号码长度不能超过 32 个字符")
    private String phone;

    /**
     * 电子邮箱（选填，同租户未删除唯一）
     */
    @Size(max = 128, message = "电子邮箱长度不能超过 128 个字符")
    private String email;

    /**
     * 性别字典值（对齐 {@code sys_user_gender}：0=男，1=女，2=未知）
     */
    @Size(max = 16, message = "性别字典编码长度不能超过 16 位")
    private String gender;

    /**
     * 账号启停状态（0=正常，1=停用；缺省为 0）
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

package io.github.atengk.solid.system.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 新增 SaaS 租户及默认租户管理员 4 步原子初始化请求体。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@ToString(exclude = {"adminPassword"})
public class TenantCreateRequest {

    /**
     * 租户唯一业务编码（4~32 位字母/数字/下划线/短横线，禁填 000000）
     */
    @NotBlank(message = "租户编码不能为空")
    @Size(min = 4, max = 32, message = "租户编码长度须在 4~32 个字符之间")
    @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "租户编码仅支持字母、数字、下划线和短横线")
    private String tenantCode;

    /**
     * 租户名称
     */
    @NotBlank(message = "租户名称不能为空")
    @Size(min = 2, max = 50, message = "租户名称长度须在 2~50 个字符之间")
    private String tenantName;

    /**
     * 关联正常启用状态租户套餐主键
     */
    @NotNull(message = "必须选择关联租户套餐")
    @Min(value = 1, message = "租户套餐主键必须为正整数")
    private Long packageId;

    /**
     * 账号配额上限（-1 表示不限制，>= 1 表示最大有效账号总数，禁止传 0）
     */
    @NotNull(message = "账号配额上限不能为空")
    private Integer accountCount;

    /**
     * 租户授权过期时间（为空表示永久有效）
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expireTime;

    /**
     * 租户初始状态（0=正常，1=停用；默认 0）
     */
    @Min(value = 0, message = "租户状态仅支持 0（正常）或 1（停用）")
    @Max(value = 1, message = "租户状态仅支持 0（正常）或 1（停用）")
    private Integer status;

    /**
     * 备注
     */
    @Size(max = 500, message = "备注长度不能超过 500 个字符")
    private String remark;

    /**
     * 默认租户管理员登录账号
     */
    @NotBlank(message = "租户管理员账号不能为空")
    @Size(min = 2, max = 30, message = "租户管理员账号长度须在 2~30 个字符之间")
    @Pattern(regexp = "^[A-Za-z0-9_]+$", message = "租户管理员账号仅支持字母、数字和下划线")
    private String adminUsername;

    /**
     * 默认租户管理员初始密码（须满足 8~20 位无空白且至少含 3 类字符）
     */
    @NotBlank(message = "租户管理员初始密码不能为空")
    @Size(max = 128, message = "租户管理员初始密码长度不能超过 128 个字符")
    private String adminPassword;
}

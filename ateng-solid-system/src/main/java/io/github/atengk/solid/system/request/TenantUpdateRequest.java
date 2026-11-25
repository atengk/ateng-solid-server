package io.github.atengk.solid.system.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 编辑 SaaS 租户信息请求体（平台默认租户 {@code 0} 仅允许修改名称与备注，{@code tenantCode} 全程不可变）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
public class TenantUpdateRequest {

    /**
     * 租户主键（平台默认租户可传 {@code 0} 编辑名称与备注）
     */
    @NotNull(message = "租户主键不能为空")
    @Min(value = 0, message = "租户主键不能为负数")
    private Long id;

    /**
     * 租户编码（编辑态只读锁定，如显式传入须与原编码一致）
     */
    private String tenantCode;

    /**
     * 租户名称
     */
    @NotBlank(message = "租户名称不能为空")
    @Size(min = 2, max = 50, message = "租户名称长度须在 2~50 个字符之间")
    private String tenantName;

    /**
     * 关联租户套餐主键（非 0 号租户必填）
     */
    private Long packageId;

    /**
     * 账号配额上限（-1 不限；>= 1 限制用户数）
     */
    private Integer accountCount;

    /**
     * 租户授权过期时间（为空表示永久有效）
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expireTime;

    /**
     * 租户状态（0=正常，1=停用）
     */
    @Min(value = 0, message = "租户状态仅支持 0（正常）或 1（停用）")
    @Max(value = 1, message = "租户状态仅支持 0（正常）或 1（停用）")
    private Integer status;

    /**
     * 备注
     */
    @Size(max = 500, message = "备注长度不能超过 500 个字符")
    private String remark;
}

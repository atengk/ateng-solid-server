package io.github.atengk.solid.system.request;

import io.github.atengk.boot.core.model.PageQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 租户分页检索请求参数。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TenantQueryRequest extends PageQuery {

    /**
     * 租户业务编码模糊匹配关键字
     */
    @Size(max = 64, message = "租户编码关键字长度不能超过 64 个字符")
    private String tenantCode;

    /**
     * 租户名称模糊匹配关键字
     */
    @Size(max = 100, message = "租户名称关键字长度不能超过 100 个字符")
    private String tenantName;

    /**
     * 租户启停状态（0=正常，1=停用）
     */
    @Min(value = 0, message = "租户状态仅支持 0（正常）或 1（停用）")
    @Max(value = 1, message = "租户状态仅支持 0（正常）或 1（停用）")
    private Integer status;

    /**
     * 所属租户套餐主键
     */
    @Positive(message = "租户套餐主键必须为正整数")
    private Long packageId;
}

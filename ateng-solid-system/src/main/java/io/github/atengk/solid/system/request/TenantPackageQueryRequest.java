package io.github.atengk.solid.system.request;

import io.github.atengk.boot.core.model.PageQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 租户套餐分页检索请求参数。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TenantPackageQueryRequest extends PageQuery {

    /**
     * 套餐名称模糊匹配关键字
     */
    @Size(max = 100, message = "套餐名称关键字长度不能超过 100 个字符")
    private String packageName;

    /**
     * 套餐启停状态（0=正常，1=停用）
     */
    @Min(value = 0, message = "套餐状态仅支持 0（正常）或 1（停用）")
    @Max(value = 1, message = "套餐状态仅支持 0（正常）或 1（停用）")
    private Integer status;
}

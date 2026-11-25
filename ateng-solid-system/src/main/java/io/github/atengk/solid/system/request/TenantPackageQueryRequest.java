package io.github.atengk.solid.system.request;

import io.github.atengk.boot.core.model.PageQuery;
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
    private String packageName;

    /**
     * 套餐启停状态（0=正常，1=停用）
     */
    private Integer status;
}

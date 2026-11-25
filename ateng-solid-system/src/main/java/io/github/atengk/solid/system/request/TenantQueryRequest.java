package io.github.atengk.solid.system.request;

import io.github.atengk.boot.core.model.PageQuery;
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
    private String tenantCode;

    /**
     * 租户名称模糊匹配关键字
     */
    private String tenantName;

    /**
     * 租户启停状态（0=正常，1=停用）
     */
    private Integer status;

    /**
     * 所属租户套餐主键
     */
    private Long packageId;
}

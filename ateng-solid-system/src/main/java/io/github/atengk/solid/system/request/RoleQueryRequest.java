package io.github.atengk.solid.system.request;

import io.github.atengk.boot.core.model.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 角色分页检索查询条件。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RoleQueryRequest extends PageQuery {

    /**
     * 角色名称（模糊匹配）
     */
    private String roleName;

    /**
     * 角色编码（模糊匹配）
     */
    private String roleKey;

    /**
     * 角色状态（0=正常，1=停用）
     */
    private Integer status;
}

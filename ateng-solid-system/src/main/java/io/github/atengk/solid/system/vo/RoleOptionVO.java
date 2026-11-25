package io.github.atengk.solid.system.vo;

import lombok.Builder;
import lombok.Value;

/**
 * 角色下拉选项轻量化视图对象。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Value
@Builder
public class RoleOptionVO {

    /**
     * 角色主键
     */
    Long id;

    /**
     * 角色名称
     */
    String roleName;

    /**
     * 角色编码
     */
    String roleKey;
}

package io.github.atengk.solid.system.mapper;

import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.solid.system.entity.SysRoleMenu;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 角色与菜单关联表持久层 Mapper。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Mapper
public interface SysRoleMenuMapper extends AtengBaseMapper<SysRoleMenu> {

    /**
     * 统计未删除租户下未删除角色中引用指定菜单主键的数量（跨租户全量校验，需在 {@code runWithoutTenant} 内调用）。
     *
     * <p>遵循租户软删除逻辑隔离归档边界（仅软删 {@code sys_tenant} 主表），显式关联 {@code sys_tenant.deleted = 0}
     * 过滤已逻辑删除租户的归档角色引用，避免阻塞平台删除菜单。</p>
     *
     * @param menuId 菜单主键
     * @return 引用该菜单的有效租户未删除角色关联数量
     */
    @Select("""
            SELECT COUNT(1)
            FROM sys_role_menu rm
            INNER JOIN sys_role r ON r.id = rm.role_id AND r.tenant_id = rm.tenant_id
            INNER JOIN sys_tenant t ON t.id = rm.tenant_id
            WHERE rm.menu_id = #{menuId}
              AND r.deleted = 0
              AND t.deleted = 0
            """)
    long countActiveRoleReferencesByMenuId(@Param("menuId") Long menuId);
}

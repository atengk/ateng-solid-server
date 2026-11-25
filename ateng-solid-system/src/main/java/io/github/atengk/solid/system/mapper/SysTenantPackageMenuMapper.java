package io.github.atengk.solid.system.mapper;

import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.solid.system.entity.SysTenantPackageMenu;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 租户套餐与菜单关联表持久层 Mapper。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Mapper
public interface SysTenantPackageMenuMapper extends AtengBaseMapper<SysTenantPackageMenu> {

    /**
     * 统计未删除租户套餐中引用指定菜单主键的数量。
     *
     * @param menuId 菜单主键
     * @return 引用该菜单的未删除套餐关联数量
     */
    @Select("""
            SELECT COUNT(1)
            FROM sys_tenant_package_menu pm
            INNER JOIN sys_tenant_package p ON p.id = pm.package_id
            WHERE pm.menu_id = #{menuId}
              AND p.deleted = 0
            """)
    long countActivePackageReferencesByMenuId(@Param("menuId") Long menuId);
}

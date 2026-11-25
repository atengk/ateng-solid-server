package io.github.atengk.solid.system.mapper;

import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.solid.system.entity.SysTenantPackageMenu;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

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

    /**
     * 批量统计指定套餐集合各自的关联菜单数量（杜绝列表序列化 N+1 查询）。
     *
     * @param packageIds 套餐主键集合
     * @return 按套餐主键聚合的菜单计数列表
     */
    @Results({
            @Result(column = "group_id", property = "groupId"),
            @Result(column = "row_count", property = "rowCount")
    })
    @Select("""
            <script>
            SELECT package_id AS group_id, COUNT(1) AS row_count
            FROM sys_tenant_package_menu
            <where>
              <choose>
                <when test="packageIds != null and !packageIds.isEmpty()">
                  package_id IN
                  <foreach collection="packageIds" item="id" open="(" separator="," close=")">
                    #{id}
                  </foreach>
                </when>
                <otherwise>
                  1 = 0
                </otherwise>
              </choose>
            </where>
            GROUP BY package_id
            </script>
            """)
    List<GroupCountRow> selectMenuCountsByPackageIds(@Param("packageIds") Collection<Long> packageIds);
}

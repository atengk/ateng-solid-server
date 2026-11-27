package io.github.atengk.solid.system.mapper;

import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.solid.system.entity.SysRoleDept;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 角色与自定部门关联表持久层 Mapper。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Mapper
public interface SysRoleDeptMapper extends AtengBaseMapper<SysRoleDept> {

    /**
     * 统计将指定部门作为唯一自定义范围绑定的活跃 {@code CUSTOM}（{@code data_scope = 2}）角色数量。
     *
     * @param tenantId 工作租户主键
     * @param deptId   待删除部门主键
     * @return 唯一依赖该部门的未删除自定数据权限角色数量
     */
    @Select("""
            SELECT COUNT(1)
            FROM sys_role_dept rd
            INNER JOIN sys_role r ON r.id = rd.role_id AND r.tenant_id = rd.tenant_id
            WHERE rd.tenant_id = #{tenantId}
              AND rd.dept_id = #{deptId}
              AND r.deleted = 0
              AND r.data_scope = 2
              AND NOT EXISTS (
                  SELECT 1
                  FROM sys_role_dept other
                  WHERE other.tenant_id = rd.tenant_id
                    AND other.role_id = rd.role_id
                    AND other.dept_id <> rd.dept_id
              )
            """)
    long countSoleBoundCustomRoles(@Param("tenantId") Long tenantId, @Param("deptId") Long deptId);
}

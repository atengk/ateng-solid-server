package io.github.atengk.solid.system.mapper;

import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysUserRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户与角色关联表持久层 Mapper。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Mapper
public interface SysUserRoleMapper extends AtengBaseMapper<SysUserRole> {

    /**
     * 统计指定租户与角色下处于未删除状态的有效绑定用户数（用于超级管理员幂等引导检测）。
     *
     * @param tenantId 租户主键
     * @param roleId   角色主键
     * @return 有效绑定用户数量
     */
    @Select("""
            SELECT COUNT(1)
            FROM sys_user_role ur
            INNER JOIN sys_user u ON u.id = ur.user_id AND u.tenant_id = ur.tenant_id
            WHERE ur.tenant_id = #{tenantId}
              AND ur.role_id = #{roleId}
              AND u.deleted = 0
            """)
    int countBoundActiveUsersByRole(@Param("tenantId") Long tenantId, @Param("roleId") Long roleId);

    /**
     * 查询指定租户下用户关联的全部正常启用角色核心摘要（ID、编码与名称），按排序升序返回。
     *
     * @param tenantId 租户主键
     * @param userId   用户主键
     * @return 启用中的角色列表
     */
    @Select("""
            SELECT r.id, r.role_key, r.role_name
            FROM sys_user_role ur
            INNER JOIN sys_role r ON r.id = ur.role_id AND r.tenant_id = ur.tenant_id
            WHERE ur.tenant_id = #{tenantId}
              AND ur.user_id = #{userId}
              AND r.status = 0
              AND r.deleted = 0
            ORDER BY r.sort ASC, r.id ASC
            """)
    List<SysRole> selectActiveRolesByUserId(@Param("tenantId") Long tenantId, @Param("userId") Long userId);
}

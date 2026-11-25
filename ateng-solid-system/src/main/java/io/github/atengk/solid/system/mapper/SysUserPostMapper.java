package io.github.atengk.solid.system.mapper;

import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.solid.system.entity.SysUserPost;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户与岗位关联表持久层 Mapper。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Mapper
public interface SysUserPostMapper extends AtengBaseMapper<SysUserPost> {

    /**
     * 查询指定租户下用户关联的正常启用岗位名称列表（用于个人中心展示）。
     *
     * @param tenantId 租户主键
     * @param userId   用户主键
     * @return 启用中的岗位名称列表
     */
    @Select("""
            SELECT p.post_name
            FROM sys_user_post up
            INNER JOIN sys_post p ON p.id = up.post_id AND p.tenant_id = up.tenant_id
            WHERE up.tenant_id = #{tenantId}
              AND up.user_id = #{userId}
              AND p.status = 0
              AND p.deleted = 0
            ORDER BY p.sort ASC, p.id ASC
            """)
    List<String> selectActivePostNamesByUserId(@Param("tenantId") Long tenantId, @Param("userId") Long userId);
}

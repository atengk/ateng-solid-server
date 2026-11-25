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

    /**
     * 批量统计当前租户下仍被未删除用户占用的岗位主键列表。
     *
     * @param tenantId 租户主键
     * @param postIds  待检测的岗位主键集合
     * @return 被活跃用户占用的岗位主键列表
     */
    @Select("""
            <script>
            SELECT DISTINCT up.post_id
            FROM sys_user_post up
            INNER JOIN sys_user u ON u.id = up.user_id AND u.tenant_id = up.tenant_id
            WHERE up.tenant_id = #{tenantId}
              AND u.deleted = 0
              <choose>
                <when test="postIds != null and !postIds.isEmpty()">
                  AND up.post_id IN
                  <foreach collection="postIds" item="postId" open="(" separator="," close=")">
                    #{postId}
                  </foreach>
                </when>
                <otherwise>
                  AND 1 = 0
                </otherwise>
              </choose>
            </script>
            """)
    List<Long> selectReferencedPostIds(@Param("tenantId") Long tenantId, @Param("postIds") List<Long> postIds);
}

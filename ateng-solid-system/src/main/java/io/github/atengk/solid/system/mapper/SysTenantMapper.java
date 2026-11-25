package io.github.atengk.solid.system.mapper;

import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.solid.system.entity.SysTenant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 租户信息表持久层 Mapper。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Mapper
public interface SysTenantMapper extends AtengBaseMapper<SysTenant> {

    /**
     * 批量统计指定套餐集合各自绑定的未删除租户数量（供套餐列表展示与删除前引用拦截共用）。
     *
     * @param packageIds 套餐主键集合
     * @return 按套餐主键聚合的绑定租户计数列表
     */
    @Results({
            @Result(column = "group_id", property = "groupId"),
            @Result(column = "row_count", property = "rowCount")
    })
    @Select("""
            <script>
            SELECT package_id AS group_id, COUNT(1) AS row_count
            FROM sys_tenant
            WHERE deleted = 0
              <choose>
                <when test="packageIds != null and !packageIds.isEmpty()">
                  AND package_id IN
                  <foreach collection="packageIds" item="id" open="(" separator="," close=")">
                    #{id}
                  </foreach>
                </when>
                <otherwise>
                  AND 1 = 0
                </otherwise>
              </choose>
            GROUP BY package_id
            </script>
            """)
    List<GroupCountRow> selectTenantCountsByPackageIds(@Param("packageIds") Collection<Long> packageIds);
}

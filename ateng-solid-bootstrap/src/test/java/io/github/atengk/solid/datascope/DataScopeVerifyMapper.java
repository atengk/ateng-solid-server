package io.github.atengk.solid.datascope;

import io.github.atengk.boot.mybatisplus.annotation.DataScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 仅供 {@code ateng-solid-bootstrap} 集成测试验证真实 H2 SQL {@code @DataScope} 改写与执行闭环的探针 Mapper。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Mapper
public interface DataScopeVerifyMapper {

    /**
     * 按用户驱动表标准 {@code @DataScope(alias = "u", deptColumn = "dept_id", userColumn = "id")} 查询可见用户主键。
     *
     * @return 数据权限过滤后的用户主键列表
     */
    @DataScope(alias = "u", deptColumn = "dept_id", userColumn = "id")
    @Select("SELECT u.id FROM sys_user u WHERE u.deleted = 0 ORDER BY u.id")
    List<Long> selectVisibleUserIds();

    /**
     * 按通用创建人列 {@code @DataScope(deptColumn = "dept_id", userColumn = "create_by")} 验证字符串型用户标识路径。
     *
     * @return 数据权限过滤后的用户主键列表
     */
    @DataScope(deptColumn = "dept_id", userColumn = "create_by")
    @Select("SELECT id FROM sys_user WHERE deleted = 0 ORDER BY id")
    List<Long> selectVisibleUserIdsByCreateBy();
}

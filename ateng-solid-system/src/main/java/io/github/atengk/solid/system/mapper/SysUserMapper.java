package io.github.atengk.solid.system.mapper;

import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.solid.system.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户表持久层 Mapper。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Mapper
public interface SysUserMapper extends AtengBaseMapper<SysUser> {

    /**
     * 按用户主键单点查询未删除用户的登录账号（用于异步操作审计日志补全真实操作人账号）。
     *
     * @param id 用户主键 ID
     * @return 登录账号；记录不存在或已逻辑删除时返回 {@code null}
     */
    @Select("SELECT username FROM sys_user WHERE id = #{id} AND deleted = 0")
    String selectUsernameById(@Param("id") Long id);
}

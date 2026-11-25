package io.github.atengk.solid.system.mapper;

import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.solid.system.entity.SysUserRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户与角色关联表持久层 Mapper。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Mapper
public interface SysUserRoleMapper extends AtengBaseMapper<SysUserRole> {
}

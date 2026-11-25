package io.github.atengk.solid.system.mapper;

import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.solid.system.entity.SysRoleDept;
import org.apache.ibatis.annotations.Mapper;

/**
 * 角色与自定部门关联表持久层 Mapper。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Mapper
public interface SysRoleDeptMapper extends AtengBaseMapper<SysRoleDept> {
}

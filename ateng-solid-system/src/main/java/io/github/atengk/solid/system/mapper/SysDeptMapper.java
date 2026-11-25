package io.github.atengk.solid.system.mapper;

import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.solid.system.entity.SysDept;
import org.apache.ibatis.annotations.Mapper;

/**
 * 部门表持久层 Mapper。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Mapper
public interface SysDeptMapper extends AtengBaseMapper<SysDept> {
}

package io.github.atengk.solid.system.mapper;

import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.solid.system.entity.SysDictType;
import org.apache.ibatis.annotations.Mapper;

/**
 * 动态字典类型表持久层 Mapper。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Mapper
public interface SysDictTypeMapper extends AtengBaseMapper<SysDictType> {
}

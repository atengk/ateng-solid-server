package io.github.atengk.solid.system.mapper;

import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.solid.system.entity.SysConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统参数表持久层 Mapper。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Mapper
public interface SysConfigMapper extends AtengBaseMapper<SysConfig> {
}

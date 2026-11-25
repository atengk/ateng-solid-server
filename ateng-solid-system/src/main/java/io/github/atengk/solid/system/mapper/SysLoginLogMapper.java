package io.github.atengk.solid.system.mapper;

import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.solid.system.entity.SysLoginLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 登录审计日志表持久层 Mapper。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Mapper
public interface SysLoginLogMapper extends AtengBaseMapper<SysLoginLog> {
}

package io.github.atengk.solid.system.mapper;

import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.solid.system.entity.SysMenu;
import org.apache.ibatis.annotations.Mapper;

/**
 * 菜单与按钮权限表持久层 Mapper。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Mapper
public interface SysMenuMapper extends AtengBaseMapper<SysMenu> {
}

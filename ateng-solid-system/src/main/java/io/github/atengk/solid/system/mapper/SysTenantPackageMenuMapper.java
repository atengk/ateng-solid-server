package io.github.atengk.solid.system.mapper;

import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.solid.system.entity.SysTenantPackageMenu;
import org.apache.ibatis.annotations.Mapper;

/**
 * 租户套餐与菜单关联表持久层 Mapper。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Mapper
public interface SysTenantPackageMenuMapper extends AtengBaseMapper<SysTenantPackageMenu> {
}

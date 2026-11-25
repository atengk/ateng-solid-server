package io.github.atengk.solid.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseIdEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serial;

/**
 * 租户套餐与菜单关联持久层实体（平台共享表，无软删）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
@TableName("sys_tenant_package_menu")
public class SysTenantPackageMenu extends BaseIdEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 租户套餐主键
     */
    private Long packageId;

    /**
     * 菜单主键（严禁关联 platform_only=true 节点）
     */
    private Long menuId;
}

package io.github.atengk.solid.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseLogicEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serial;

/**
 * 租户套餐持久层实体（平台共享表，免除行级多租户过滤）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
@TableName("sys_tenant_package")
public class SysTenantPackage extends BaseLogicEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 套餐名称（全局未删除唯一）
     */
    private String packageName;

    /**
     * 菜单树是否父子联动（true=联动，false=独立）
     */
    private Boolean menuCheckStrictly;

    /**
     * 套餐启停状态（0=正常，1=停用）
     */
    private Integer status;

    /**
     * 备注
     */
    private String remark;
}

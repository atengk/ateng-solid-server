package io.github.atengk.solid.system.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 租户套餐分页列表视图对象（含包含菜单数与绑定租户数聚合统计）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantPackageVO {

    /**
     * 套餐主键
     */
    private Long id;

    /**
     * 套餐名称
     */
    private String packageName;

    /**
     * 包含菜单与权限节点数量
     */
    private Integer menuCount;

    /**
     * 当前绑定该套餐的未删除租户数量
     */
    private Integer tenantCount;

    /**
     * 菜单树是否父子联动
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

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}

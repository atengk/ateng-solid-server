package io.github.atengk.solid.system.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 租户套餐详情视图对象（含已授权菜单主键列表与树联动标志）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantPackageDetailVO {

    /**
     * 套餐主键
     */
    private Long id;

    /**
     * 套餐名称
     */
    private String packageName;

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

    /**
     * 已绑定菜单主键列表
     */
    private List<Long> menuIds;
}

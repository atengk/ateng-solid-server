package io.github.atengk.solid.system.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 租户套餐缩减菜单影响范围只读预览视图对象。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantPackageShrinkPreviewVO {

    /**
     * 本次被移除的菜单主键集合
     */
    private List<Long> removedMenuIds;

    /**
     * 本次被移除的菜单标题清单
     */
    private List<String> removedMenuTitles;

    /**
     * 受影响绑定租户数量
     */
    private Integer affectedTenantCount;

    /**
     * 受影响关联租户清单
     */
    private List<AffectedTenantVO> affectedTenants;

    /**
     * 受套餐菜单裁剪影响的单个租户摘要。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AffectedTenantVO {

        /**
         * 租户主键
         */
        private Long id;

        /**
         * 租户业务编码
         */
        private String tenantCode;

        /**
         * 租户名称
         */
        private String tenantName;
    }
}

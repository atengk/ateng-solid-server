package io.github.atengk.solid.system.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 正常启用租户套餐下拉选项视图对象。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantPackageOptionVO {

    /**
     * 套餐主键
     */
    private Long id;

    /**
     * 套餐名称
     */
    private String packageName;
}

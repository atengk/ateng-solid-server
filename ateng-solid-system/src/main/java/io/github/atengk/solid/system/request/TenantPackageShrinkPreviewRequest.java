package io.github.atengk.solid.system.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 套餐菜单缩减影响范围预览请求体。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
public class TenantPackageShrinkPreviewRequest {

    /**
     * 目标套餐主键
     */
    @NotNull(message = "套餐主键不能为空")
    @Min(value = 1, message = "套餐主键必须为正整数")
    private Long packageId;

    /**
     * 编辑后的新版菜单主键列表
     */
    private List<Long> newMenuIds;
}

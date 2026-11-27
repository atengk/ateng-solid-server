package io.github.atengk.solid.system.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
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
    @Size(max = 2000, message = "套餐菜单数量不能超过 2000 个")
    private List<@NotNull(message = "菜单主键不能为空") @Positive(message = "菜单主键必须为正整数") Long> newMenuIds;
}

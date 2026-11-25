package io.github.atengk.solid.system.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 租户套餐新增与修改请求体（禁止包含任何 {@code platform_only=1} 菜单）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
public class TenantPackageSaveRequest {

    /**
     * 套餐主键（新增留空，修改必填）
     */
    private Long id;

    /**
     * 套餐名称（全局未删除唯一）
     */
    @NotBlank(message = "套餐名称不能为空")
    @Size(min = 2, max = 50, message = "套餐名称长度须在 2~50 个字符之间")
    private String packageName;

    /**
     * 菜单树是否父子联动（默认 true）
     */
    private Boolean menuCheckStrictly;

    /**
     * 套餐启停状态（0=正常，1=停用；新增默认 0）
     */
    @Min(value = 0, message = "套餐状态仅支持 0（正常）或 1（停用）")
    @Max(value = 1, message = "套餐状态仅支持 0（正常）或 1（停用）")
    private Integer status;

    /**
     * 备注
     */
    @Size(max = 500, message = "备注长度不能超过 500 个字符")
    private String remark;

    /**
     * 套餐包含的菜单与按钮主键列表
     */
    private List<Long> menuIds;
}

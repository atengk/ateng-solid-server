package io.github.atengk.solid.system.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 角色新增与修改请求体（含菜单权限绑定）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
public class RoleSaveRequest {

    /**
     * 角色主键（新增留空，修改必填）
     */
    private Long id;

    /**
     * 角色名称
     */
    @NotBlank(message = "角色名称不能为空")
    @Size(min = 2, max = 50, message = "角色名称长度须在 2~50 个字符之间")
    private String roleName;

    /**
     * 角色编码（禁用保留编码 super_admin 与 admin）
     */
    @NotBlank(message = "角色编码不能为空")
    @Size(min = 2, max = 64, message = "角色编码长度须在 2~64 个字符之间")
    @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "角色编码仅支持字母、数字、下划线和短横线")
    private String roleKey;

    /**
     * 显示排序
     */
    @NotNull(message = "显示排序不能为空")
    @Min(value = 0, message = "显示排序不能小于 0")
    @Max(value = 9999, message = "显示排序不能大于 9999")
    private Integer sort;

    /**
     * 角色状态（0=正常，1=停用；新增默认 0）
     */
    @Min(value = 0, message = "角色状态仅支持 0（正常）或 1（停用）")
    @Max(value = 1, message = "角色状态仅支持 0（正常）或 1（停用）")
    private Integer status;

    /**
     * 菜单权限树是否父子联动（默认 true）
     */
    private Boolean menuCheckStrictly;

    /**
     * 备注
     */
    @Size(max = 500, message = "备注长度不能超过 500 个字符")
    private String remark;

    /**
     * 授权菜单主键列表
     */
    @Size(max = 2000, message = "授权菜单数量不能超过 2000 个")
    private List<@NotNull(message = "菜单主键不能为空") @Positive(message = "菜单主键必须为正整数") Long> menuIds;
}

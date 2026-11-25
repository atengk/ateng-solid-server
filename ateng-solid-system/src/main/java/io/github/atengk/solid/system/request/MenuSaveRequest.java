package io.github.atengk.solid.system.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 菜单与按钮权限新增/修改请求体。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
public class MenuSaveRequest {

    /**
     * 菜单主键（新增留空，修改必填）
     */
    private Long id;

    /**
     * 父菜单主键（顶级传 0）
     */
    @NotNull(message = "上级菜单不能为空")
    @Min(value = 0, message = "上级菜单标识无效")
    private Long parentId;

    /**
     * 节点类型（M=目录，C=页面菜单，F=按钮权限）
     */
    @NotBlank(message = "菜单类型不能为空")
    private String menuType;

    /**
     * 菜单或权限名称
     */
    @NotBlank(message = "菜单名称不能为空")
    @Size(min = 1, max = 50, message = "菜单名称长度须在 1~50 个字符之间")
    private String title;

    /**
     * 路由组件名（C 非外链必填 PascalCase，全树唯一）
     */
    @Size(max = 64, message = "路由组件名称长度不能超过 64 个字符")
    private String name;

    /**
     * 路由路径或外链地址（M/C 必填，F 节点自动清理）
     */
    @Size(max = 255, message = "路由地址长度不能超过 255 个字符")
    private String path;

    /**
     * 前端视图组件路径（C 非外链必填）
     */
    @Size(max = 255, message = "组件路径长度不能超过 255 个字符")
    private String component;

    /**
     * 目录默认重定向路径（仅 M 节点使用）
     */
    @Size(max = 255, message = "重定向路径长度不能超过 255 个字符")
    private String redirect;

    /**
     * 三段式权限标识（F 节点必填，C 节点选填）
     */
    @Size(max = 100, message = "权限标识长度不能超过 100 个字符")
    private String perms;

    /**
     * 图标名称
     */
    @Size(max = 64, message = "图标标识长度不能超过 64 个字符")
    private String icon;

    /**
     * 显示排序
     */
    @NotNull(message = "显示排序不能为空")
    @Min(value = 0, message = "显示排序不能小于 0")
    @Max(value = 9999, message = "显示排序不能大于 9999")
    private Integer sort;

    /**
     * 侧栏显隐状态（0=显示，1=隐藏）
     */
    @Min(value = 0, message = "显隐状态仅支持 0（显示）或 1（隐藏）")
    @Max(value = 1, message = "显隐状态仅支持 0（显示）或 1（隐藏）")
    private Integer visible;

    /**
     * 启停状态（0=正常，1=停用）
     */
    @Min(value = 0, message = "菜单状态仅支持 0（正常）或 1（停用）")
    @Max(value = 1, message = "菜单状态仅支持 0（正常）或 1（停用）")
    private Integer status;

    /**
     * 是否开启 KeepAlive 页面缓存
     */
    private Boolean keepAlive;

    /**
     * 是否为外链菜单
     */
    private Boolean isExternal;

    /**
     * 是否仅平台默认租户可见维护
     */
    private Boolean platformOnly;
}

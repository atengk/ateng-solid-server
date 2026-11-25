package io.github.atengk.solid.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseLogicEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serial;

/**
 * 菜单与按钮权限持久层实体（平台共享表，免除行级多租户过滤）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
@TableName("sys_menu")
public class SysMenu extends BaseLogicEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 父菜单主键（顶级目录为 0）
     */
    private Long parentId;

    /**
     * 节点类型（M=目录，C=页面菜单，F=按钮权限）
     */
    private String menuType;

    /**
     * 菜单或权限显示名称
     */
    private String title;

    /**
     * 前端路由组件名称（C 非外链必填 PascalCase，用于 KeepAlive）
     */
    private String name;

    /**
     * 路由路径或外链地址（F 节点为 null）
     */
    private String path;

    /**
     * 前端视图组件路径（相对 {@code src/views/}；仅 C 非外链必填）
     */
    private String component;

    /**
     * 目录默认重定向路径（仅 M 节点使用）
     */
    private String redirect;

    /**
     * 三段式权限标识（F 节点必填；初始种子 M/C 为 null）
     */
    private String perms;

    /**
     * 菜单图标标识（对应 {@code @element-plus/icons-vue}）
     */
    private String icon;

    /**
     * 显示排序（升序）
     */
    private Integer sort;

    /**
     * 侧栏显隐状态（0=显示，1=隐藏）
     */
    private Integer visible;

    /**
     * 启停状态（0=正常，1=停用）
     */
    private Integer status;

    /**
     * 页面是否开启 KeepAlive 缓存
     */
    private Boolean keepAlive;

    /**
     * 是否为外部链接
     */
    private Boolean isExternal;

    /**
     * 是否仅平台默认租户可见维护（true 时禁止入选任何租户套餐）
     */
    private Boolean platformOnly;
}

package io.github.atengk.solid.system.vo;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 菜单权限树节点响应视图对象。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Value
@Builder
public class MenuTreeVO {

    /**
     * 菜单主键
     */
    Long id;

    /**
     * 父菜单主键（顶级节点为 0）
     */
    Long parentId;

    /**
     * 节点类型（M=目录，C=页面菜单，F=按钮权限）
     */
    String menuType;

    /**
     * 菜单或按钮显示名称
     */
    String title;

    /**
     * 前端路由组件名称（C 非外链必填 PascalCase）
     */
    String name;

    /**
     * 路由路径或外链 URL（F 节点为 null）
     */
    String path;

    /**
     * 前端视图组件路径（相对 {@code src/views/}）
     */
    String component;

    /**
     * 目录默认重定向路径（仅 M 节点有效）
     */
    String redirect;

    /**
     * 三段式权限标识（如 {@code system:user:list}）
     */
    String perms;

    /**
     * 图标名称
     */
    String icon;

    /**
     * 显示排序（升序）
     */
    Integer sort;

    /**
     * 侧栏显隐状态（0=显示，1=隐藏）
     */
    Integer visible;

    /**
     * 启停状态（0=正常，1=停用）
     */
    Integer status;

    /**
     * 是否开启 KeepAlive 页面缓存
     */
    Boolean keepAlive;

    /**
     * 是否为外部链接
     */
    Boolean isExternal;

    /**
     * 是否仅平台默认租户可见维护
     */
    Boolean platformOnly;

    /**
     * 创建时间
     */
    LocalDateTime createTime;

    /**
     * 下级子菜单树集合（无子节点为空列表）
     */
    @Builder.Default
    List<MenuTreeVO> children = Collections.emptyList();
}

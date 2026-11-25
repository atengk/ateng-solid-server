package io.github.atengk.solid.system.vo;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * 菜单节点详情响应视图对象。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Value
@Builder
public class MenuVO {

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
     * 前端路由组件名称
     */
    String name;

    /**
     * 路由路径或外链地址
     */
    String path;

    /**
     * 前端视图组件路径
     */
    String component;

    /**
     * 目录默认重定向路径
     */
    String redirect;

    /**
     * 三段式权限标识
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
}

package io.github.atengk.solid.system.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 管理端动态路由菜单树节点数据传输对象（已由系统域完成停用剪枝与平台专属过滤）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteMenuDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 菜单主键
     */
    private Long id;

    /**
     * 上级目录主键（顶级为 0）
     */
    private Long parentId;

    /**
     * 节点类型（M=目录，C=菜单）
     */
    private String menuType;

    /**
     * 菜单标题
     */
    private String title;

    /**
     * 前端路由组件名称（PascalCase，用于 KeepAlive 精确缓存匹配）
     */
    private String name;

    /**
     * 路由路径或外链地址
     */
    private String path;

    /**
     * 前端视图组件路径（相对 {@code src/views/}）
     */
    private String component;

    /**
     * 目录默认重定向路径
     */
    private String redirect;

    /**
     * 菜单图标标识
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
     * 页面是否开启 KeepAlive 缓存
     */
    private Boolean keepAlive;

    /**
     * 是否为外部链接
     */
    private Boolean isExternal;

    /**
     * 是否为平台专属菜单
     */
    private Boolean platformOnly;

    /**
     * 子路由菜单集合（无子节点时保持空集合）
     */
    @Builder.Default
    private List<RouteMenuDTO> children = Collections.emptyList();
}

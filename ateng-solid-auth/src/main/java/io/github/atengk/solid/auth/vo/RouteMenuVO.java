package io.github.atengk.solid.auth.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 动态菜单路由树节点视图对象（对应 {@code GET /admin/auth/routes}）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteMenuVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 菜单主键（JSON 序列化为字符串）
     */
    private Long id;

    /**
     * 上级目录主键（JSON 序列化为字符串）
     */
    private Long parentId;

    /**
     * 节点类型（{@code M}=目录，{@code C}=菜单）
     */
    private String menuType;

    /**
     * 菜单标题
     */
    private String title;

    /**
     * 前端路由名称（PascalCase，用于 KeepAlive 精确缓存）
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
     * 排序序号（升序）
     */
    private Integer sort;

    /**
     * 侧栏显隐状态（0=显示，1=隐藏）
     */
    private Integer visible;

    /**
     * 是否开启 KeepAlive 缓存
     */
    private Boolean keepAlive;

    /**
     * 是否为外部链接
     */
    private Boolean isExternal;

    /**
     * 是否平台专属菜单
     */
    private Boolean platformOnly;

    /**
     * 子路由菜单集合（空集保底返回 {@code []}）
     */
    @Builder.Default
    private List<RouteMenuVO> children = Collections.emptyList();
}

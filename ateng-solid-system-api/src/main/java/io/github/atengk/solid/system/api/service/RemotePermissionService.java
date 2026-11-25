package io.github.atengk.solid.system.api.service;

import io.github.atengk.solid.system.api.dto.RouteMenuDTO;

import java.util.List;

/**
 * 角色、权限标识与动态路由菜单跨模块远程服务契约。
 *
 * @author Ateng
 * @since 2026-11-25
 */
public interface RemotePermissionService {

    /**
     * 查询指定租户下用户被授予的正常启用角色编码列表。
     *
     * @param tenantId 显式定界的租户主键，不可为 {@code null}
     * @param userId   用户主键
     * @return 角色编码列表；无匹配时保底返回空集合
     */
    List<String> getRoleKeysByUserId(Long tenantId, Long userId);

    /**
     * 查询指定租户下单个角色对应的权限标识集合。
     *
     * @param tenantId 显式定界的租户主键，不可为 {@code null}
     * @param roleKey  角色编码
     * @return 权限标识列表；无匹配时保底返回空集合
     */
    List<String> getPermissionsByRoleKey(Long tenantId, String roleKey);

    /**
     * 查询用户的直接授权权限标识集合（未关联角色直赋的权限）。
     *
     * @param tenantId 显式定界的租户主键，不可为 {@code null}
     * @param userId   用户主键
     * @return 直接权限标识列表；无匹配时保底返回空集合
     */
    List<String> getDirectPermissionsByUserId(Long tenantId, Long userId);

    /**
     * 查询并组装用户当前生效视角可见的目录与页面路由树（已完成停用剪枝、平台专属与多租户开关过滤）。
     *
     * @param ownerTenantId     登录账号归属租户主键
     * @param effectiveTenantId 当前请求生效租户主键（超管跨租户切换后可能与归属租户不同）
     * @param userId            用户主键
     * @param superAdmin        是否具备平台超级管理员身份
     * @param tenantEnabled     系统是否启用 SaaS 多租户模式
     * @return 嵌套路由菜单树列表；无可见菜单时保底返回空集合
     */
    List<RouteMenuDTO> getRouteMenusByUserId(
            Long ownerTenantId,
            Long effectiveTenantId,
            Long userId,
            boolean superAdmin,
            boolean tenantEnabled
    );
}

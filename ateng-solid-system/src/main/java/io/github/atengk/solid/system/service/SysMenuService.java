package io.github.atengk.solid.system.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.TransactionUtil;
import io.github.atengk.boot.satoken.cache.PermissionCacheService;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.enums.MenuTypeEnum;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysMenu;
import io.github.atengk.solid.system.entity.SysRoleMenu;
import io.github.atengk.solid.system.entity.SysTenantPackageMenu;
import io.github.atengk.solid.system.mapper.SysMenuMapper;
import io.github.atengk.solid.system.mapper.SysRoleMenuMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMenuMapper;
import io.github.atengk.solid.system.request.MenuQueryRequest;
import io.github.atengk.solid.system.request.MenuSaveRequest;
import io.github.atengk.solid.system.support.MenuTreeSupport;
import io.github.atengk.solid.system.support.MenuValidationSupport;
import io.github.atengk.solid.system.support.MenuValidationSupport.NormalizedMenuPayload;
import io.github.atengk.solid.system.support.SystemContextSupport;
import io.github.atengk.solid.system.vo.MenuTreeVO;
import io.github.atengk.solid.system.vo.MenuVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 平台菜单与按钮权限树管理服务，严格锁定平台默认租户 {@code 0} 维护，校验 {@code M/C/F} 拓扑约束与字段规则，
 * 并在事务提交后全量清理权限缓存。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Service
@RequiredArgsConstructor
public class SysMenuService {

    private final SystemContextSupport contextSupport;
    private final SysMenuMapper sysMenuMapper;
    private final SysRoleMenuMapper sysRoleMenuMapper;
    private final SysTenantPackageMenuMapper sysTenantPackageMenuMapper;
    private final PermissionCacheService permissionCacheService;

    /**
     * 查询平台菜单全树（仅允许平台默认租户 0 访问）。
     *
     * @param query 检索条件
     * @return 菜单树列表
     */
    public List<MenuTreeVO> listMenuTree(MenuQueryRequest query) {
        contextSupport.requirePlatformTenantId();
        List<SysMenu> allMenus = loadAllMenusOrdered();
        if (allMenus.isEmpty()) {
            return List.of();
        }
        MenuQueryRequest safeQuery = query != null ? query : new MenuQueryRequest();
        String title = SystemContextSupport.trimToNull(safeQuery.getTitle());
        Integer status = safeQuery.getStatus();
        Boolean platformOnly = safeQuery.getPlatformOnly();
        boolean hasFilter = title != null || status != null || platformOnly != null;

        return MenuTreeSupport.buildTreeWithAncestorRetention(
                allMenus,
                hasFilter,
                menu -> matchesQuery(menu, title, status, platformOnly)
        );
    }

    /**
     * 查询指定菜单节点详情（仅允许平台默认租户 0 访问）。
     *
     * @param id 菜单主键
     * @return 菜单详情
     */
    public MenuVO getDetail(Long id) {
        contextSupport.requirePlatformTenantId();
        return MenuTreeSupport.toMenuVO(requireMenuExists(id));
    }

    /**
     * 新增菜单或按钮权限，严格校验 M/C/F 节点拓扑与字段边界，并在事务提交后全量清理权限缓存。
     *
     * @param request 菜单保存请求
     * @return 新建菜单主键
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createMenu(MenuSaveRequest request) {
        contextSupport.requirePlatformTenantId();
        if (request == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "菜单参数不能为空");
        }
        MenuTypeEnum nodeType = MenuValidationSupport.parseRequiredMenuType(request.getMenuType());
        SysMenu parent = resolveAndCheckParent(request.getParentId(), nodeType);
        NormalizedMenuPayload payload = MenuValidationSupport.normalizeAndValidate(
                null, request, nodeType, parent, name -> isRouteNameDuplicate(null, name)
        );

        SysMenu entity = new SysMenu();
        MenuValidationSupport.applyPayload(entity, payload);
        try {
            TenantContextHolder.runWithoutTenant(() -> sysMenuMapper.insert(entity));
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "路由组件名称已存在", ex);
        }
        TransactionUtil.executeAfterCommit(permissionCacheService::clearAll);
        return entity.getId();
    }

    /**
     * 修改菜单或按钮权限，校验防自环与节点降级约束，并在事务提交后全量清理权限缓存。
     *
     * @param request 菜单修改请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateMenu(MenuSaveRequest request) {
        contextSupport.requirePlatformTenantId();
        if (request == null || request.getId() == null || request.getId() <= 0L) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "修改菜单必须传入有效主键");
        }
        SysMenu existing = requireMenuExists(request.getId());
        MenuTypeEnum targetType = MenuValidationSupport.parseRequiredMenuType(request.getMenuType());

        // 1. 校验父节点挂接、防自环迁移及子节点降级冲突
        SysMenu parent = resolveAndCheckParent(request.getParentId(), targetType);
        List<SysMenu> allMenus = loadAllMenusOrdered();
        MenuValidationSupport.assertUpdateTopologySafe(existing, request.getParentId(), targetType, allMenus);
        NormalizedMenuPayload payload = MenuValidationSupport.normalizeAndValidate(
                existing, request, targetType, parent, name -> isRouteNameDuplicate(existing.getId(), name)
        );

        LambdaUpdateWrapper<SysMenu> wrapper = Wrappers.<SysMenu>lambdaUpdate()
                .eq(SysMenu::getId, existing.getId())
                .set(SysMenu::getParentId, payload.parentId())
                .set(SysMenu::getMenuType, payload.menuType())
                .set(SysMenu::getTitle, payload.title())
                .set(SysMenu::getName, payload.name())
                .set(SysMenu::getPath, payload.path())
                .set(SysMenu::getComponent, payload.component())
                .set(SysMenu::getRedirect, payload.redirect())
                .set(SysMenu::getPerms, payload.perms())
                .set(SysMenu::getIcon, payload.icon())
                .set(SysMenu::getSort, payload.sort())
                .set(SysMenu::getVisible, payload.visible())
                .set(SysMenu::getStatus, payload.status())
                .set(SysMenu::getKeepAlive, payload.keepAlive())
                .set(SysMenu::getIsExternal, payload.isExternal())
                .set(SysMenu::getPlatformOnly, payload.platformOnly());
        try {
            TenantContextHolder.runWithoutTenant(() -> sysMenuMapper.update(new SysMenu(), wrapper));
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "路由组件名称已存在", ex);
        }

        // 2. 当节点收紧为平台专属时，级联收紧全部子孙并清理历史分发至套餐与普通租户角色的残留绑定
        if (!Boolean.TRUE.equals(existing.getPlatformOnly()) && payload.platformOnly()) {
            Set<Long> subtreeIds = MenuTreeSupport.collectSubtreeIds(existing.getId(), allMenus);
            pruneNonPlatformMenuBindings(subtreeIds);
        }
        TransactionUtil.executeAfterCommit(permissionCacheService::clearAll);
    }

    /**
     * 切换菜单启停状态，并在事务提交后全量清理权限缓存。
     *
     * @param id     菜单主键
     * @param status 目标状态（0=正常，1=停用）
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateMenuStatus(Long id, Integer status) {
        contextSupport.requirePlatformTenantId();
        Integer validStatus = SystemContextSupport.requireValidStatus(status);
        SysMenu existing = requireMenuExists(id);

        TenantContextHolder.runWithoutTenant(() -> sysMenuMapper.update(
                new SysMenu(),
                Wrappers.<SysMenu>lambdaUpdate()
                        .eq(SysMenu::getId, existing.getId())
                        .set(SysMenu::getStatus, validStatus)
        ));
        TransactionUtil.executeAfterCommit(permissionCacheService::clearAll);
    }

    /**
     * 删除菜单或按钮权限；存在子节点或角色/套餐引用时阻断抛出 {@code 102005}，提交后清理权限缓存。
     *
     * @param id 菜单主键
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteMenu(Long id) {
        contextSupport.requirePlatformTenantId();
        SysMenu existing = requireMenuExists(id);

        Long childCount = TenantContextHolder.runWithoutTenant(() -> sysMenuMapper.selectCount(
                Wrappers.<SysMenu>lambdaQuery().eq(SysMenu::getParentId, existing.getId())
        ));
        if (childCount != null && childCount > 0L) {
            throw new BusinessException(SystemResultCode.RESOURCE_IN_USE, "存在下级菜单或按钮子节点，禁止删除");
        }
        long packageRefs = TenantContextHolder.runWithoutTenant(
                () -> sysTenantPackageMenuMapper.countActivePackageReferencesByMenuId(existing.getId())
        );
        if (packageRefs > 0L) {
            throw new BusinessException(
                    SystemResultCode.RESOURCE_IN_USE,
                    "该菜单已被 " + packageRefs + " 个租户套餐引用，请先解除套餐关联后再删除"
            );
        }
        long roleRefs = TenantContextHolder.runWithoutTenant(
                () -> sysRoleMenuMapper.countActiveRoleReferencesByMenuId(existing.getId())
        );
        if (roleRefs > 0L) {
            throw new BusinessException(
                    SystemResultCode.RESOURCE_IN_USE,
                    "该菜单已被 " + roleRefs + " 个角色引用，请先解除角色授权后再删除"
            );
        }

        TenantContextHolder.runWithoutTenant(() -> {
            sysTenantPackageMenuMapper.delete(
                    Wrappers.<SysTenantPackageMenu>lambdaQuery().eq(SysTenantPackageMenu::getMenuId, existing.getId())
            );
            sysRoleMenuMapper.delete(
                    Wrappers.<SysRoleMenu>lambdaQuery().eq(SysRoleMenu::getMenuId, existing.getId())
            );
            sysMenuMapper.deleteById(existing.getId());
        });
        TransactionUtil.executeAfterCommit(permissionCacheService::clearAll);
    }

    private List<SysMenu> loadAllMenusOrdered() {
        List<SysMenu> menus = TenantContextHolder.runWithoutTenant(() -> sysMenuMapper.selectList(
                Wrappers.<SysMenu>lambdaQuery()
                        .orderByAsc(SysMenu::getSort)
                        .orderByAsc(SysMenu::getId)
        ));
        return SystemContextSupport.safeList(menus);
    }

    private SysMenu requireMenuExists(Long id) {
        if (id == null || id <= 0L) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "菜单主键无效");
        }
        SysMenu menu = TenantContextHolder.runWithoutTenant(() -> sysMenuMapper.selectById(id));
        if (menu == null) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "目标菜单节点不存在");
        }
        return menu;
    }

    private SysMenu resolveAndCheckParent(Long parentId, MenuTypeEnum targetType) {
        if (parentId == null || parentId < 0L) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "上级菜单标识无效");
        }
        SysMenu parent = parentId == 0L ? null
                : TenantContextHolder.runWithoutTenant(() -> sysMenuMapper.selectById(parentId));
        if (parentId > 0L && parent == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "所选上级菜单不存在");
        }
        MenuValidationSupport.assertParentTypeCompatible(parent, targetType);
        return parent;
    }

    private boolean isRouteNameDuplicate(Long selfId, String routeName) {
        Long count = TenantContextHolder.runWithoutTenant(() -> sysMenuMapper.selectCount(
                Wrappers.<SysMenu>lambdaQuery()
                        .eq(SysMenu::getName, routeName)
                        .ne(selfId != null, SysMenu::getId, selfId)
        ));
        return count != null && count > 0L;
    }

    private void pruneNonPlatformMenuBindings(Set<Long> subtreeIds) {
        if (subtreeIds == null || subtreeIds.isEmpty()) {
            return;
        }
        TenantContextHolder.runWithoutTenant(() -> {
            sysMenuMapper.update(
                    new SysMenu(),
                    Wrappers.<SysMenu>lambdaUpdate()
                            .in(SysMenu::getId, subtreeIds)
                            .set(SysMenu::getPlatformOnly, Boolean.TRUE)
            );
            sysTenantPackageMenuMapper.delete(
                    Wrappers.<SysTenantPackageMenu>lambdaQuery().in(SysTenantPackageMenu::getMenuId, subtreeIds)
            );
            sysRoleMenuMapper.delete(
                    Wrappers.<SysRoleMenu>lambdaQuery()
                            .in(SysRoleMenu::getMenuId, subtreeIds)
                            .ne(SysRoleMenu::getTenantId, SolidConstants.PLATFORM_TENANT_ID)
            );
        });
    }

    private static boolean matchesQuery(SysMenu menu, String title, Integer status, Boolean platformOnly) {
        if (title != null && (!StringUtils.hasText(menu.getTitle())
                || !menu.getTitle().toLowerCase(Locale.ROOT).contains(title.toLowerCase(Locale.ROOT)))) {
            return false;
        }
        if (status != null && !Objects.equals(menu.getStatus(), status)) {
            return false;
        }
        return platformOnly == null || Objects.equals(Boolean.TRUE.equals(menu.getPlatformOnly()), platformOnly);
    }
}

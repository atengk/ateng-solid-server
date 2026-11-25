package io.github.atengk.solid.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.TransactionUtil;
import io.github.atengk.boot.satoken.cache.PermissionCacheService;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.enums.MenuTypeEnum;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysMenu;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysTenantPackage;
import io.github.atengk.solid.system.entity.SysTenantPackageMenu;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysMenuMapper;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMenuMapper;
import io.github.atengk.solid.system.support.MenuTreeSupport;
import io.github.atengk.solid.system.vo.MenuTreeVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色授权边界校验、套餐可授权菜单树装配及角色/用户权限缓存精准淘汰协作者。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Component
@RequiredArgsConstructor
public class RoleAuthorizationSupport {

    private final SysMenuMapper sysMenuMapper;
    private final SysDeptMapper sysDeptMapper;
    private final SysTenantMapper sysTenantMapper;
    private final SysTenantPackageMapper sysTenantPackageMapper;
    private final SysTenantPackageMenuMapper sysTenantPackageMenuMapper;
    private final PermissionCacheService permissionCacheService;

    /**
     * 获取指定工作租户在角色表单中允许勾选的启用菜单树。
     *
     * @param tenantId           工作租户主键
     * @param multiTenantEnabled 是否开启多租户模式
     * @return 可授权菜单树集合
     */
    public List<MenuTreeVO> buildGrantableMenuTree(Long tenantId, boolean multiTenantEnabled) {
        boolean platformView = SolidConstants.PLATFORM_TENANT_ID.equals(tenantId);
        Set<Long> packageMenuIds = platformView ? Collections.emptySet() : loadActivePackageMenuIds(tenantId);
        if (!platformView && packageMenuIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<SysMenu> activeMenus = loadActiveMenusOrdered();
        if (activeMenus.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, List<SysMenu>> childrenMap = MenuTreeSupport.groupByParentId(activeMenus);
        return buildGrantableSubtree(0L, childrenMap, packageMenuIds, platformView, multiTenantEnabled);
    }

    /**
     * 校验并规范化角色待绑定菜单主键列表；普通租户严格校验套餐范围与 {@code platform_only=false} 边界，
     * 并与半选套餐模式（{@code menu_check_strictly=0}）下角色树回传的父目录半选节点兼容。
     *
     * @param tenantId           工作租户主键
     * @param multiTenantEnabled 是否开启多租户模式
     * @param requestedMenuIds   前端提交菜单主键列表
     * @return 去重并校验通过的有效菜单主键列表
     */
    public List<Long> validateAndNormalizeMenuIds(
            Long tenantId,
            boolean multiTenantEnabled,
            List<Long> requestedMenuIds
    ) {
        List<Long> distinctIds = deduplicatePositiveIds(requestedMenuIds, "存在无效的菜单主键标识");
        if (distinctIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<SysMenu> menus = TenantContextHolder.runWithoutTenant(() -> sysMenuMapper.selectList(
                Wrappers.<SysMenu>lambdaQuery().in(SysMenu::getId, distinctIds)
        ));
        if (CollectionUtils.isEmpty(menus) || menus.size() != distinctIds.size()) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "存在无效或已删除的菜单节点");
        }

        boolean platformView = SolidConstants.PLATFORM_TENANT_ID.equals(tenantId);
        Set<Long> packageMenuIds = platformView ? Collections.emptySet() : loadActivePackageMenuIds(tenantId);
        Set<Long> requestedSet = new HashSet<>(distinctIds);
        Map<Long, List<SysMenu>> activeChildrenMap = null;

        for (SysMenu menu : menus) {
            assertMenuGrantEligible(menu, platformView, multiTenantEnabled);
            if (platformView || packageMenuIds.contains(menu.getId())) {
                continue;
            }
            // 半选套餐模式（menu_check_strictly=0）下，父目录 M 若未入套餐表但旗下存在本次已授权的套餐子孙，同样予以放行
            if (MenuTypeEnum.fromCode(menu.getMenuType()) == MenuTypeEnum.DIRECTORY) {
                if (activeChildrenMap == null) {
                    activeChildrenMap = MenuTreeSupport.groupByParentId(loadActiveMenusOrdered());
                }
                if (hasAuthorizedPackageDescendant(menu.getId(), activeChildrenMap, requestedSet, packageMenuIds, multiTenantEnabled)) {
                    continue;
                }
            }
            throw new BusinessException(
                    SystemResultCode.TENANT_QUOTA_OR_SCOPE_EXCEEDED,
                    "超出当前租户套餐授权菜单范围：" + menu.getTitle()
            );
        }
        return distinctIds;
    }

    /**
     * 校验自定数据权限（CUSTOM）部门主键列表非空且全部归属当前租户且未删除。
     *
     * @param tenantId         工作租户主键
     * @param requestedDeptIds 前端提交部门主键列表
     * @return 去重后的有效部门主键列表
     */
    public List<Long> validateAndNormalizeCustomDeptIds(Long tenantId, List<Long> requestedDeptIds) {
        List<Long> distinctIds = deduplicatePositiveIds(requestedDeptIds, "存在无效的部门主键标识");
        if (distinctIds.isEmpty()) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "自定数据权限至少需要勾选 1 个部门");
        }

        List<SysDept> depts = TenantContextHolder.runWithTenant(tenantId, () -> sysDeptMapper.selectList(
                Wrappers.<SysDept>lambdaQuery()
                        .select(SysDept::getId)
                        .eq(SysDept::getTenantId, tenantId)
                        .in(SysDept::getId, distinctIds)
        ));
        if (CollectionUtils.isEmpty(depts) || depts.size() != distinctIds.size()) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "存在无效或跨租户的部门主键");
        }
        return distinctIds;
    }

    /**
     * 在事务提交后精准清理指定租户下角色权限缓存及受 {@code roleKey} 变更影响用户的角色列表缓存（回滚不触发）。
     *
     * @param tenantId        工作租户主键
     * @param roleKeys        待清理的角色编码集合（去重）
     * @param affectedUserIds 角色编码发生变更时需联动清理的用户主键集合（可为空）
     */
    public void evictRoleAndUserCacheAfterCommit(
            Long tenantId,
            Collection<String> roleKeys,
            Collection<Long> affectedUserIds
    ) {
        if (tenantId == null) {
            return;
        }
        Set<String> targetKeys = new LinkedHashSet<>();
        if (!CollectionUtils.isEmpty(roleKeys)) {
            for (String roleKey : roleKeys) {
                if (roleKey != null && !roleKey.isBlank()) {
                    targetKeys.add(roleKey.trim());
                }
            }
        }
        Set<Long> targetUserIds = new LinkedHashSet<>();
        if (!CollectionUtils.isEmpty(affectedUserIds)) {
            for (Long userId : affectedUserIds) {
                if (userId != null && userId > 0L) {
                    targetUserIds.add(userId);
                }
            }
        }
        if (targetKeys.isEmpty() && targetUserIds.isEmpty()) {
            return;
        }
        String tenantStr = String.valueOf(tenantId);
        TransactionUtil.executeAfterCommit(() -> {
            for (String roleKey : targetKeys) {
                permissionCacheService.clearRoleCache(SolidConstants.ADMIN_LOGIN_TYPE, tenantStr, roleKey);
            }
            for (Long userId : targetUserIds) {
                permissionCacheService.clearUserCache(
                        SolidConstants.ADMIN_LOGIN_TYPE, tenantStr, String.valueOf(userId)
                );
            }
        });
    }

    private List<SysMenu> loadActiveMenusOrdered() {
        List<SysMenu> menus = TenantContextHolder.runWithoutTenant(() -> sysMenuMapper.selectList(
                Wrappers.<SysMenu>lambdaQuery()
                        .eq(SysMenu::getStatus, CommonStatusEnum.NORMAL.getCode())
                        .orderByAsc(SysMenu::getSort)
                        .orderByAsc(SysMenu::getId)
        ));
        return CollectionUtils.isEmpty(menus) ? Collections.emptyList() : menus;
    }

    private Set<Long> loadActivePackageMenuIds(Long tenantId) {
        return TenantContextHolder.runWithoutTenant(() -> {
            SysTenant tenant = sysTenantMapper.selectOne(
                    Wrappers.<SysTenant>lambdaQuery()
                            .eq(SysTenant::getId, tenantId)
                            .eq(SysTenant::getStatus, CommonStatusEnum.NORMAL.getCode())
            );
            if (tenant == null || tenant.getPackageId() == null) {
                return Collections.emptySet();
            }
            if (tenant.getExpireTime() != null && !tenant.getExpireTime().isAfter(LocalDateTime.now())) {
                return Collections.emptySet();
            }
            // 停用套餐不影响存量已绑定租户继续维护其现有菜单权限
            SysTenantPackage pkg = sysTenantPackageMapper.selectById(tenant.getPackageId());
            if (pkg == null) {
                return Collections.emptySet();
            }
            List<SysTenantPackageMenu> mappings = sysTenantPackageMenuMapper.selectList(
                    Wrappers.<SysTenantPackageMenu>lambdaQuery()
                            .select(SysTenantPackageMenu::getMenuId)
                            .eq(SysTenantPackageMenu::getPackageId, pkg.getId())
            );
            if (CollectionUtils.isEmpty(mappings)) {
                return Collections.emptySet();
            }
            Set<Long> menuIds = new HashSet<>(mappings.size());
            for (SysTenantPackageMenu mapping : mappings) {
                if (mapping.getMenuId() != null) {
                    menuIds.add(mapping.getMenuId());
                }
            }
            return menuIds.isEmpty() ? Collections.emptySet() : menuIds;
        });
    }

    private List<MenuTreeVO> buildGrantableSubtree(
            Long parentId,
            Map<Long, List<SysMenu>> childrenMap,
            Set<Long> packageMenuIds,
            boolean platformView,
            boolean multiTenantEnabled
    ) {
        List<SysMenu> candidates = childrenMap.get(parentId);
        if (CollectionUtils.isEmpty(candidates)) {
            return Collections.emptyList();
        }
        List<MenuTreeVO> result = new ArrayList<>();
        for (SysMenu menu : candidates) {
            if (!isGrantableNode(menu, platformView, multiTenantEnabled)) {
                continue;
            }
            MenuTypeEnum nodeType = MenuTypeEnum.fromCode(menu.getMenuType());
            if (nodeType == null) {
                continue;
            }
            if (!platformView && nodeType != MenuTypeEnum.DIRECTORY && !packageMenuIds.contains(menu.getId())) {
                continue;
            }
            List<MenuTreeVO> children = buildGrantableSubtree(
                    menu.getId(), childrenMap, packageMenuIds, platformView, multiTenantEnabled
            );
            if (nodeType == MenuTypeEnum.DIRECTORY) {
                if (!children.isEmpty()) {
                    result.add(MenuTreeSupport.toMenuTreeVO(menu, children));
                }
                continue;
            }
            result.add(MenuTreeSupport.toMenuTreeVO(menu, children));
        }
        return result.isEmpty() ? Collections.emptyList() : result;
    }

    private boolean hasAuthorizedPackageDescendant(
            Long dirId,
            Map<Long, List<SysMenu>> activeChildrenMap,
            Set<Long> requestedSet,
            Set<Long> packageMenuIds,
            boolean multiTenantEnabled
    ) {
        List<SysMenu> children = activeChildrenMap.get(dirId);
        if (CollectionUtils.isEmpty(children)) {
            return false;
        }
        for (SysMenu child : children) {
            if (!isGrantableNode(child, false, multiTenantEnabled)) {
                continue;
            }
            MenuTypeEnum childType = MenuTypeEnum.fromCode(child.getMenuType());
            if (childType == null) {
                continue;
            }
            if (childType != MenuTypeEnum.DIRECTORY) {
                if (packageMenuIds.contains(child.getId()) && requestedSet.contains(child.getId())) {
                    return true;
                }
                if (hasAuthorizedPackageDescendant(
                        child.getId(), activeChildrenMap, requestedSet, packageMenuIds, multiTenantEnabled)) {
                    return true;
                }
            } else if (hasAuthorizedPackageDescendant(
                    child.getId(), activeChildrenMap, requestedSet, packageMenuIds, multiTenantEnabled)) {
                return true;
            }
        }
        return false;
    }

    private static void assertMenuGrantEligible(SysMenu menu, boolean platformView, boolean multiTenantEnabled) {
        if (!CommonStatusEnum.NORMAL.getCode().equals(menu.getStatus())) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "禁止授予已停用状态的菜单：" + menu.getTitle());
        }
        if (!multiTenantEnabled && MenuTreeSupport.isTenantGovernanceMenu(menu)) {
            throw new BusinessException(
                    SystemResultCode.TENANT_QUOTA_OR_SCOPE_EXCEEDED,
                    "单租户模式下禁止授予多租户治理菜单：" + menu.getTitle()
            );
        }
        if (!platformView && Boolean.TRUE.equals(menu.getPlatformOnly())) {
            throw new BusinessException(
                    SystemResultCode.TENANT_QUOTA_OR_SCOPE_EXCEEDED,
                    "普通租户禁止越权授予平台专属菜单：" + menu.getTitle()
            );
        }
    }

    private static boolean isGrantableNode(SysMenu menu, boolean platformView, boolean multiTenantEnabled) {
        if (!platformView && Boolean.TRUE.equals(menu.getPlatformOnly())) {
            return false;
        }
        return multiTenantEnabled || !MenuTreeSupport.isTenantGovernanceMenu(menu);
    }

    private static List<Long> deduplicatePositiveIds(List<Long> rawIds, String invalidMsg) {
        if (CollectionUtils.isEmpty(rawIds)) {
            return Collections.emptyList();
        }
        Set<Long> unique = new LinkedHashSet<>(rawIds.size());
        for (Long id : rawIds) {
            if (id == null || id <= 0L) {
                throw new BusinessException(CommonBizResultCode.PARAM_INVALID, invalidMsg);
            }
            unique.add(id);
        }
        return new ArrayList<>(unique);
    }
}

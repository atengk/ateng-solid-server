package io.github.atengk.solid.system.service.remote;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.properties.SolidTenantProperties;
import io.github.atengk.solid.system.api.dto.RouteMenuDTO;
import io.github.atengk.solid.system.api.dto.TenantAuthDTO;
import io.github.atengk.solid.system.api.enums.MenuTypeEnum;
import io.github.atengk.solid.system.api.service.RemotePermissionService;
import io.github.atengk.solid.system.api.service.RemoteTenantService;
import io.github.atengk.solid.system.entity.SysMenu;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysRoleMenu;
import io.github.atengk.solid.system.entity.SysTenantPackage;
import io.github.atengk.solid.system.entity.SysTenantPackageMenu;
import io.github.atengk.solid.system.mapper.SysMenuMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysRoleMenuMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMenuMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 角色、权限标识与动态路由菜单跨模块远程服务实现，完整落实平台超管特权、跨租户纵深防越权、停用子树级联剪枝及空目录裁剪。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Service
@RequiredArgsConstructor
public class RemotePermissionServiceImpl implements RemotePermissionService {

    /**
     * V1.0.1 确定性种子约定的多租户治理顶级目录主键（与 {@code platform_only=1} 配合控制显隐）
     */
    private static final long TENANT_GOVERNANCE_DIR_ID = 3000L;
    private static final String TENANT_ROOT_PATH = "/tenant";
    private static final Set<String> TENANT_ROUTE_NAMES = Set.of("SysTenant", "SysTenantPackage");
    private static final Set<String> TENANT_ROUTE_PATHS = Set.of("/system/tenant", "/system/tenant-package");
    private static final String TENANT_PERMS_PREFIX = "system:tenant";

    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysRoleMenuMapper sysRoleMenuMapper;
    private final SysMenuMapper sysMenuMapper;
    private final SysTenantPackageMapper sysTenantPackageMapper;
    private final SysTenantPackageMenuMapper sysTenantPackageMenuMapper;
    private final RemoteTenantService remoteTenantService;
    private final SolidTenantProperties tenantProperties;

    @Override
    public List<String> getRoleKeysByUserId(Long tenantId, Long userId) {
        if (tenantId == null || userId == null) {
            return Collections.emptyList();
        }
        List<SysRole> roles = TenantContextHolder.runWithTenant(
                tenantId,
                () -> sysUserRoleMapper.selectActiveRolesByUserId(tenantId, userId)
        );
        if (CollectionUtils.isEmpty(roles)) {
            return Collections.emptyList();
        }
        List<String> keys = roles.stream()
                .map(SysRole::getRoleKey)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        return keys.isEmpty() ? Collections.emptyList() : keys;
    }

    @Override
    public List<String> getPermissionsByRoleKey(Long tenantId, String roleKey) {
        if (tenantId == null || !StringUtils.hasText(roleKey)) {
            return Collections.emptyList();
        }
        String normalizedRoleKey = roleKey.trim();
        SysRole role = findActiveRoleByKey(tenantId, normalizedRoleKey);
        if (role == null) {
            return Collections.emptyList();
        }
        if (SolidConstants.PLATFORM_TENANT_ID.equals(tenantId)
                && SolidConstants.SUPER_ADMIN_ROLE.equals(normalizedRoleKey)) {
            return List.of("*:*:*");
        }

        Set<Long> roleMenuIds = loadRoleMenuIds(tenantId, List.of(role.getId()));
        if (roleMenuIds.isEmpty()) {
            return Collections.emptyList();
        }

        boolean platformView = SolidConstants.PLATFORM_TENANT_ID.equals(tenantId);
        Set<Long> packageMenuIds = platformView ? Collections.emptySet() : loadActiveTenantPackageMenuIds(tenantId);
        if (!platformView && packageMenuIds.isEmpty()) {
            return Collections.emptyList();
        }

        boolean tenantEnabled = tenantProperties != null && tenantProperties.isEnabled();
        MenuFilterContext ctx = new MenuFilterContext(
                loadAllMenusGroupedByParent(),
                roleMenuIds,
                packageMenuIds,
                false,
                platformView,
                tenantEnabled
        );
        List<String> permissions = new ArrayList<>();
        collectReachablePermissions(0L, ctx, permissions);
        return permissions.isEmpty() ? Collections.emptyList() : permissions.stream().distinct().toList();
    }

    @Override
    public List<String> getDirectPermissionsByUserId(Long tenantId, Long userId) {
        return Collections.emptyList();
    }

    @Override
    public List<RouteMenuDTO> getRouteMenusByUserId(
            Long ownerTenantId,
            Long effectiveTenantId,
            Long userId,
            boolean superAdmin,
            boolean tenantEnabled
    ) {
        if (effectiveTenantId == null || userId == null) {
            return Collections.emptyList();
        }
        if (!superAdmin && (ownerTenantId == null || !Objects.equals(ownerTenantId, effectiveTenantId))) {
            return Collections.emptyList();
        }

        boolean platformView = SolidConstants.PLATFORM_TENANT_ID.equals(effectiveTenantId);
        Set<Long> packageMenuIds = platformView ? Collections.emptySet() : loadActiveTenantPackageMenuIds(effectiveTenantId);
        if (!platformView && packageMenuIds.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> roleMenuIds = superAdmin ? Collections.emptySet() : loadUserActiveRoleMenuIds(effectiveTenantId, userId);
        if (!superAdmin && roleMenuIds.isEmpty()) {
            return Collections.emptyList();
        }

        MenuFilterContext ctx = new MenuFilterContext(
                loadAllMenusGroupedByParent(),
                roleMenuIds,
                packageMenuIds,
                superAdmin,
                platformView,
                tenantEnabled
        );
        return buildFilteredSubtree(0L, ctx);
    }

    private SysRole findActiveRoleByKey(Long tenantId, String roleKey) {
        return TenantContextHolder.runWithTenant(tenantId, () -> sysRoleMapper.selectOne(
                Wrappers.<SysRole>lambdaQuery()
                        .eq(SysRole::getTenantId, tenantId)
                        .eq(SysRole::getRoleKey, roleKey)
                        .eq(SysRole::getStatus, CommonStatusEnum.NORMAL.getCode())
        ));
    }

    private Set<Long> loadUserActiveRoleMenuIds(Long tenantId, Long userId) {
        List<SysRole> activeRoles = TenantContextHolder.runWithTenant(
                tenantId,
                () -> sysUserRoleMapper.selectActiveRolesByUserId(tenantId, userId)
        );
        if (CollectionUtils.isEmpty(activeRoles)) {
            return Collections.emptySet();
        }
        List<Long> roleIds = activeRoles.stream().map(SysRole::getId).filter(Objects::nonNull).toList();
        return roleIds.isEmpty() ? Collections.emptySet() : loadRoleMenuIds(tenantId, roleIds);
    }

    private Set<Long> loadRoleMenuIds(Long tenantId, List<Long> roleIds) {
        List<SysRoleMenu> roleMenus = TenantContextHolder.runWithTenant(tenantId, () -> sysRoleMenuMapper.selectList(
                Wrappers.<SysRoleMenu>lambdaQuery()
                        .select(SysRoleMenu::getMenuId)
                        .eq(SysRoleMenu::getTenantId, tenantId)
                        .in(SysRoleMenu::getRoleId, roleIds)
        ));
        if (CollectionUtils.isEmpty(roleMenus)) {
            return Collections.emptySet();
        }
        Set<Long> menuIds = new HashSet<>(roleMenus.size());
        for (SysRoleMenu rm : roleMenus) {
            if (rm.getMenuId() != null) {
                menuIds.add(rm.getMenuId());
            }
        }
        return menuIds.isEmpty() ? Collections.emptySet() : menuIds;
    }

    private Set<Long> loadActiveTenantPackageMenuIds(Long tenantId) {
        TenantAuthDTO tenant = remoteTenantService.getTenantById(tenantId);
        if (tenant == null || !tenant.isAvailableAt(LocalDateTime.now()) || tenant.getPackageId() == null) {
            return Collections.emptySet();
        }
        return TenantContextHolder.runWithoutTenant(() -> {
            SysTenantPackage pkg = sysTenantPackageMapper.selectOne(
                    Wrappers.<SysTenantPackage>lambdaQuery()
                            .eq(SysTenantPackage::getId, tenant.getPackageId())
                            .eq(SysTenantPackage::getStatus, CommonStatusEnum.NORMAL.getCode())
            );
            if (pkg == null) {
                return Collections.emptySet();
            }
            List<SysTenantPackageMenu> packageMenus = sysTenantPackageMenuMapper.selectList(
                    Wrappers.<SysTenantPackageMenu>lambdaQuery()
                            .select(SysTenantPackageMenu::getMenuId)
                            .eq(SysTenantPackageMenu::getPackageId, pkg.getId())
            );
            if (CollectionUtils.isEmpty(packageMenus)) {
                return Collections.emptySet();
            }
            Set<Long> menuIds = new HashSet<>(packageMenus.size());
            for (SysTenantPackageMenu pm : packageMenus) {
                if (pm.getMenuId() != null) {
                    menuIds.add(pm.getMenuId());
                }
            }
            return menuIds.isEmpty() ? Collections.emptySet() : menuIds;
        });
    }

    private Map<Long, List<SysMenu>> loadAllMenusGroupedByParent() {
        List<SysMenu> allMenus = TenantContextHolder.runWithoutTenant(() -> sysMenuMapper.selectList(
                Wrappers.<SysMenu>lambdaQuery()
                        .orderByAsc(SysMenu::getSort)
                        .orderByAsc(SysMenu::getId)
        ));
        if (CollectionUtils.isEmpty(allMenus)) {
            return Collections.emptyMap();
        }
        Map<Long, List<SysMenu>> grouped = new LinkedHashMap<>();
        for (SysMenu menu : allMenus) {
            Long parentId = menu.getParentId() != null ? menu.getParentId() : 0L;
            grouped.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(menu);
        }
        return grouped;
    }

    private List<RouteMenuDTO> buildFilteredSubtree(Long parentId, MenuFilterContext ctx) {
        List<SysMenu> candidates = ctx.childrenMap().get(parentId);
        if (CollectionUtils.isEmpty(candidates)) {
            return Collections.emptyList();
        }
        List<RouteMenuDTO> result = new ArrayList<>();
        for (SysMenu menu : candidates) {
            MenuTypeEnum nodeType = MenuTypeEnum.fromCode(menu.getMenuType());
            if (!ctx.isReachable(menu) || nodeType == null || nodeType == MenuTypeEnum.BUTTON) {
                continue;
            }
            List<RouteMenuDTO> children = buildFilteredSubtree(menu.getId(), ctx);
            if (nodeType == MenuTypeEnum.DIRECTORY) {
                if (!children.isEmpty()) {
                    result.add(toRouteMenuDTO(menu, children));
                }
                continue;
            }
            if (nodeType == MenuTypeEnum.MENU && ctx.isMenuPermitted(menu.getId())) {
                result.add(toRouteMenuDTO(menu, children));
            }
        }
        return result.isEmpty() ? Collections.emptyList() : result;
    }

    private void collectReachablePermissions(Long parentId, MenuFilterContext ctx, List<String> collector) {
        List<SysMenu> candidates = ctx.childrenMap().get(parentId);
        if (CollectionUtils.isEmpty(candidates)) {
            return;
        }
        for (SysMenu menu : candidates) {
            MenuTypeEnum nodeType = MenuTypeEnum.fromCode(menu.getMenuType());
            if (!ctx.isReachable(menu) || nodeType == null) {
                continue;
            }
            boolean inPackage = ctx.isInPackage(menu.getId());
            // 目录 M 在半选套餐模式（menu_check_strictly=0）下可未入套餐表但允许下探；页面 C 超出套餐则直接截断其子按钮 F
            if (nodeType != MenuTypeEnum.DIRECTORY && !inPackage) {
                continue;
            }
            if (inPackage && ctx.roleMenuIds().contains(menu.getId()) && StringUtils.hasText(menu.getPerms())) {
                collector.add(menu.getPerms().trim());
            }
            collectReachablePermissions(menu.getId(), ctx, collector);
        }
    }

    private static boolean isTenantGovernanceMenu(SysMenu menu) {
        if (Objects.equals(menu.getId(), TENANT_GOVERNANCE_DIR_ID)) {
            return true;
        }
        String path = menu.getPath();
        if (TENANT_ROOT_PATH.equals(path) || (path != null && TENANT_ROUTE_PATHS.contains(path))) {
            return true;
        }
        String name = menu.getName();
        if (name != null && TENANT_ROUTE_NAMES.contains(name)) {
            return true;
        }
        return StringUtils.hasText(menu.getPerms()) && menu.getPerms().trim().startsWith(TENANT_PERMS_PREFIX);
    }

    private static RouteMenuDTO toRouteMenuDTO(SysMenu menu, List<RouteMenuDTO> children) {
        return RouteMenuDTO.builder()
                .id(menu.getId())
                .parentId(menu.getParentId())
                .menuType(menu.getMenuType())
                .title(menu.getTitle())
                .name(menu.getName())
                .path(menu.getPath())
                .component(menu.getComponent())
                .redirect(menu.getRedirect())
                .icon(menu.getIcon())
                .sort(menu.getSort())
                .visible(menu.getVisible())
                .keepAlive(menu.getKeepAlive())
                .isExternal(menu.getIsExternal())
                .platformOnly(menu.getPlatformOnly())
                .children(CollectionUtils.isEmpty(children) ? Collections.emptyList() : children)
                .build();
    }

    private record MenuFilterContext(
            Map<Long, List<SysMenu>> childrenMap,
            Set<Long> roleMenuIds,
            Set<Long> packageMenuIds,
            boolean superAdmin,
            boolean platformView,
            boolean tenantEnabled
    ) {
        private boolean isReachable(SysMenu menu) {
            if (!CommonStatusEnum.NORMAL.getCode().equals(menu.getStatus())) {
                return false;
            }
            if (!platformView && Boolean.TRUE.equals(menu.getPlatformOnly())) {
                return false;
            }
            return tenantEnabled || !isTenantGovernanceMenu(menu);
        }

        private boolean isInPackage(Long menuId) {
            return platformView || packageMenuIds.contains(menuId);
        }

        private boolean isMenuPermitted(Long menuId) {
            boolean authorized = superAdmin || roleMenuIds.contains(menuId);
            return authorized && isInPackage(menuId);
        }
    }
}

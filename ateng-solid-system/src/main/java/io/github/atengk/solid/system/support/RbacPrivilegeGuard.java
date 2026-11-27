package io.github.atengk.solid.system.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.properties.SolidTenantProperties;
import io.github.atengk.solid.system.api.enums.DataScopeType;
import io.github.atengk.solid.system.api.enums.MenuTypeEnum;
import io.github.atengk.solid.system.entity.SysMenu;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysRoleDept;
import io.github.atengk.solid.system.entity.SysRoleMenu;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysTenantPackage;
import io.github.atengk.solid.system.entity.SysTenantPackageMenu;
import io.github.atengk.solid.system.entity.SysUserRole;
import io.github.atengk.solid.system.mapper.SysMenuMapper;
import io.github.atengk.solid.system.mapper.SysRoleDeptMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysRoleMenuMapper;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMenuMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import io.github.atengk.solid.system.security.SolidDataPermissionHandler;
import io.github.atengk.solid.system.security.SolidDataPermissionHandler.ScopeResolution;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * RBAC 权限子集与数据范围防提权守卫（覆盖角色维护、用户写操作与批量导入，消除所有跨权与同级越权路径）。
 *
 * @author Ateng
 * @since 2026-11-27
 */
@Component
@RequiredArgsConstructor
public class RbacPrivilegeGuard {

    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysRoleMenuMapper sysRoleMenuMapper;
    private final SysRoleDeptMapper sysRoleDeptMapper;
    private final SysMenuMapper sysMenuMapper;
    private final SysTenantMapper sysTenantMapper;
    private final SysTenantPackageMapper sysTenantPackageMapper;
    private final SysTenantPackageMenuMapper sysTenantPackageMenuMapper;
    private final SolidDataPermissionHandler dataPermissionHandler;
    private final SolidTenantProperties tenantProperties;

    /**
     * 解析当前操作人在指定工作租户下的有效菜单权限、持有角色及 5 级数据范围能力快照。
     *
     * @param tenantId    工作租户主键
     * @param actorUserId 当前操作人用户主键（无用户上下文的程序化测试或超级管理员视角返回非受限快照）
     * @return 只读操作人权限能力快照
     */
    public ActorPrivilegeContext resolveActorContext(Long tenantId, Long actorUserId) {
        if (tenantId == null
                || actorUserId == null
                || DataPermissionContextHolder.isIgnore()
                || DataPermissionContextHolder.isSuperAdmin()
                || RoleViewSupport.isPlatformSuperAdminView(tenantId)) {
            return ActorPrivilegeContext.UNRESTRICTED;
        }
        return DataPermissionContextHolder.runWithoutPermission(
                () -> TenantContextHolder.runWithTenant(tenantId, () -> loadRestrictedActorContext(tenantId, actorUserId))
        );
    }

    /**
     * 校验待绑定的角色菜单集合是否完全落入操作人自身有效权限子集内。
     *
     * @param tenantId           工作租户主键
     * @param actor              操作人权限快照
     * @param requestedMenuIds   待绑定的规范化菜单主键集合
     */
    public void assertMenusWithinActorPrivilege(
            Long tenantId,
            ActorPrivilegeContext actor,
            Collection<Long> requestedMenuIds
    ) {
        if (actor == null || actor.unrestricted() || CollectionUtils.isEmpty(requestedMenuIds)) {
            return;
        }
        MenuGrantEnvironment env = loadMenuGrantEnvironment(tenantId);
        Set<Long> requestedSet = new HashSet<>(requestedMenuIds);
        for (Long menuId : requestedMenuIds) {
            SysMenu menu = env.menusById().get(menuId);
            if (menu == null) {
                throw new BusinessException(CommonResultCode.FORBIDDEN, "存在不可识别的菜单权限节点");
            }
            MenuTypeEnum nodeType = MenuTypeEnum.fromCode(menu.getMenuType());
            if (nodeType == MenuTypeEnum.DIRECTORY) {
                boolean allowedDirectory = actor.boundRawMenuIds().contains(menuId)
                        || hasDescendantInSet(menuId, env.childrenMap(), requestedSet, actor.effectiveNonDirMenuIds(), new HashSet<>());
                if (!allowedDirectory) {
                    throw new BusinessException(CommonResultCode.FORBIDDEN, "禁止授予超出自身菜单范围的目录节点：" + menu.getTitle());
                }
                continue;
            }
            if (!actor.effectiveNonDirMenuIds().contains(menuId)) {
                throw new BusinessException(CommonResultCode.FORBIDDEN, "禁止授予超出当前操作人自身权限范围的菜单：" + menu.getTitle());
            }
        }
    }

    /**
     * 校验操作人是否被允许变更、启停、调整数据范围或删除目标角色列表（禁止自改持有角色及高权/平级越权角色）。
     *
     * @param tenantId 工作租户主键
     * @param actor    操作人权限快照
     * @param roles    待操作的目标角色集合
     */
    public void assertActorDominatesRoles(Long tenantId, ActorPrivilegeContext actor, Collection<SysRole> roles) {
        if (actor == null || actor.unrestricted() || CollectionUtils.isEmpty(roles)) {
            return;
        }
        for (SysRole role : roles) {
            if (role != null && role.getId() != null && actor.boundRoleIds().contains(role.getId())) {
                throw new BusinessException(CommonResultCode.FORBIDDEN, "禁止修改、启停、调整范围或删除当前登录用户自身持有的角色");
            }
        }
        Set<Long> exceedingRoleIds = findPrivilegeExceededRoleIds(tenantId, actor, roles);
        if (!exceedingRoleIds.isEmpty()) {
            throw new BusinessException(CommonResultCode.FORBIDDEN, "目标角色包含当前操作人未持有的权限或更高数据范围，禁止操作");
        }
    }

    /**
     * 校验操作人是否被允许在用户新增/修改中授予目标角色主键集合。
     *
     * @param tenantId 工作租户主键
     * @param actor    操作人权限快照
     * @param roleIds  待分配角色主键集合
     */
    public void assertActorCanAssignRoles(Long tenantId, ActorPrivilegeContext actor, Collection<Long> roleIds) {
        if (actor == null || actor.unrestricted() || CollectionUtils.isEmpty(roleIds)) {
            return;
        }
        List<SysRole> roles = DataPermissionContextHolder.runWithoutPermission(() ->
                TenantContextHolder.runWithTenant(tenantId, () -> sysRoleMapper.selectList(
                        Wrappers.<SysRole>lambdaQuery()
                                .eq(SysRole::getTenantId, tenantId)
                                .in(SysRole::getId, roleIds)
                ))
        );
        Set<Long> exceedingRoleIds = findPrivilegeExceededRoleIds(tenantId, actor, roles);
        if (!exceedingRoleIds.isEmpty()) {
            throw new BusinessException(CommonResultCode.FORBIDDEN, "禁止为用户分配权限或数据范围高于当前操作人的角色");
        }
    }

    /**
     * 校验操作人是否在菜单权限及数据范围上完整覆盖指定目标用户集合（用于改/启停/重密/删他人的前置防越权）。
     *
     * @param tenantId      工作租户主键
     * @param actor         操作人权限快照
     * @param targetUserIds 待操作的其他目标用户主键集合
     */
    public void assertActorDominatesUsers(Long tenantId, ActorPrivilegeContext actor, Collection<Long> targetUserIds) {
        if (actor == null || actor.unrestricted() || CollectionUtils.isEmpty(targetUserIds)) {
            return;
        }
        List<SysUserRole> userRoles = DataPermissionContextHolder.runWithoutPermission(() ->
                TenantContextHolder.runWithTenant(tenantId, () -> sysUserRoleMapper.selectList(
                        Wrappers.<SysUserRole>lambdaQuery()
                                .eq(SysUserRole::getTenantId, tenantId)
                                .in(SysUserRole::getUserId, targetUserIds)
                ))
        );
        if (CollectionUtils.isEmpty(userRoles)) {
            return;
        }
        Set<Long> distinctRoleIds = new LinkedHashSet<>();
        for (SysUserRole ur : userRoles) {
            if (ur != null && ur.getRoleId() != null) {
                distinctRoleIds.add(ur.getRoleId());
            }
        }
        if (distinctRoleIds.isEmpty()) {
            return;
        }
        List<SysRole> activeTargetRoles = DataPermissionContextHolder.runWithoutPermission(() ->
                TenantContextHolder.runWithTenant(tenantId, () -> sysRoleMapper.selectList(
                        Wrappers.<SysRole>lambdaQuery()
                                .eq(SysRole::getTenantId, tenantId)
                                .eq(SysRole::getStatus, CommonStatusEnum.NORMAL.getCode())
                                .in(SysRole::getId, distinctRoleIds)
                ))
        );
        Set<Long> exceedingRoleIds = findPrivilegeExceededRoleIds(tenantId, actor, activeTargetRoles);
        if (!exceedingRoleIds.isEmpty()) {
            throw new BusinessException(CommonResultCode.FORBIDDEN, "目标用户持有高于当前操作人自身权限或数据范围的角色，禁止操作");
        }
    }

    /**
     * 校验目标数据范围及自定部门列表是否不高于操作人自身数据权限范围。
     *
     * @param actor         操作人权限快照
     * @param targetScope   目标数据范围枚举
     * @param customDeptIds 当目标为 {@code CUSTOM} 时的部门主键集合
     */
    public void assertDataScopeWithinActor(
            ActorPrivilegeContext actor,
            DataScopeType targetScope,
            Collection<Long> customDeptIds
    ) {
        if (actor == null || actor.unrestricted() || targetScope == null) {
            return;
        }
        if (!actor.dominatesDataScope(targetScope, customDeptIds)) {
            throw new BusinessException(CommonResultCode.FORBIDDEN, "目标数据权限范围或自定部门集合超出当前操作人自身权限");
        }
    }

    /**
     * 批量探测候选角色集合中权限或数据范围超出当前操作人边界的角色主键集合（零 N+1）。
     *
     * @param tenantId 工作租户主键
     * @param actor    操作人权限快照
     * @param roles    待校验的角色集合
     * @return 超出操作人边界的角色主键集合
     */
    public Set<Long> findPrivilegeExceededRoleIds(
            Long tenantId,
            ActorPrivilegeContext actor,
            Collection<SysRole> roles
    ) {
        if (actor == null || actor.unrestricted() || CollectionUtils.isEmpty(roles)) {
            return Collections.emptySet();
        }
        List<Long> allRoleIds = roles.stream()
                .filter(Objects::nonNull)
                .map(SysRole::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (allRoleIds.isEmpty()) {
            return Collections.emptySet();
        }

        return DataPermissionContextHolder.runWithoutPermission(() -> TenantContextHolder.runWithTenant(tenantId, () -> {
            Map<Long, Set<Long>> roleMenusMap = loadRoleMenuIdMap(tenantId, allRoleIds);
            List<Long> customRoleIds = roles.stream()
                    .filter(r -> r != null && r.getId() != null && DataScopeType.fromCode(r.getDataScope()) == DataScopeType.CUSTOM)
                    .map(SysRole::getId)
                    .distinct()
                    .toList();
            Map<Long, Set<Long>> customDeptMap = customRoleIds.isEmpty()
                    ? Collections.emptyMap()
                    : loadRoleCustomDeptMap(tenantId, customRoleIds);
            MenuGrantEnvironment env = loadMenuGrantEnvironment(tenantId);

            Set<Long> exceeded = new LinkedHashSet<>();
            for (SysRole role : roles) {
                if (role == null || role.getId() == null) {
                    continue;
                }
                if (UserRelationSupport.isSuperAdminRole(role)) {
                    exceeded.add(role.getId());
                    continue;
                }
                DataScopeType roleScope = DataScopeType.fromCode(role.getDataScope());
                Set<Long> customDepts = customDeptMap.getOrDefault(role.getId(), Collections.emptySet());
                if (roleScope == null || !actor.dominatesDataScope(roleScope, customDepts)) {
                    exceeded.add(role.getId());
                    continue;
                }
                Set<Long> rawMenus = roleMenusMap.getOrDefault(role.getId(), Collections.emptySet());
                Set<Long> effectiveNonDirs = collectEffectiveNonDirMenuIds(rawMenus, env);
                if (!actor.effectiveNonDirMenuIds().containsAll(effectiveNonDirs)) {
                    exceeded.add(role.getId());
                }
            }
            return exceeded.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(exceeded);
        }));
    }

    private ActorPrivilegeContext loadRestrictedActorContext(Long tenantId, Long actorUserId) {
        List<SysRole> activeRoles = SystemContextSupport.safeList(
                sysUserRoleMapper.selectActiveRolesByUserId(tenantId, actorUserId)
        );
        if (SolidConstants.PLATFORM_TENANT_ID.equals(tenantId)
                && activeRoles.stream().anyMatch(UserRelationSupport::isSuperAdminRole)) {
            return ActorPrivilegeContext.UNRESTRICTED;
        }
        Set<Long> boundRoleIds = Set.copyOf(
                SystemContextSupport.safeList(sysUserRoleMapper.selectRoleIdsByUserId(tenantId, actorUserId))
        );
        if (activeRoles.isEmpty()) {
            return new ActorPrivilegeContext(
                    false, boundRoleIds, Collections.emptySet(), Collections.emptySet(),
                    ScopeResolution.EMPTY, false, false, false, false, false
            );
        }

        boolean hasAll = false;
        boolean hasDeptAndChild = false;
        boolean hasDept = false;
        boolean hasCustom = false;
        boolean hasSelf = false;
        List<Long> activeRoleIds = activeRoles.stream().map(SysRole::getId).filter(Objects::nonNull).toList();
        for (SysRole role : activeRoles) {
            DataScopeType scopeType = role != null ? DataScopeType.fromCode(role.getDataScope()) : null;
            if (scopeType == null) {
                continue;
            }
            switch (scopeType) {
                case ALL -> hasAll = true;
                case DEPT_AND_CHILD -> hasDeptAndChild = true;
                case DEPT -> hasDept = true;
                case CUSTOM -> hasCustom = true;
                case SELF -> hasSelf = true;
            }
        }

        ScopeResolution scopeResolution = dataPermissionHandler.resolveActorScope(tenantId, actorUserId);
        Set<Long> boundRawMenuIds = activeRoleIds.isEmpty()
                ? Collections.emptySet()
                : unionRoleMenuIds(tenantId, activeRoleIds);
        MenuGrantEnvironment env = loadMenuGrantEnvironment(tenantId);
        Set<Long> effectiveNonDirMenuIds = collectEffectiveNonDirMenuIds(boundRawMenuIds, env);

        return new ActorPrivilegeContext(
                false,
                boundRoleIds,
                Collections.unmodifiableSet(boundRawMenuIds),
                Collections.unmodifiableSet(effectiveNonDirMenuIds),
                scopeResolution,
                hasAll,
                hasDeptAndChild,
                hasDept,
                hasCustom,
                hasSelf
        );
    }

    private MenuGrantEnvironment loadMenuGrantEnvironment(Long tenantId) {
        boolean platformView = SolidConstants.PLATFORM_TENANT_ID.equals(tenantId);
        boolean tenantEnabled = tenantProperties != null && tenantProperties.isEnabled();
        Set<Long> packageMenuIds = platformView ? Collections.emptySet() : loadActiveTenantPackageMenuIds(tenantId);

        List<SysMenu> allMenus = TenantContextHolder.runWithoutTenant(() -> sysMenuMapper.selectList(
                Wrappers.<SysMenu>lambdaQuery()
                        .orderByAsc(SysMenu::getSort)
                        .orderByAsc(SysMenu::getId)
        ));
        List<SysMenu> safeMenus = SystemContextSupport.safeList(allMenus);
        Map<Long, SysMenu> menusById = new HashMap<>(safeMenus.size());
        for (SysMenu menu : safeMenus) {
            if (menu != null && menu.getId() != null) {
                menusById.put(menu.getId(), menu);
            }
        }
        Map<Long, List<SysMenu>> childrenMap = MenuTreeSupport.groupByParentId(safeMenus);
        return new MenuGrantEnvironment(platformView, tenantEnabled, packageMenuIds, menusById, childrenMap);
    }

    private Set<Long> collectEffectiveNonDirMenuIds(Set<Long> rawRoleMenuIds, MenuGrantEnvironment env) {
        if (CollectionUtils.isEmpty(rawRoleMenuIds)) {
            return Collections.emptySet();
        }
        if (!env.platformView() && env.packageMenuIds().isEmpty()) {
            return Collections.emptySet();
        }
        Set<Long> collector = new LinkedHashSet<>();
        traverseReachableMenus(0L, rawRoleMenuIds, env, collector, new HashSet<>());
        return collector.isEmpty() ? Collections.emptySet() : collector;
    }

    private void traverseReachableMenus(
            Long parentId,
            Set<Long> rawRoleMenuIds,
            MenuGrantEnvironment env,
            Set<Long> collector,
            Set<Long> visited
    ) {
        if (parentId == null || !visited.add(parentId)) {
            return;
        }
        List<SysMenu> candidates = env.childrenMap().get(parentId);
        if (CollectionUtils.isEmpty(candidates)) {
            return;
        }
        for (SysMenu menu : candidates) {
            if (menu == null || menu.getId() == null || visited.contains(menu.getId())) {
                continue;
            }
            if (!env.isReachableNode(menu)) {
                continue;
            }
            MenuTypeEnum nodeType = MenuTypeEnum.fromCode(menu.getMenuType());
            if (nodeType == null) {
                continue;
            }
            boolean inPackage = env.isInPackage(menu.getId());
            if (nodeType != MenuTypeEnum.DIRECTORY && !inPackage) {
                continue;
            }
            if (nodeType != MenuTypeEnum.DIRECTORY && inPackage && rawRoleMenuIds.contains(menu.getId())) {
                collector.add(menu.getId());
            }
            traverseReachableMenus(menu.getId(), rawRoleMenuIds, env, collector, visited);
        }
    }

    private static boolean hasDescendantInSet(
            Long dirId,
            Map<Long, List<SysMenu>> childrenMap,
            Set<Long> requestedSet,
            Set<Long> allowedNonDirIds,
            Set<Long> visited
    ) {
        if (dirId == null || !visited.add(dirId)) {
            return false;
        }
        List<SysMenu> children = childrenMap.get(dirId);
        if (CollectionUtils.isEmpty(children)) {
            return false;
        }
        for (SysMenu child : children) {
            if (child == null || child.getId() == null || visited.contains(child.getId())) {
                continue;
            }
            MenuTypeEnum type = MenuTypeEnum.fromCode(child.getMenuType());
            if (type == null) {
                continue;
            }
            if (type != MenuTypeEnum.DIRECTORY) {
                if (requestedSet.contains(child.getId()) && allowedNonDirIds.contains(child.getId())) {
                    return true;
                }
            }
            if (hasDescendantInSet(child.getId(), childrenMap, requestedSet, allowedNonDirIds, visited)) {
                return true;
            }
        }
        return false;
    }

    private Set<Long> loadActiveTenantPackageMenuIds(Long tenantId) {
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
            Set<Long> ids = new HashSet<>(mappings.size());
            for (SysTenantPackageMenu pm : mappings) {
                if (pm.getMenuId() != null) {
                    ids.add(pm.getMenuId());
                }
            }
            return ids;
        });
    }

    private Set<Long> unionRoleMenuIds(Long tenantId, List<Long> roleIds) {
        List<SysRoleMenu> roleMenus = sysRoleMenuMapper.selectList(
                Wrappers.<SysRoleMenu>lambdaQuery()
                        .select(SysRoleMenu::getMenuId)
                        .eq(SysRoleMenu::getTenantId, tenantId)
                        .in(SysRoleMenu::getRoleId, roleIds)
        );
        if (CollectionUtils.isEmpty(roleMenus)) {
            return Collections.emptySet();
        }
        Set<Long> ids = new HashSet<>(roleMenus.size());
        for (SysRoleMenu rm : roleMenus) {
            if (rm != null && rm.getMenuId() != null) {
                ids.add(rm.getMenuId());
            }
        }
        return ids;
    }

    private Map<Long, Set<Long>> loadRoleMenuIdMap(Long tenantId, List<Long> roleIds) {
        List<SysRoleMenu> roleMenus = sysRoleMenuMapper.selectList(
                Wrappers.<SysRoleMenu>lambdaQuery()
                        .select(SysRoleMenu::getRoleId, SysRoleMenu::getMenuId)
                        .eq(SysRoleMenu::getTenantId, tenantId)
                        .in(SysRoleMenu::getRoleId, roleIds)
        );
        if (CollectionUtils.isEmpty(roleMenus)) {
            return Collections.emptyMap();
        }
        Map<Long, Set<Long>> map = new HashMap<>(roleIds.size());
        for (SysRoleMenu rm : roleMenus) {
            if (rm != null && rm.getRoleId() != null && rm.getMenuId() != null) {
                map.computeIfAbsent(rm.getRoleId(), ignored -> new HashSet<>()).add(rm.getMenuId());
            }
        }
        return map;
    }

    private Map<Long, Set<Long>> loadRoleCustomDeptMap(Long tenantId, List<Long> customRoleIds) {
        List<SysRoleDept> roleDepts = sysRoleDeptMapper.selectList(
                Wrappers.<SysRoleDept>lambdaQuery()
                        .select(SysRoleDept::getRoleId, SysRoleDept::getDeptId)
                        .eq(SysRoleDept::getTenantId, tenantId)
                        .in(SysRoleDept::getRoleId, customRoleIds)
        );
        if (CollectionUtils.isEmpty(roleDepts)) {
            return Collections.emptyMap();
        }
        Map<Long, Set<Long>> map = new HashMap<>(customRoleIds.size());
        for (SysRoleDept rd : roleDepts) {
            if (rd != null && rd.getRoleId() != null && rd.getDeptId() != null) {
                map.computeIfAbsent(rd.getRoleId(), ignored -> new HashSet<>()).add(rd.getDeptId());
            }
        }
        return map;
    }

    private record MenuGrantEnvironment(
            boolean platformView,
            boolean tenantEnabled,
            Set<Long> packageMenuIds,
            Map<Long, SysMenu> menusById,
            Map<Long, List<SysMenu>> childrenMap
    ) {
        private boolean isReachableNode(SysMenu menu) {
            if (menu == null || !CommonStatusEnum.NORMAL.getCode().equals(menu.getStatus())) {
                return false;
            }
            if (!platformView && Boolean.TRUE.equals(menu.getPlatformOnly())) {
                return false;
            }
            return tenantEnabled || !MenuTreeSupport.isTenantGovernanceMenu(menu);
        }

        private boolean isInPackage(Long menuId) {
            return platformView || packageMenuIds.contains(menuId);
        }
    }

    /**
     * 操作人权限能力只读快照。
     *
     * @param unrestricted           是否免受子集校验（无登录态程序化调用或平台超级管理员）
     * @param boundRoleIds           当前操作人自身绑定的角色主键集合（含启停态，防自改/自删）
     * @param boundRawMenuIds        当前操作人有效角色直绑菜单原始集合
     * @param effectiveNonDirMenuIds 当前操作人在当前租户与套餐下生效的非目录菜单/按钮主键集合
     * @param scopeResolution        当前操作人 5 级数据权限生效范围快照
     * @param hasAll                 是否持有 {@code ALL} 数据范围角色
     * @param hasDeptAndChild        是否持有 {@code DEPT_AND_CHILD} 数据范围角色
     * @param hasDept                是否持有 {@code DEPT} 数据范围角色
     * @param hasCustom              是否持有 {@code CUSTOM} 数据范围角色
     * @param hasSelf                是否持有 {@code SELF} 数据范围角色
     */
    public record ActorPrivilegeContext(
            boolean unrestricted,
            Set<Long> boundRoleIds,
            Set<Long> boundRawMenuIds,
            Set<Long> effectiveNonDirMenuIds,
            ScopeResolution scopeResolution,
            boolean hasAll,
            boolean hasDeptAndChild,
            boolean hasDept,
            boolean hasCustom,
            boolean hasSelf
    ) {
        public static final ActorPrivilegeContext UNRESTRICTED = new ActorPrivilegeContext(
                true, Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
                ScopeResolution.ALLOW_ALL, true, false, false, false, false
        );

        /**
         * 计算新增角色未显式指定 {@code dataScope} 时的安全默认初始范围，确保不高于操作人自身最大可控范围。
         *
         * @return 安全初始数据权限范围枚举
         */
        public DataScopeType resolveDefaultCreateDataScope() {
            if (unrestricted || hasAll) {
                return DataScopeType.ALL;
            }
            if (hasDeptAndChild) {
                return DataScopeType.DEPT_AND_CHILD;
            }
            if (hasDept) {
                return DataScopeType.DEPT;
            }
            return DataScopeType.SELF;
        }

        /**
         * 判断操作人自身数据权限能力是否完全覆盖目标数据权限范围与自定部门集合。
         *
         * @param targetScope   目标数据权限范围
         * @param customDeptIds 当目标范围为 {@code CUSTOM} 时对应的部门主键集合
         * @return 覆盖时返回 {@code true}
         */
        public boolean dominatesDataScope(DataScopeType targetScope, Collection<Long> customDeptIds) {
            if (unrestricted || hasAll) {
                return true;
            }
            if (targetScope == null) {
                return false;
            }
            return switch (targetScope) {
                case ALL -> false;
                case DEPT_AND_CHILD -> hasDeptAndChild;
                case DEPT -> hasDeptAndChild || hasDept;
                case CUSTOM -> !CollectionUtils.isEmpty(customDeptIds)
                        && scopeResolution != null
                        && scopeResolution.visibleDeptIds().containsAll(customDeptIds);
                case SELF -> hasDeptAndChild || hasDept || hasCustom || hasSelf;
            };
        }
    }
}

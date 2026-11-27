package io.github.atengk.solid.system.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.TransactionUtil;
import io.github.atengk.boot.mybatisplus.util.PessimisticLockUtil;
import io.github.atengk.boot.satoken.cache.PermissionCacheService;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.enums.MenuTypeEnum;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysMenu;
import io.github.atengk.solid.system.entity.SysRoleMenu;
import io.github.atengk.solid.system.entity.SysTenantPackage;
import io.github.atengk.solid.system.entity.SysTenantPackageMenu;
import io.github.atengk.solid.system.mapper.SysMenuMapper;
import io.github.atengk.solid.system.mapper.SysRoleMenuMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMenuMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 租户套餐实体探查、菜单合规校验（拦截 {@code platform_only=1} 与停用节点）、跨租户 {@code sys_role_menu} 原子裁剪及缓存驱逐协同支撑。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Component
@RequiredArgsConstructor
public class TenantPackageMenuSyncSupport {

    private final SysMenuMapper sysMenuMapper;
    private final SysTenantPackageMapper sysTenantPackageMapper;
    private final SysTenantPackageMenuMapper sysTenantPackageMenuMapper;
    private final SysRoleMenuMapper sysRoleMenuMapper;
    private final PermissionCacheService permissionCacheService;

    /**
     * 校验并返回指定未删除租户套餐实体；标识无效抛 {@code 100001}，记录不存在抛 {@code 102001}。
     *
     * @param packageId 租户套餐主键
     * @return 租户套餐实体
     */
    public SysTenantPackage requirePackageExists(Long packageId) {
        if (packageId == null || packageId <= 0L) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "套餐主键必须为正整数");
        }
        SysTenantPackage pkg = TenantContextHolder.runWithoutTenant(() -> sysTenantPackageMapper.selectById(packageId));
        if (pkg == null) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "目标租户套餐不存在");
        }
        return pkg;
    }

    /**
     * 在写事务中以 {@code FOR UPDATE} 悲观行锁校验并返回指定未删除租户套餐实体。
     *
     * @param packageId 租户套餐主键
     * @return 加锁后的租户套餐实体
     */
    public SysTenantPackage requirePackageExistsForUpdate(Long packageId) {
        if (packageId == null || packageId <= 0L) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "套餐主键必须为正整数");
        }
        SysTenantPackage pkg = TenantContextHolder.runWithoutTenant(() -> {
            LambdaQueryWrapper<SysTenantPackage> lockQuery = Wrappers.<SysTenantPackage>lambdaQuery()
                    .eq(SysTenantPackage::getId, packageId);
            PessimisticLockUtil.forUpdate(lockQuery);
            return sysTenantPackageMapper.selectOne(lockQuery);
        });
        if (pkg == null) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "目标租户套餐不存在");
        }
        return pkg;
    }

    /**
     * 查询指定套餐当前绑定的全部菜单主键（按绑定主键升序）。
     *
     * @param packageId 租户套餐主键
     * @return 已绑定菜单主键列表
     */
    public List<Long> loadBoundMenuIds(Long packageId) {
        if (packageId == null || packageId <= 0L) {
            return Collections.emptyList();
        }
        List<SysTenantPackageMenu> mappings = TenantContextHolder.runWithoutTenant(() -> sysTenantPackageMenuMapper.selectList(
                Wrappers.<SysTenantPackageMenu>lambdaQuery()
                        .select(SysTenantPackageMenu::getMenuId)
                        .eq(SysTenantPackageMenu::getPackageId, packageId)
                        .orderByAsc(SysTenantPackageMenu::getId)
        ));
        if (CollectionUtils.isEmpty(mappings)) {
            return Collections.emptyList();
        }
        return mappings.stream().map(SysTenantPackageMenu::getMenuId).filter(Objects::nonNull).toList();
    }

    /**
     * 校验并去重规范化套餐勾选菜单主键，禁止任何平台专属或已停用菜单进入套餐。
     *
     * @param rawMenuIds 原始菜单主键集合
     * @return 去重且通过校验的菜单主键列表
     */
    public List<Long> validateAndNormalizePackageMenuIds(Collection<Long> rawMenuIds) {
        List<Long> distinctIds = SystemContextSupport.deduplicatePositiveIds(rawMenuIds, "存在无效的菜单主键标识");
        if (distinctIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<SysMenu> menus = TenantContextHolder.runWithoutTenant(() -> sysMenuMapper.selectList(
                Wrappers.<SysMenu>lambdaQuery().in(SysMenu::getId, distinctIds)
        ));
        if (CollectionUtils.isEmpty(menus) || menus.size() != distinctIds.size()) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "存在无效或已删除的菜单节点");
        }
        for (SysMenu menu : menus) {
            if (Boolean.TRUE.equals(menu.getPlatformOnly())) {
                throw new BusinessException(
                        SystemResultCode.TENANT_QUOTA_OR_SCOPE_EXCEEDED,
                        "禁止将平台专属菜单分配给租户套餐：" + menu.getTitle()
                );
            }
            if (!CommonStatusEnum.NORMAL.getCode().equals(menu.getStatus())) {
                throw new BusinessException(
                        SystemResultCode.TENANT_QUOTA_OR_SCOPE_EXCEEDED,
                        "禁止为租户套餐分配已停用状态的菜单：" + menu.getTitle()
                );
            }
        }
        return distinctIds;
    }

    /**
     * 装载指定套餐当前绑定且处于正常启用、非平台专属状态的菜单主键有序列表。
     *
     * @param packageId 租户套餐主键
     * @return 有效可下发菜单主键列表
     */
    public List<Long> loadEligiblePackageMenuIds(Long packageId) {
        List<Long> rawMenuIds = loadBoundMenuIds(packageId).stream().distinct().toList();
        if (rawMenuIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<SysMenu> activeMenus = TenantContextHolder.runWithoutTenant(() -> sysMenuMapper.selectList(
                Wrappers.<SysMenu>lambdaQuery()
                        .select(SysMenu::getId)
                        .in(SysMenu::getId, rawMenuIds)
                        .eq(SysMenu::getStatus, CommonStatusEnum.NORMAL.getCode())
                        .eq(SysMenu::getPlatformOnly, Boolean.FALSE)
        ));
        if (CollectionUtils.isEmpty(activeMenus)) {
            return Collections.emptyList();
        }
        Set<Long> activeSet = new HashSet<>(activeMenus.size());
        for (SysMenu menu : activeMenus) {
            activeSet.add(menu.getId());
        }
        return rawMenuIds.stream().filter(activeSet::contains).toList();
    }

    /**
     * 在 {@code runWithoutTenant} 下批量裁剪指定租户集合中超出允许菜单边界的 {@code sys_role_menu} 记录（兼容半选套餐祖先目录 {@code M}），
     * 并在事务提交后全局清理权限缓存。
     *
     * @param targetTenantIds 需要裁剪的租户主键集合
     * @param allowedMenuIds  允许保留的菜单主键集合
     */
    public void pruneRoleMenusForTenants(Collection<Long> targetTenantIds, Set<Long> allowedMenuIds) {
        if (CollectionUtils.isEmpty(targetTenantIds)) {
            return;
        }
        Set<Long> expandedAllowed = expandWithActiveAncestorDirectories(allowedMenuIds);
        TenantContextHolder.runWithoutTenant(() -> {
            if (expandedAllowed.isEmpty()) {
                sysRoleMenuMapper.delete(
                        Wrappers.<SysRoleMenu>lambdaQuery().in(SysRoleMenu::getTenantId, targetTenantIds)
                );
            } else {
                sysRoleMenuMapper.delete(
                        Wrappers.<SysRoleMenu>lambdaQuery()
                                .in(SysRoleMenu::getTenantId, targetTenantIds)
                                .notIn(SysRoleMenu::getMenuId, expandedAllowed)
                );
            }
        });
        TransactionUtil.executeAfterCommit(permissionCacheService::clearAll);
    }

    private Set<Long> expandWithActiveAncestorDirectories(Set<Long> allowedMenuIds) {
        if (CollectionUtils.isEmpty(allowedMenuIds)) {
            return Collections.emptySet();
        }
        // 半选套餐模式（menu_check_strictly=false）允许角色持有含有效子孙的父目录 M，保留其活跃祖先目录防误删
        List<SysMenu> activeMenus = TenantContextHolder.runWithoutTenant(() -> sysMenuMapper.selectList(
                Wrappers.<SysMenu>lambdaQuery()
                        .select(SysMenu::getId, SysMenu::getParentId, SysMenu::getMenuType)
                        .eq(SysMenu::getStatus, CommonStatusEnum.NORMAL.getCode())
                        .eq(SysMenu::getPlatformOnly, Boolean.FALSE)
        ));
        if (CollectionUtils.isEmpty(activeMenus)) {
            return Set.copyOf(allowedMenuIds);
        }
        Map<Long, SysMenu> activeById = new HashMap<>(activeMenus.size());
        for (SysMenu menu : activeMenus) {
            activeById.put(menu.getId(), menu);
        }
        Set<Long> expanded = new HashSet<>(allowedMenuIds);
        for (Long allowedId : allowedMenuIds) {
            SysMenu current = activeById.get(allowedId);
            Set<Long> visited = new HashSet<>();
            while (current != null && current.getParentId() != null && current.getParentId() > 0L) {
                Long parentId = current.getParentId();
                if (!visited.add(parentId)) {
                    break;
                }
                SysMenu parent = activeById.get(parentId);
                if (parent == null || MenuTypeEnum.fromCode(parent.getMenuType()) != MenuTypeEnum.DIRECTORY) {
                    break;
                }
                expanded.add(parentId);
                current = parent;
            }
        }
        return expanded;
    }
}

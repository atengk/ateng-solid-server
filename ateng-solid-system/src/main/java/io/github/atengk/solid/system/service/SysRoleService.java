package io.github.atengk.solid.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.mybatisplus.util.BatchUtil;
import io.github.atengk.boot.mybatisplus.util.PessimisticLockUtil;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.enums.DataScopeType;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysRoleDept;
import io.github.atengk.solid.system.entity.SysRoleMenu;
import io.github.atengk.solid.system.entity.SysUserRole;
import io.github.atengk.solid.system.mapper.SysRoleDeptMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysRoleMenuMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import io.github.atengk.solid.system.request.RoleDataScopeRequest;
import io.github.atengk.solid.system.request.RoleSaveRequest;
import io.github.atengk.solid.system.support.RbacPrivilegeGuard;
import io.github.atengk.solid.system.support.RbacPrivilegeGuard.ActorPrivilegeContext;
import io.github.atengk.solid.system.support.RoleViewSupport;
import io.github.atengk.solid.system.support.RoleViewSupport.NormalizedRolePayload;
import io.github.atengk.solid.system.support.SystemContextSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 角色新增、修改、停用、数据权限分配与删除写操作服务，负责同租户唯一校验、保留编码（{@code 102004}）及内置超管保护（{@code 102007}）、
 * 套餐菜单范围校验（{@code 102006}）、{@code CUSTOM} 部门绑定（{@code 100001}）与事务提交后精准清理角色及受影响用户权限缓存。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Service
@RequiredArgsConstructor
public class SysRoleService {

    private final SystemContextSupport contextSupport;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMenuMapper sysRoleMenuMapper;
    private final SysRoleDeptMapper sysRoleDeptMapper;
    private final RoleAuthorizationSupport authorizationSupport;
    private final RbacPrivilegeGuard rbacPrivilegeGuard;

    /**
     * 新增角色及菜单权限，禁填保留编码 {@code super_admin}/{@code admin}（{@code 102004}）与套餐越界菜单（{@code 102006}），
     * 并强制约束授权菜单与初始数据权限范围不得高于操作人自身边界。
     *
     * @param request 角色保存请求
     * @return 新建角色主键
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createRole(RoleSaveRequest request) {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        ActorPrivilegeContext actor = rbacPrivilegeGuard.resolveActorContext(
                tenantId, contextSupport.resolveCurrentActorUserId()
        );
        NormalizedRolePayload payload = RoleViewSupport.validateAndNormalizePayload(null, request);
        List<Long> menuIds = authorizationSupport.validateAndNormalizeMenuIds(
                tenantId, contextSupport.isMultiTenantEnabled(), request.getMenuIds()
        );
        rbacPrivilegeGuard.assertMenusWithinActorPrivilege(tenantId, actor, menuIds);
        DataScopeType defaultScope = actor.resolveDefaultCreateDataScope();

        Long roleId = TenantContextHolder.runWithTenant(tenantId, () -> {
            ensureRoleUniqueInTenant(tenantId, null, payload.roleName(), payload.roleKey());

            SysRole role = new SysRole();
            role.setTenantId(tenantId);
            role.setRoleName(payload.roleName());
            role.setRoleKey(payload.roleKey());
            role.setDataScope(defaultScope.getCode());
            role.setMenuCheckStrictly(payload.menuCheckStrictly());
            role.setDeptCheckStrictly(Boolean.FALSE);
            role.setSort(payload.sort());
            role.setStatus(payload.status());
            role.setRemark(payload.remark());

            try {
                sysRoleMapper.insert(role);
            } catch (DuplicateKeyException ex) {
                throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "同租户下角色编码或名称已存在", ex);
            }
            replaceRoleMenus(tenantId, role.getId(), menuIds);
            return role.getId();
        });
        authorizationSupport.evictRoleAndUserCacheAfterCommit(tenantId, List.of(payload.roleKey()), List.of());
        return roleId;
    }

    /**
     * 修改角色信息与菜单授权；保护内置 {@code super_admin}（{@code 102007}），提交后 3 参刷新角色权限缓存，
     * 当 {@code roleKey} 变更时同步失效已绑定用户的角色列表缓存。
     *
     * @param request 角色修改请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateRole(RoleSaveRequest request) {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        if (request == null || request.getId() == null || request.getId() <= 0L) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "修改角色必须传入有效主键");
        }
        SysRole existing = requireRoleInTenant(tenantId, request.getId());
        RoleViewSupport.assertNotBuiltinSuperAdmin(tenantId, existing, "系统内置超级管理员角色禁止编辑修改");
        ActorPrivilegeContext actor = rbacPrivilegeGuard.resolveActorContext(
                tenantId, contextSupport.resolveCurrentActorUserId()
        );
        rbacPrivilegeGuard.assertActorDominatesRoles(tenantId, actor, List.of(existing));

        NormalizedRolePayload payload = RoleViewSupport.validateAndNormalizePayload(existing, request);
        List<Long> menuIds = authorizationSupport.validateAndNormalizeMenuIds(
                tenantId, contextSupport.isMultiTenantEnabled(), request.getMenuIds()
        );
        rbacPrivilegeGuard.assertMenusWithinActorPrivilege(tenantId, actor, menuIds);

        List<Long> affectedUserIds = TenantContextHolder.runWithTenant(tenantId, () -> {
            ensureRoleUniqueInTenant(tenantId, existing.getId(), payload.roleName(), payload.roleKey());

            LambdaUpdateWrapper<SysRole> wrapper = Wrappers.<SysRole>lambdaUpdate()
                    .eq(SysRole::getTenantId, tenantId)
                    .eq(SysRole::getId, existing.getId())
                    .set(SysRole::getRoleName, payload.roleName())
                    .set(SysRole::getRoleKey, payload.roleKey())
                    .set(SysRole::getMenuCheckStrictly, payload.menuCheckStrictly())
                    .set(SysRole::getSort, payload.sort())
                    .set(SysRole::getStatus, payload.status())
                    .set(SysRole::getRemark, payload.remark());
            try {
                sysRoleMapper.update(new SysRole(), wrapper);
            } catch (DuplicateKeyException ex) {
                throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "同租户下角色编码或名称已存在", ex);
            }

            replaceRoleMenus(tenantId, existing.getId(), menuIds);
            if (!Objects.equals(existing.getRoleKey(), payload.roleKey())
                    || !Objects.equals(existing.getStatus(), payload.status())) {
                return loadBoundUserIds(tenantId, existing.getId());
            }
            return Collections.<Long>emptyList();
        });
        authorizationSupport.evictRoleAndUserCacheAfterCommit(
                tenantId, List.of(existing.getRoleKey(), payload.roleKey()), affectedUserIds
        );
    }

    /**
     * 切换角色启停状态；保护内置 {@code super_admin}（{@code 102007}）并校检越权操作，提交后双清角色及已绑定用户缓存。
     *
     * @param id     角色主键
     * @param status 目标状态（0=正常，1=停用）
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateRoleStatus(Long id, Integer status) {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        Integer validStatus = SystemContextSupport.requireValidStatus(status);
        SysRole existing = requireRoleInTenant(tenantId, id);
        RoleViewSupport.assertNotBuiltinSuperAdmin(tenantId, existing, "系统内置超级管理员角色禁止停用或修改状态");
        ActorPrivilegeContext actor = rbacPrivilegeGuard.resolveActorContext(
                tenantId, contextSupport.resolveCurrentActorUserId()
        );
        rbacPrivilegeGuard.assertActorDominatesRoles(tenantId, actor, List.of(existing));

        List<Long> affectedUserIds = TenantContextHolder.runWithTenant(tenantId, () -> {
            sysRoleMapper.update(
                    new SysRole(),
                    Wrappers.<SysRole>lambdaUpdate()
                            .eq(SysRole::getTenantId, tenantId)
                            .eq(SysRole::getId, existing.getId())
                            .set(SysRole::getStatus, validStatus)
            );
            if (!Objects.equals(existing.getStatus(), validStatus)) {
                return loadBoundUserIds(tenantId, existing.getId());
            }
            return Collections.<Long>emptyList();
        });
        authorizationSupport.evictRoleAndUserCacheAfterCommit(
                tenantId, List.of(existing.getRoleKey()), affectedUserIds
        );
    }

    /**
     * 分配角色 5 级数据权限范围；自定部门（{@code CUSTOM}）强制要求至少 1 个有效启用部门（{@code 100001}），且不得高于操作人自身数据权限。
     *
     * @param id      角色主键
     * @param request 数据权限配置请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateRoleDataScope(Long id, RoleDataScopeRequest request) {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        if (request == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "数据权限配置参数不能为空");
        }
        SysRole existing = requireRoleInTenant(tenantId, id);
        RoleViewSupport.assertNotBuiltinSuperAdmin(tenantId, existing, "系统内置超级管理员角色禁止变更数据权限范围");
        ActorPrivilegeContext actor = rbacPrivilegeGuard.resolveActorContext(
                tenantId, contextSupport.resolveCurrentActorUserId()
        );
        rbacPrivilegeGuard.assertActorDominatesRoles(tenantId, actor, List.of(existing));

        DataScopeType targetScope = DataScopeType.fromCode(request.getDataScope());
        if (targetScope == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "数据权限范围仅支持 1~5 级类型");
        }
        List<Long> customDeptIds = targetScope == DataScopeType.CUSTOM
                ? authorizationSupport.validateAndNormalizeCustomDeptIds(tenantId, request.getDeptIds())
                : Collections.emptyList();
        rbacPrivilegeGuard.assertDataScopeWithinActor(actor, targetScope, customDeptIds);
        boolean deptCheckStrictly = Boolean.TRUE.equals(request.getDeptCheckStrictly());

        TenantContextHolder.runWithTenant(tenantId, () -> {
            sysRoleMapper.update(
                    new SysRole(),
                    Wrappers.<SysRole>lambdaUpdate()
                            .eq(SysRole::getTenantId, tenantId)
                            .eq(SysRole::getId, existing.getId())
                            .set(SysRole::getDataScope, targetScope.getCode())
                            .set(SysRole::getDeptCheckStrictly, deptCheckStrictly)
            );
            sysRoleDeptMapper.delete(
                    Wrappers.<SysRoleDept>lambdaQuery()
                            .eq(SysRoleDept::getTenantId, tenantId)
                            .eq(SysRoleDept::getRoleId, existing.getId())
            );
            if (!customDeptIds.isEmpty()) {
                List<SysRoleDept> mappings = new ArrayList<>(customDeptIds.size());
                for (Long deptId : customDeptIds) {
                    SysRoleDept mapping = new SysRoleDept();
                    mapping.setTenantId(tenantId);
                    mapping.setRoleId(existing.getId());
                    mapping.setDeptId(deptId);
                    mappings.add(mapping);
                }
                BatchUtil.insertBatch(sysRoleDeptMapper, mappings);
            }
        });
    }

    /**
     * 批量删除角色；保护 {@code super_admin}（{@code 102007}）并校验无活跃用户绑定（{@code 102005}），提交后 3 参清理缓存。
     *
     * @param ids 逗号分隔的角色主键串
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteRoles(String ids) {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        List<Long> roleIds = SystemContextSupport.parsePathIds(ids);
        List<Long> sortedRoleIds = roleIds.stream().sorted().toList();
        ActorPrivilegeContext actor = rbacPrivilegeGuard.resolveActorContext(
                tenantId, contextSupport.resolveCurrentActorUserId()
        );

        List<String> deletedKeys = TenantContextHolder.runWithTenant(tenantId, () -> {
            LambdaQueryWrapper<SysRole> lockQuery = Wrappers.<SysRole>lambdaQuery()
                    .eq(SysRole::getTenantId, tenantId)
                    .in(SysRole::getId, sortedRoleIds)
                    .orderByAsc(SysRole::getId);
            PessimisticLockUtil.forUpdate(lockQuery);
            List<SysRole> targets = sysRoleMapper.selectList(lockQuery);
            if (CollectionUtils.isEmpty(targets) || targets.size() != sortedRoleIds.size()) {
                throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "存在不存在或跨租户的角色记录");
            }
            Set<String> keys = new LinkedHashSet<>(targets.size());
            for (SysRole role : targets) {
                RoleViewSupport.assertNotBuiltinSuperAdmin(tenantId, role, "系统内置超级管理员角色禁止删除");
                keys.add(role.getRoleKey());
            }
            rbacPrivilegeGuard.assertActorDominatesRoles(tenantId, actor, targets);

            List<Long> referencedIds = sysUserRoleMapper.selectReferencedRoleIds(tenantId, roleIds);
            if (!CollectionUtils.isEmpty(referencedIds)) {
                throw new BusinessException(SystemResultCode.RESOURCE_IN_USE, "角色已分配给活跃用户，请先解绑后再删除");
            }

            sysRoleMenuMapper.delete(
                    Wrappers.<SysRoleMenu>lambdaQuery()
                            .eq(SysRoleMenu::getTenantId, tenantId)
                            .in(SysRoleMenu::getRoleId, roleIds)
            );
            sysRoleDeptMapper.delete(
                    Wrappers.<SysRoleDept>lambdaQuery()
                            .eq(SysRoleDept::getTenantId, tenantId)
                            .in(SysRoleDept::getRoleId, roleIds)
            );
            sysRoleMapper.delete(
                    Wrappers.<SysRole>lambdaQuery()
                            .eq(SysRole::getTenantId, tenantId)
                            .in(SysRole::getId, roleIds)
            );
            return new ArrayList<>(keys);
        });
        authorizationSupport.evictRoleAndUserCacheAfterCommit(tenantId, deletedKeys, List.of());
    }

    private SysRole requireRoleInTenant(Long tenantId, Long id) {
        if (id == null || id <= 0L) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "角色主键无效");
        }
        SysRole role = TenantContextHolder.runWithTenant(tenantId, () -> {
            LambdaQueryWrapper<SysRole> lockQuery = Wrappers.<SysRole>lambdaQuery()
                    .eq(SysRole::getTenantId, tenantId)
                    .eq(SysRole::getId, id);
            PessimisticLockUtil.forUpdate(lockQuery);
            return sysRoleMapper.selectOne(lockQuery);
        });
        if (role == null) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "目标角色不存在或无权访问");
        }
        return role;
    }

    private List<Long> loadBoundUserIds(Long tenantId, Long roleId) {
        List<SysUserRole> userRoles = sysUserRoleMapper.selectList(
                Wrappers.<SysUserRole>lambdaQuery()
                        .select(SysUserRole::getUserId)
                        .eq(SysUserRole::getTenantId, tenantId)
                        .eq(SysUserRole::getRoleId, roleId)
        );
        if (CollectionUtils.isEmpty(userRoles)) {
            return Collections.emptyList();
        }
        List<Long> userIds = new ArrayList<>(userRoles.size());
        for (SysUserRole item : userRoles) {
            if (item.getUserId() != null) {
                userIds.add(item.getUserId());
            }
        }
        return userIds.isEmpty() ? Collections.emptyList() : userIds;
    }

    private void replaceRoleMenus(Long tenantId, Long roleId, List<Long> menuIds) {
        sysRoleMenuMapper.delete(
                Wrappers.<SysRoleMenu>lambdaQuery()
                        .eq(SysRoleMenu::getTenantId, tenantId)
                        .eq(SysRoleMenu::getRoleId, roleId)
        );
        if (CollectionUtils.isEmpty(menuIds)) {
            return;
        }
        List<SysRoleMenu> mappings = new ArrayList<>(menuIds.size());
        for (Long menuId : menuIds) {
            SysRoleMenu mapping = new SysRoleMenu();
            mapping.setTenantId(tenantId);
            mapping.setRoleId(roleId);
            mapping.setMenuId(menuId);
            mappings.add(mapping);
        }
        BatchUtil.insertBatch(sysRoleMenuMapper, mappings);
    }

    private void ensureRoleUniqueInTenant(Long tenantId, Long selfId, String roleName, String roleKey) {
        Long keyCount = sysRoleMapper.selectCount(
                Wrappers.<SysRole>lambdaQuery()
                        .eq(SysRole::getTenantId, tenantId)
                        .eq(SysRole::getRoleKey, roleKey)
                        .ne(selfId != null, SysRole::getId, selfId)
        );
        if (keyCount != null && keyCount > 0L) {
            throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "同租户下角色编码已存在：" + roleKey);
        }
        Long nameCount = sysRoleMapper.selectCount(
                Wrappers.<SysRole>lambdaQuery()
                        .eq(SysRole::getTenantId, tenantId)
                        .eq(SysRole::getRoleName, roleName)
                        .ne(selfId != null, SysRole::getId, selfId)
        );
        if (nameCount != null && nameCount > 0L) {
            throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "同租户下角色名称已存在：" + roleName);
        }
    }
}

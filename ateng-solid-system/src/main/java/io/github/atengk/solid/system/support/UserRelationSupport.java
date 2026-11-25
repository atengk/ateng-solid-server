package io.github.atengk.solid.system.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.TransactionUtil;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.mybatisplus.util.BatchUtil;
import io.github.atengk.boot.satoken.cache.PermissionCacheService;
import io.github.atengk.boot.satoken.util.LoginHelper;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysPost;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysUserPost;
import io.github.atengk.solid.system.entity.SysUserRole;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysPostMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysUserPostMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 用户关联关系（部门/岗位/角色）及事务提交后缓存强退协同支撑组件。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Component
@RequiredArgsConstructor
public class UserRelationSupport {

    private final SysDeptMapper sysDeptMapper;
    private final SysPostMapper sysPostMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserPostMapper sysUserPostMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final PermissionCacheService permissionCacheService;

    /**
     * 校验归属部门在目标租户内存在、未删除且处于正常启用状态。
     *
     * @param tenantId 目标租户主键
     * @param deptId   归属部门主键（可为空）
     */
    public void assertActiveDeptInTenant(Long tenantId, Long deptId) {
        if (deptId == null) {
            return;
        }
        if (deptId <= 0) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "归属部门主键不合法");
        }
        SysDept dept = DataPermissionContextHolder.runWithoutPermission(() ->
                TenantContextHolder.runWithTenant(tenantId, () -> sysDeptMapper.selectOne(
                        Wrappers.<SysDept>lambdaQuery()
                                .eq(SysDept::getTenantId, tenantId)
                                .eq(SysDept::getId, deptId)
                                .eq(SysDept::getStatus, CommonStatusEnum.NORMAL.getCode())
                ))
        );
        if (dept == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "归属部门不存在或已停用");
        }
    }

    /**
     * 校验并去重规范化岗位主键集合，阻断跨租户或已停用岗位绑定。
     *
     * @param tenantId 目标租户主键
     * @param postIds  原始岗位主键列表
     * @return 去重且保序的有效岗位主键列表
     */
    public List<Long> validateAndNormalizePostIds(Long tenantId, List<Long> postIds) {
        List<Long> distinctIds = deduplicatePositiveIds(postIds, "岗位");
        if (distinctIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<SysPost> posts = TenantContextHolder.runWithTenant(tenantId, () -> sysPostMapper.selectList(
                Wrappers.<SysPost>lambdaQuery()
                        .eq(SysPost::getTenantId, tenantId)
                        .in(SysPost::getId, distinctIds)
                        .eq(SysPost::getStatus, CommonStatusEnum.NORMAL.getCode())
        ));
        if (posts.size() != distinctIds.size()) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "存在无效或已停用的岗位");
        }
        return distinctIds;
    }

    /**
     * 校验并去重规范化角色主键集合，阻断无效角色与非平台超级管理员视角的 {@code super_admin} 角色越权授权。
     *
     * @param tenantId                目标租户主键
     * @param roleIds                 原始角色主键列表
     * @param allowSuperAdminAssign   是否允许分配内置超级管理员角色
     * @return 去重且保序的有效角色主键列表
     */
    public List<Long> validateAndNormalizeRoleIds(
            Long tenantId,
            List<Long> roleIds,
            boolean allowSuperAdminAssign
    ) {
        List<Long> distinctIds = deduplicatePositiveIds(roleIds, "角色");
        if (distinctIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<SysRole> roles = TenantContextHolder.runWithTenant(tenantId, () -> sysRoleMapper.selectList(
                Wrappers.<SysRole>lambdaQuery()
                        .eq(SysRole::getTenantId, tenantId)
                        .in(SysRole::getId, distinctIds)
                        .eq(SysRole::getStatus, CommonStatusEnum.NORMAL.getCode())
        ));
        if (roles.size() != distinctIds.size()) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "存在无效或已停用的角色");
        }
        if (!allowSuperAdminAssign && roles.stream().anyMatch(UserRelationSupport::isSuperAdminRole)) {
            throw new BusinessException(SystemResultCode.SYSTEM_BUILTIN_PROTECTED, "当前视角不允许分配平台超级管理员角色");
        }
        return distinctIds;
    }

    /**
     * 全量替换用户的岗位关联记录。
     *
     * @param tenantId 目标租户主键
     * @param userId   用户主键
     * @param postIds  目标岗位主键列表
     */
    public void replaceUserPosts(Long tenantId, Long userId, List<Long> postIds) {
        TenantContextHolder.runWithTenant(tenantId, () -> {
            sysUserPostMapper.delete(
                    Wrappers.<SysUserPost>lambdaQuery()
                            .eq(SysUserPost::getTenantId, tenantId)
                            .eq(SysUserPost::getUserId, userId)
            );
            if (!CollectionUtils.isEmpty(postIds)) {
                List<SysUserPost> relations = new ArrayList<>(postIds.size());
                for (Long postId : postIds) {
                    SysUserPost entity = new SysUserPost();
                    entity.setTenantId(tenantId);
                    entity.setUserId(userId);
                    entity.setPostId(postId);
                    relations.add(entity);
                }
                BatchUtil.insertBatch(sysUserPostMapper, relations);
            }
        });
    }

    /**
     * 全量替换用户的角色关联记录。
     *
     * @param tenantId 目标租户主键
     * @param userId   用户主键
     * @param roleIds  目标角色主键列表
     */
    public void replaceUserRoles(Long tenantId, Long userId, List<Long> roleIds) {
        TenantContextHolder.runWithTenant(tenantId, () -> {
            sysUserRoleMapper.delete(
                    Wrappers.<SysUserRole>lambdaQuery()
                            .eq(SysUserRole::getTenantId, tenantId)
                            .eq(SysUserRole::getUserId, userId)
            );
            if (!CollectionUtils.isEmpty(roleIds)) {
                List<SysUserRole> relations = new ArrayList<>(roleIds.size());
                for (Long roleId : roleIds) {
                    SysUserRole entity = new SysUserRole();
                    entity.setTenantId(tenantId);
                    entity.setUserId(userId);
                    entity.setRoleId(roleId);
                    relations.add(entity);
                }
                BatchUtil.insertBatch(sysUserRoleMapper, relations);
            }
        });
    }

    /**
     * 查询目标用户在指定租户下当前已绑定的有效角色主键列表。
     *
     * @param tenantId 租户主键
     * @param userId   用户主键
     * @return 角色主键列表
     */
    public List<Long> listAssignedRoleIds(Long tenantId, Long userId) {
        return TenantContextHolder.runWithTenant(
                tenantId,
                () -> SystemContextSupport.safeList(sysUserRoleMapper.selectRoleIdsByUserId(tenantId, userId))
        );
    }

    /**
     * 校验给定角色主键集合在当前租户下是否包含平台内置超级管理员角色。
     *
     * @param tenantId 租户主键
     * @param roleIds  角色主键列表
     * @return 包含 {@code super_admin} 角色时返回 {@code true}
     */
    public boolean containsSuperAdminRole(Long tenantId, Collection<Long> roleIds) {
        if (!SolidConstants.PLATFORM_TENANT_ID.equals(tenantId) || CollectionUtils.isEmpty(roleIds)) {
            return false;
        }
        if (roleIds.contains(SolidConstants.SUPER_ADMIN_ROLE_ID)) {
            return true;
        }
        List<SysRole> roles = TenantContextHolder.runWithTenant(tenantId, () -> sysRoleMapper.selectList(
                Wrappers.<SysRole>lambdaQuery()
                        .eq(SysRole::getTenantId, tenantId)
                        .in(SysRole::getId, roleIds)
        ));
        return roles.stream().anyMatch(UserRelationSupport::isSuperAdminRole);
    }

    /**
     * 批量逻辑删除用户时清理其关联的岗位与角色中间表数据。
     *
     * @param tenantId 租户主键
     * @param userIds  被删除用户主键列表
     */
    public void clearUserAssociationsOnDelete(Long tenantId, List<Long> userIds) {
        if (CollectionUtils.isEmpty(userIds)) {
            return;
        }
        TenantContextHolder.runWithTenant(tenantId, () -> {
            sysUserPostMapper.delete(
                    Wrappers.<SysUserPost>lambdaQuery()
                            .eq(SysUserPost::getTenantId, tenantId)
                            .in(SysUserPost::getUserId, userIds)
            );
            sysUserRoleMapper.delete(
                    Wrappers.<SysUserRole>lambdaQuery()
                            .eq(SysUserRole::getTenantId, tenantId)
                            .in(SysUserRole::getUserId, userIds)
            );
        });
    }

    /**
     * 判断单个用户是否为平台内置超级管理员。
     *
     * @param tenantId 租户主键
     * @param userId   用户主键
     * @return 是平台内置超级管理员返回 {@code true}
     */
    public boolean isBuiltinSuperAdminUser(Long tenantId, Long userId) {
        return userId != null && !findBuiltinSuperAdminUserIds(tenantId, List.of(userId)).isEmpty();
    }

    /**
     * 批量识别目标用户集合中的平台内置超级管理员主键集合。
     *
     * @param tenantId 租户主键
     * @param userIds  用户主键集合
     * @return 内置超级管理员用户主键集合
     */
    public Set<Long> findBuiltinSuperAdminUserIds(Long tenantId, Collection<Long> userIds) {
        if (!SolidConstants.PLATFORM_TENANT_ID.equals(tenantId) || CollectionUtils.isEmpty(userIds)) {
            return Collections.emptySet();
        }
        List<Long> matched = TenantContextHolder.runWithTenant(
                tenantId,
                () -> sysUserRoleMapper.selectSuperAdminUserIds(tenantId, userIds)
        );
        return CollectionUtils.isEmpty(matched) ? Collections.emptySet() : new HashSet<>(matched);
    }

    /**
     * 注册事务提交后副作用：按需驱逐目标用户的权限缓存，并在停用/重置密码/删除时强退全部在线会话。
     *
     * @param tenantId            目标租户主键
     * @param userIds             受影响用户主键集合
     * @param evictPermissionCache 是否清理权限与角色缓存
     * @param forceLogout         是否强退全部 {@code admin} 在线会话
     */
    public void evictAndLogoutAfterCommit(
            Long tenantId,
            Collection<Long> userIds,
            boolean evictPermissionCache,
            boolean forceLogout
    ) {
        if (tenantId == null || CollectionUtils.isEmpty(userIds) || (!evictPermissionCache && !forceLogout)) {
            return;
        }
        List<Long> snapshotIds = List.copyOf(userIds);
        String tenantStr = String.valueOf(tenantId);
        TransactionUtil.executeAfterCommit(() -> {
            for (Long userId : snapshotIds) {
                if (userId == null) {
                    continue;
                }
                String userIdStr = String.valueOf(userId);
                if (evictPermissionCache) {
                    permissionCacheService.clearUserCache(SolidConstants.ADMIN_LOGIN_TYPE, tenantStr, userIdStr);
                }
                if (forceLogout) {
                    String compositeLoginId = LoginHelper.buildCompositeLoginId(tenantStr, userIdStr);
                    LoginHelper.logoutByLoginType(SolidConstants.ADMIN_LOGIN_TYPE, compositeLoginId);
                }
            }
        });
    }

    /**
     * 判断角色是否为平台保留超管角色。
     *
     * @param role 角色实体
     * @return 为超管角色时返回 {@code true}
     */
    public static boolean isSuperAdminRole(SysRole role) {
        return role != null && (Objects.equals(SolidConstants.SUPER_ADMIN_ROLE_ID, role.getId())
                || SolidConstants.SUPER_ADMIN_ROLE.equalsIgnoreCase(role.getRoleKey()));
    }

    private static List<Long> deduplicatePositiveIds(List<Long> rawIds, String fieldLabel) {
        if (CollectionUtils.isEmpty(rawIds)) {
            return Collections.emptyList();
        }
        Set<Long> deduplicated = new LinkedHashSet<>(rawIds.size());
        for (Long id : rawIds) {
            if (id == null || id <= 0) {
                throw new BusinessException(CommonBizResultCode.PARAM_INVALID, fieldLabel + "主键必须为正整数");
            }
            deduplicated.add(id);
        }
        return List.copyOf(deduplicated);
    }
}

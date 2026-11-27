package io.github.atengk.solid.system.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.request.UserCreateRequest;
import io.github.atengk.solid.system.request.UserResetPasswordRequest;
import io.github.atengk.solid.system.request.UserUpdateRequest;
import io.github.atengk.solid.system.security.SolidDataPermissionHandler;
import io.github.atengk.solid.system.security.SolidDataPermissionHandler.ScopeResolution;
import io.github.atengk.solid.system.support.ContactFieldCrypto;
import io.github.atengk.solid.system.support.RbacPrivilegeGuard;
import io.github.atengk.solid.system.support.RbacPrivilegeGuard.ActorPrivilegeContext;
import io.github.atengk.solid.system.support.RoleViewSupport;
import io.github.atengk.solid.system.support.SystemContextSupport;
import io.github.atengk.solid.system.support.TenantQuotaGuard;
import io.github.atengk.solid.system.support.UserContactAndDictSupport;
import io.github.atengk.solid.system.support.UserContactAndDictSupport.NormalizedContactAndGender;
import io.github.atengk.solid.system.support.UserRelationSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 用户写操作核心业务服务（实现配额悲观锁防超卖、事务外口令慢哈希与提交后强退清理闭环）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Service
@RequiredArgsConstructor
public class SysUserService {

    private final SystemContextSupport contextSupport;
    private final UserContactAndDictSupport userContactAndDictSupport;
    private final SysUserMapper sysUserMapper;
    private final TenantQuotaGuard tenantQuotaGuard;
    private final UserRelationSupport relationSupport;
    private final SolidDataPermissionHandler dataPermissionHandler;
    private final RbacPrivilegeGuard rbacPrivilegeGuard;
    private final TransactionTemplate transactionTemplate;

    /**
     * 新增管理端用户并返回生成主键（口令哈希在事务外计算，入库短事务内加悲观行锁校核租户配额）。
     *
     * @param request 新增用户请求载荷
     * @return 新创建的用户主键
     */
    public Long createUser(UserCreateRequest request) {
        if (request == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "新增用户参数不能为空");
        }
        Long tenantId = contextSupport.requireEffectiveTenantId();
        Long actorUserId = contextSupport.resolveCurrentActorUserId();
        String username = userContactAndDictSupport.requireValidUsername(request.getUsername());
        String nickname = userContactAndDictSupport.requireValidNickname(request.getNickname());
        String remark = SystemContextSupport.trimToNull(request.getRemark());
        Integer status = SystemContextSupport.normalizeStatus(request.getStatus());
        NormalizedContactAndGender profile = userContactAndDictSupport.normalizeAndValidateProfileFields(
                request.getPhone(), request.getEmail(), request.getGender(), null
        );
        String encodedPassword = PasswordUtil.hash(
                userContactAndDictSupport.resolveAndValidatePassword(tenantId, request.getPassword())
        );

        return transactionTemplate.execute(txStatus -> {
            ActorPrivilegeContext actorPrivilege = rbacPrivilegeGuard.resolveActorContext(tenantId, actorUserId);
            assertTargetDeptInActorScope(tenantId, actorUserId, request.getDeptId(), null, null);
            relationSupport.assertActiveDeptInTenant(tenantId, request.getDeptId());
            List<Long> postIds = relationSupport.validateAndNormalizePostIds(tenantId, request.getPostIds());
            boolean allowSuperAdmin = RoleViewSupport.isPlatformSuperAdminView(tenantId);
            List<Long> roleIds = relationSupport.validateAndNormalizeRoleIds(tenantId, request.getRoleIds(), allowSuperAdmin);
            rbacPrivilegeGuard.assertActorCanAssignRoles(tenantId, actorPrivilege, roleIds);
            assertUniqueUserFields(tenantId, null, username, profile.phone(), profile.email());
            tenantQuotaGuard.assertQuotaAvailable(tenantId, 1);

            SysUser entity = buildNewUserEntity(tenantId, request.getDeptId(), username, nickname, encodedPassword,
                    profile, status, remark);
            try {
                TenantContextHolder.runWithTenant(tenantId, () -> sysUserMapper.insert(entity));
            } catch (DuplicateKeyException ex) {
                throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "用户账号、手机号或电子邮箱已存在", ex);
            }
            relationSupport.replaceUserPosts(tenantId, entity.getId(), postIds);
            relationSupport.replaceUserRoles(tenantId, entity.getId(), roleIds);
            return entity.getId();
        });
    }

    /**
     * 修改管理端用户信息（锁定账号不可变，强制施加数据范围与内置超管/自身保护，停用或角色变更后提交清理）。
     *
     * @param request 修改用户请求载荷
     */
    public void updateUser(UserUpdateRequest request) {
        if (request == null || request.getId() == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "修改用户参数不能为空");
        }
        Long tenantId = contextSupport.requireEffectiveTenantId();
        String nickname = userContactAndDictSupport.requireValidNickname(request.getNickname());
        String remark = SystemContextSupport.trimToNull(request.getRemark());
        Long actorUserId = contextSupport.resolveCurrentActorUserId();

        transactionTemplate.executeWithoutResult(txStatus -> {
            SysUser existing = loadEditableUserOrThrow(tenantId, request.getId(), actorUserId);
            NormalizedContactAndGender profile = userContactAndDictSupport.normalizeAndValidateProfileFields(
                    request.getPhone(), request.getEmail(), request.getGender(), existing.getGender()
            );
            Integer targetStatus = request.getStatus() != null
                    ? SystemContextSupport.requireValidStatus(request.getStatus())
                    : existing.getStatus();
            executeUserUpdate(tenantId, actorUserId, existing, request, nickname, remark, profile, targetStatus);
        });
    }

    /**
     * 切换用户启用/停用状态（停用转启用受套餐配额防超卖校验，停用提交后清理权限缓存并强退在线会话）。
     *
     * @param id     用户主键
     * @param status 目标状态（0=正常，1=停用）
     */
    public void updateUserStatus(Long id, Integer status) {
        if (id == null || id <= 0) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "用户主键必须为正整数");
        }
        Integer targetStatus = SystemContextSupport.requireValidStatus(status);
        Long tenantId = contextSupport.requireEffectiveTenantId();
        Long actorUserId = contextSupport.resolveCurrentActorUserId();

        transactionTemplate.executeWithoutResult(txStatus -> {
            SysUser existing = loadEditableUserOrThrow(tenantId, id, actorUserId);
            boolean disabling = CommonStatusEnum.DISABLED.getCode().equals(targetStatus);
            if (disabling) {
                if (relationSupport.isBuiltinSuperAdminUser(tenantId, id)) {
                    throw new BusinessException(SystemResultCode.SYSTEM_BUILTIN_PROTECTED, "平台内置超级管理员账号不允许停用");
                }
                if (Objects.equals(actorUserId, id)) {
                    throw new BusinessException(SystemResultCode.SYSTEM_BUILTIN_PROTECTED, "当前登录用户不允许停用自己");
                }
            } else if (CommonStatusEnum.DISABLED.getCode().equals(existing.getStatus())) {
                tenantQuotaGuard.assertQuotaAvailable(tenantId, 0);
            }
            if (!Objects.equals(actorUserId, id)) {
                ActorPrivilegeContext actorPrivilege = rbacPrivilegeGuard.resolveActorContext(tenantId, actorUserId);
                if (!actorPrivilege.unrestricted() && relationSupport.isBuiltinSuperAdminUser(tenantId, id)) {
                    throw new BusinessException(SystemResultCode.SYSTEM_BUILTIN_PROTECTED, "非平台超级管理员禁止变更内置超级管理员状态");
                }
                rbacPrivilegeGuard.assertActorDominatesUsers(tenantId, actorPrivilege, List.of(id));
            }

            LambdaUpdateWrapper<SysUser> wrapper = Wrappers.<SysUser>lambdaUpdate()
                    .eq(SysUser::getTenantId, tenantId)
                    .eq(SysUser::getId, id)
                    .set(SysUser::getStatus, targetStatus);
            TenantContextHolder.runWithTenant(tenantId, () -> sysUserMapper.update(new SysUser(), wrapper));

            if (disabling) {
                relationSupport.evictAndLogoutAfterCommit(tenantId, List.of(id), true, true);
            }
        });
    }

    /**
     * 管理员重置指定用户密码（阻断非本人越权重置平台内置超管，重设后置空 {@code pwd_update_time} 并强退在线会话）。
     *
     * @param id      目标用户主键
     * @param request 重置密码请求（可为空或留空密码字段，自动回退 {@code sys.user.initPassword}）
     */
    public void resetPassword(Long id, UserResetPasswordRequest request) {
        if (id == null || id <= 0) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "用户主键必须为正整数");
        }
        Long tenantId = contextSupport.requireEffectiveTenantId();
        Long actorUserId = contextSupport.resolveCurrentActorUserId();

        loadEditableUserOrThrow(tenantId, id, actorUserId);
        if (relationSupport.isBuiltinSuperAdminUser(tenantId, id) && !Objects.equals(actorUserId, id)) {
            throw new BusinessException(SystemResultCode.SYSTEM_BUILTIN_PROTECTED, "不允许非本人重置平台内置超级管理员密码");
        }
        if (!Objects.equals(actorUserId, id)) {
            ActorPrivilegeContext actorPrivilege = rbacPrivilegeGuard.resolveActorContext(tenantId, actorUserId);
            rbacPrivilegeGuard.assertActorDominatesUsers(tenantId, actorPrivilege, List.of(id));
        }

        String candidatePassword = request != null ? request.getPassword() : null;
        String encodedPassword = PasswordUtil.hash(
                userContactAndDictSupport.resolveAndValidatePassword(tenantId, candidatePassword)
        );

        transactionTemplate.executeWithoutResult(txStatus -> {
            LambdaUpdateWrapper<SysUser> wrapper = Wrappers.<SysUser>lambdaUpdate()
                    .eq(SysUser::getTenantId, tenantId)
                    .eq(SysUser::getId, id)
                    .set(SysUser::getPassword, encodedPassword)
                    .set(SysUser::getPwdUpdateTime, null);
            int updated = TenantContextHolder.runWithTenant(tenantId, () -> sysUserMapper.update(new SysUser(), wrapper));
            if (updated == 0) {
                throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "目标用户不存在或已被删除");
            }
            relationSupport.evictAndLogoutAfterCommit(tenantId, List.of(id), false, true);
        });
    }

    /**
     * 批量逻辑删除用户并同步清理角色与岗位中间表（保护数据权限边界、内置超级管理员及当前登录用户本人）。
     *
     * @param ids 逗号分隔的用户主键路径参数
     */
    public void deleteUsers(String ids) {
        List<Long> idList = SystemContextSupport.parsePathIds(ids);
        Long tenantId = contextSupport.requireEffectiveTenantId();
        Long actorUserId = contextSupport.resolveCurrentActorUserId();

        if (actorUserId != null && idList.contains(actorUserId)) {
            throw new BusinessException(SystemResultCode.SYSTEM_BUILTIN_PROTECTED, "当前登录用户不允许删除自己");
        }

        transactionTemplate.executeWithoutResult(txStatus -> {
            assertUsersDeletableInScope(tenantId, idList, actorUserId);
            if (!relationSupport.findBuiltinSuperAdminUserIds(tenantId, idList).isEmpty()) {
                throw new BusinessException(SystemResultCode.SYSTEM_BUILTIN_PROTECTED, "平台内置超级管理员账号不允许删除");
            }
            ActorPrivilegeContext actorPrivilege = rbacPrivilegeGuard.resolveActorContext(tenantId, actorUserId);
            rbacPrivilegeGuard.assertActorDominatesUsers(tenantId, actorPrivilege, idList);
            TenantContextHolder.runWithTenant(tenantId, () -> sysUserMapper.deleteByIds(idList));
            relationSupport.clearUserAssociationsOnDelete(tenantId, idList);
            relationSupport.evictAndLogoutAfterCommit(tenantId, idList, true, true);
        });
    }

    private void executeUserUpdate(
            Long tenantId,
            Long actorUserId,
            SysUser existing,
            UserUpdateRequest request,
            String nickname,
            String remark,
            NormalizedContactAndGender profile,
            Integer targetStatus
    ) {
        boolean targetBuiltinSuperAdmin = relationSupport.isBuiltinSuperAdminUser(tenantId, existing.getId());
        boolean editingSelf = actorUserId != null && actorUserId.equals(existing.getId());
        boolean allowSuperAdmin = RoleViewSupport.isPlatformSuperAdminView(tenantId);
        List<Long> existingRoleIds = relationSupport.listAssignedRoleIds(tenantId, existing.getId());

        guardUserUpdateProtections(targetBuiltinSuperAdmin, editingSelf, allowSuperAdmin, targetStatus);
        ActorPrivilegeContext actorPrivilege = rbacPrivilegeGuard.resolveActorContext(tenantId, actorUserId);
        if (!editingSelf) {
            rbacPrivilegeGuard.assertActorDominatesUsers(tenantId, actorPrivilege, List.of(existing.getId()));
        }
        assertTargetDeptInActorScope(tenantId, actorUserId, request.getDeptId(), existing.getId(), existing.getDeptId());
        relationSupport.assertActiveDeptInTenant(tenantId, request.getDeptId());
        List<Long> postIds = request.getPostIds() != null
                ? relationSupport.validateAndNormalizePostIds(tenantId, request.getPostIds())
                : null;
        List<Long> roleIds = request.getRoleIds() != null
                ? relationSupport.validateAndNormalizeRoleIds(tenantId, request.getRoleIds(), allowSuperAdmin)
                : null;
        if (roleIds != null) {
            guardRoleAssignmentMutation(tenantId, targetBuiltinSuperAdmin, editingSelf, existingRoleIds, roleIds);
            if (!editingSelf) {
                rbacPrivilegeGuard.assertActorCanAssignRoles(tenantId, actorPrivilege, roleIds);
            }
        }
        if (CommonStatusEnum.DISABLED.getCode().equals(existing.getStatus())
                && CommonStatusEnum.NORMAL.getCode().equals(targetStatus)) {
            tenantQuotaGuard.assertQuotaAvailable(tenantId, 0);
        }
        assertUniqueUserFields(tenantId, existing.getId(), null, profile.phone(), profile.email());

        applyUserUpdateStatement(tenantId, existing.getId(), request.getDeptId(), nickname, profile, targetStatus, remark);
        if (postIds != null) {
            relationSupport.replaceUserPosts(tenantId, existing.getId(), postIds);
        }
        if (roleIds != null) {
            relationSupport.replaceUserRoles(tenantId, existing.getId(), roleIds);
        }
        boolean rolesChanged = roleIds != null && !Set.copyOf(existingRoleIds).equals(Set.copyOf(roleIds));
        boolean becameDisabled = CommonStatusEnum.DISABLED.getCode().equals(targetStatus)
                && !CommonStatusEnum.DISABLED.getCode().equals(existing.getStatus());
        relationSupport.evictAndLogoutAfterCommit(
                tenantId, List.of(existing.getId()), rolesChanged || becameDisabled, becameDisabled
        );
    }

    private void applyUserUpdateStatement(
            Long tenantId,
            Long userId,
            Long deptId,
            String nickname,
            NormalizedContactAndGender profile,
            Integer targetStatus,
            String remark
    ) {
        LambdaUpdateWrapper<SysUser> wrapper = Wrappers.<SysUser>lambdaUpdate()
                .eq(SysUser::getTenantId, tenantId)
                .eq(SysUser::getId, userId)
                .set(SysUser::getDeptId, deptId)
                .set(SysUser::getNickname, nickname)
                .set(SysUser::getEmail, ContactFieldCrypto.encryptEmailForQuery(profile.email()))
                .set(SysUser::getPhone, ContactFieldCrypto.encryptPhoneForQuery(profile.phone()))
                .set(SysUser::getGender, profile.gender())
                .set(SysUser::getStatus, targetStatus)
                .set(SysUser::getRemark, remark);
        try {
            TenantContextHolder.runWithTenant(tenantId, () -> sysUserMapper.update(new SysUser(), wrapper));
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "手机号或电子邮箱已被其他用户占用", ex);
        }
    }

    private SysUser loadEditableUserOrThrow(Long tenantId, Long id, Long actorUserId) {
        boolean enforceDataScope = shouldEnforceDataScope(actorUserId);
        SysUser user = enforceDataScope
                ? TenantContextHolder.runWithTenant(tenantId, () -> sysUserMapper.selectUserByIdWithDataScope(tenantId, id))
                : DataPermissionContextHolder.runWithoutPermission(() ->
                        TenantContextHolder.runWithTenant(tenantId, () -> sysUserMapper.selectOne(
                                Wrappers.<SysUser>lambdaQuery()
                                        .eq(SysUser::getTenantId, tenantId)
                                        .eq(SysUser::getId, id)
                        )));
        if (user == null) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "目标用户不存在或无访问权限");
        }
        return user;
    }

    private void assertUsersDeletableInScope(Long tenantId, List<Long> idList, Long actorUserId) {
        boolean enforceDataScope = shouldEnforceDataScope(actorUserId);
        List<Long> visibleIds = enforceDataScope
                ? TenantContextHolder.runWithTenant(tenantId,
                        () -> sysUserMapper.selectVisibleUserIdsByIdsWithDataScope(tenantId, idList))
                : DataPermissionContextHolder.runWithoutPermission(() ->
                        TenantContextHolder.runWithTenant(tenantId, () -> sysUserMapper.selectList(
                                Wrappers.<SysUser>lambdaQuery()
                                        .select(SysUser::getId)
                                        .eq(SysUser::getTenantId, tenantId)
                                        .in(SysUser::getId, idList)
                        )).stream().map(SysUser::getId).toList());
        if (visibleIds == null || new HashSet<>(visibleIds).size() != new HashSet<>(idList).size()) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "部分目标用户不存在或无访问权限");
        }
    }

    private static boolean shouldEnforceDataScope(Long actorUserId) {
        return actorUserId != null
                && !DataPermissionContextHolder.isIgnore()
                && !DataPermissionContextHolder.isSuperAdmin();
    }

    private void assertTargetDeptInActorScope(
            Long tenantId,
            Long actorUserId,
            Long targetDeptId,
            Long targetUserId,
            Long existingDeptId
    ) {
        if (!shouldEnforceDataScope(actorUserId)) {
            return;
        }
        ScopeResolution actorScope = dataPermissionHandler.resolveActorScope(tenantId, actorUserId);
        boolean editingSelfUnchangedDept = targetUserId != null
                && Objects.equals(actorUserId, targetUserId)
                && Objects.equals(existingDeptId, targetDeptId);
        if (!editingSelfUnchangedDept && !actorScope.isDeptAllowed(targetDeptId)) {
            throw new BusinessException(CommonBizResultCode.DATA_SCOPE_ERROR, "归属部门不在当前操作人的数据权限范围内");
        }
    }

    private void assertUniqueUserFields(Long tenantId, Long excludeId, String username, String phone, String email) {
        DataPermissionContextHolder.runWithoutPermission(() -> TenantContextHolder.runWithTenant(tenantId, () -> {
            assertUniqueField(tenantId, excludeId, SysUser::getUsername, username, "登录账号已被占用");
            assertUniqueField(tenantId, excludeId, SysUser::getPhone, phone, "手机号码已被其他用户绑定");
            assertUniqueField(tenantId, excludeId, SysUser::getEmail, email, "电子邮箱已被其他用户绑定");
        }));
    }

    private <V> void assertUniqueField(
            Long tenantId,
            Long excludeId,
            com.baomidou.mybatisplus.core.toolkit.support.SFunction<SysUser, V> column,
            V value,
            String conflictMessage
    ) {
        if (value == null) {
            return;
        }
        long count = sysUserMapper.selectCount(
                Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getTenantId, tenantId)
                        .eq(column, value)
                        .ne(excludeId != null, SysUser::getId, excludeId)
        );
        if (count > 0) {
            throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, conflictMessage);
        }
    }

    private static SysUser buildNewUserEntity(
            Long tenantId,
            Long deptId,
            String username,
            String nickname,
            String encodedPassword,
            NormalizedContactAndGender profile,
            Integer status,
            String remark
    ) {
        SysUser entity = new SysUser();
        entity.setTenantId(tenantId);
        entity.setDeptId(deptId);
        entity.setUsername(username);
        entity.setNickname(nickname);
        entity.setPassword(encodedPassword);
        entity.setPwdUpdateTime(null);
        entity.setEmail(profile.email());
        entity.setPhone(profile.phone());
        entity.setGender(profile.gender());
        entity.setStatus(status);
        entity.setRemark(remark);
        return entity;
    }

    private static void guardUserUpdateProtections(
            boolean targetBuiltinSuperAdmin,
            boolean editingSelf,
            boolean allowSuperAdmin,
            Integer targetStatus
    ) {
        if (targetBuiltinSuperAdmin) {
            if (!allowSuperAdmin) {
                throw new BusinessException(SystemResultCode.SYSTEM_BUILTIN_PROTECTED, "非平台超级管理员禁止修改内置超级管理员信息");
            }
            if (CommonStatusEnum.DISABLED.getCode().equals(targetStatus)) {
                throw new BusinessException(SystemResultCode.SYSTEM_BUILTIN_PROTECTED, "平台内置超级管理员账号不允许停用");
            }
        }
        if (editingSelf && CommonStatusEnum.DISABLED.getCode().equals(targetStatus)) {
            throw new BusinessException(SystemResultCode.SYSTEM_BUILTIN_PROTECTED, "当前登录用户不允许停用自己");
        }
    }

    private void guardRoleAssignmentMutation(
            Long tenantId,
            boolean targetBuiltinSuperAdmin,
            boolean editingSelf,
            List<Long> existingRoleIds,
            List<Long> requestedRoleIds
    ) {
        if (targetBuiltinSuperAdmin && !relationSupport.containsSuperAdminRole(tenantId, requestedRoleIds)) {
            throw new BusinessException(SystemResultCode.SYSTEM_BUILTIN_PROTECTED, "平台内置超级管理员不允许剥离超级管理员角色");
        }
        if (editingSelf && !Set.copyOf(existingRoleIds).equals(Set.copyOf(requestedRoleIds))) {
            throw new BusinessException(SystemResultCode.SYSTEM_BUILTIN_PROTECTED, "当前登录用户不允许自行篡改角色授权");
        }
    }
}

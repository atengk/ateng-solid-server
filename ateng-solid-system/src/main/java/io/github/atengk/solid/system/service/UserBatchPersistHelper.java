package io.github.atengk.solid.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.mybatisplus.util.BatchUtil;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysPost;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.entity.SysUserPost;
import io.github.atengk.solid.system.entity.SysUserRole;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysPostMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserPostMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import io.github.atengk.solid.system.service.UserImportValidator.ValidatedImportRow;
import io.github.atengk.solid.system.support.ContactFieldCrypto;
import io.github.atengk.solid.system.support.TenantQuotaGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 用户批量导入阶段 3 短事务落库协作者。
 * <p>
 * 仅在已完成无事务并行 PBKDF2 哈希之后开启极短写事务，先以悲观行锁复核租户配额并复核关联部门/岗位/角色仍处于启用态再调用
 * {@link BatchUtil#insertBatch} 一次性批量写入用户及岗位/角色关联表。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Component
@RequiredArgsConstructor
public class UserBatchPersistHelper {

    private final TenantQuotaGuard tenantQuotaGuard;
    private final SysDeptMapper sysDeptMapper;
    private final SysPostMapper sysPostMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserMapper sysUserMapper;
    private final SysUserPostMapper sysUserPostMapper;
    private final SysUserRoleMapper sysUserRoleMapper;

    /**
     * 在短事务内加悲观行锁复核租户配额与关联主体启用态后批量写入用户主表及关联中间表。
     *
     * @param tenantId      工作租户主键
     * @param preparedUsers 已完成独立随机盐哈希的待落库用户列表
     * @return 实际落库用户数量
     */
    @Transactional(rollbackFor = Exception.class)
    public int persistBatch(Long tenantId, List<PreparedImportUser> preparedUsers) {
        if (CollectionUtils.isEmpty(preparedUsers)) {
            return 0;
        }
        tenantQuotaGuard.assertQuotaAvailable(tenantId, preparedUsers.size());
        assertReferencesStillActiveInTenant(tenantId, preparedUsers);

        List<SysUser> users = new ArrayList<>(preparedUsers.size());
        List<SysUserPost> userPosts = new ArrayList<>();
        List<SysUserRole> userRoles = new ArrayList<>();

        for (PreparedImportUser item : preparedUsers) {
            ValidatedImportRow row = item.row();
            long userId = IdUtil.getSnowflakeNextId();

            SysUser user = new SysUser();
            user.setId(userId);
            user.setTenantId(tenantId);
            user.setDeptId(row.deptId());
            user.setUsername(row.username());
            user.setNickname(row.nickname());
            user.setPassword(item.passwordHash());
            user.setPwdUpdateTime(null);
            user.setEmail(ContactFieldCrypto.normalizeEmail(row.email()));
            user.setPhone(ContactFieldCrypto.normalizePhone(row.phone()));
            user.setGender(row.gender());
            user.setStatus(row.status());
            users.add(user);

            for (Long postId : row.postIds()) {
                SysUserPost up = new SysUserPost();
                up.setTenantId(tenantId);
                up.setUserId(userId);
                up.setPostId(postId);
                userPosts.add(up);
            }
            for (Long roleId : row.roleIds()) {
                SysUserRole ur = new SysUserRole();
                ur.setTenantId(tenantId);
                ur.setUserId(userId);
                ur.setRoleId(roleId);
                userRoles.add(ur);
            }
        }

        TenantContextHolder.runWithTenant(tenantId, () -> {
            try {
                BatchUtil.insertBatch(sysUserMapper, users);
            } catch (DuplicateKeyException ex) {
                throw new BusinessException(
                        SystemResultCode.DUPLICATE_RESOURCE,
                        "批量导入存在与现有用户冲突的账号、手机号码或电子邮箱",
                        ex
                );
            }
            if (!userPosts.isEmpty()) {
                BatchUtil.insertBatch(sysUserPostMapper, userPosts);
            }
            if (!userRoles.isEmpty()) {
                BatchUtil.insertBatch(sysUserRoleMapper, userRoles);
            }
        });
        return users.size();
    }

    private void assertReferencesStillActiveInTenant(Long tenantId, List<PreparedImportUser> preparedUsers) {
        Set<Long> deptIds = new LinkedHashSet<>();
        Set<Long> postIds = new LinkedHashSet<>();
        Set<Long> roleIds = new LinkedHashSet<>();
        for (PreparedImportUser item : preparedUsers) {
            ValidatedImportRow row = item.row();
            if (row.deptId() != null) {
                deptIds.add(row.deptId());
            }
            if (!CollectionUtils.isEmpty(row.postIds())) {
                postIds.addAll(row.postIds());
            }
            if (!CollectionUtils.isEmpty(row.roleIds())) {
                roleIds.addAll(row.roleIds());
            }
        }
        DataPermissionContextHolder.runWithoutPermission(() -> TenantContextHolder.runWithTenant(tenantId, () -> {
            if (!deptIds.isEmpty()) {
                long activeDeptCount = sysDeptMapper.selectCount(
                        Wrappers.<SysDept>lambdaQuery()
                                .eq(SysDept::getTenantId, tenantId)
                                .in(SysDept::getId, deptIds)
                                .eq(SysDept::getStatus, CommonStatusEnum.NORMAL.getCode())
                );
                if (activeDeptCount != deptIds.size()) {
                    throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "导入用户关联的部分部门已被停用或删除，请重新核验后导入");
                }
            }
            if (!postIds.isEmpty()) {
                long activePostCount = sysPostMapper.selectCount(
                        Wrappers.<SysPost>lambdaQuery()
                                .eq(SysPost::getTenantId, tenantId)
                                .in(SysPost::getId, postIds)
                                .eq(SysPost::getStatus, CommonStatusEnum.NORMAL.getCode())
                );
                if (activePostCount != postIds.size()) {
                    throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "导入用户关联的部分岗位已被停用或删除，请重新核验后导入");
                }
            }
            if (!roleIds.isEmpty()) {
                long activeRoleCount = sysRoleMapper.selectCount(
                        Wrappers.<SysRole>lambdaQuery()
                                .eq(SysRole::getTenantId, tenantId)
                                .in(SysRole::getId, roleIds)
                                .eq(SysRole::getStatus, CommonStatusEnum.NORMAL.getCode())
                );
                if (activeRoleCount != roleIds.size()) {
                    throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "导入用户关联的部分角色已被停用或删除，请重新核验后导入");
                }
            }
        }));
    }

    /**
     * 已计算好独立随机盐密码哈希的待入库用户载体。
     *
     * @param row          阶段 1 校验通过的用户基础信息
     * @param passwordHash 阶段 2 计算完成的 PBKDF2 慢哈希串
     */
    public record PreparedImportUser(ValidatedImportRow row, String passwordHash) {
    }
}

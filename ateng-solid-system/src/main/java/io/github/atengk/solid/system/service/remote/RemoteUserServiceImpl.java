package io.github.atengk.solid.system.service.remote;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.storage.template.StorageOperations;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.command.UpdatePersonalProfileCommand;
import io.github.atengk.solid.system.api.command.UploadAvatarCommand;
import io.github.atengk.solid.system.api.dto.AdminAuthUserDTO;
import io.github.atengk.solid.system.api.dto.AdminProfileDTO;
import io.github.atengk.solid.system.api.dto.AvatarUploadDTO;
import io.github.atengk.solid.system.api.dto.PersonalProfileDTO;
import io.github.atengk.solid.system.api.dto.TenantAuthDTO;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.api.service.RemoteTenantService;
import io.github.atengk.solid.system.api.service.RemoteUserService;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserPostMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import io.github.atengk.solid.system.support.AvatarStorageHelper;
import io.github.atengk.solid.system.support.ContactFieldCrypto;
import io.github.atengk.solid.system.support.UserContactAndDictSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 系统用户跨模块远程服务实现：覆盖认证用户检索、超管跨租户摘要解析、个人中心密文明文透明维护、事务安全头像上传及改密哈希落库。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Service
@RequiredArgsConstructor
public class RemoteUserServiceImpl implements RemoteUserService {

    private final SysUserMapper sysUserMapper;
    private final SysDeptMapper sysDeptMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysUserPostMapper sysUserPostMapper;
    private final RemoteTenantService remoteTenantService;
    private final AvatarStorageHelper avatarStorageHelper;
    private final UserContactAndDictSupport userContactAndDictSupport;
    private final TransactionTemplate transactionTemplate;

    @Override
    public AdminAuthUserDTO getAuthUserByUsername(Long tenantId, String username) {
        if (tenantId == null || !StringUtils.hasText(username)) {
            return null;
        }
        String normalizedUsername = username.trim();
        SysUser user = TenantContextHolder.runWithTenant(tenantId, () -> sysUserMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getTenantId, tenantId)
                        .eq(SysUser::getUsername, normalizedUsername)
        ));
        return toAdminAuthUserDTO(user);
    }

    @Override
    public AdminAuthUserDTO getAuthUserById(Long tenantId, Long userId) {
        return toAdminAuthUserDTO(findUserByTenantAndId(tenantId, userId));
    }

    @Override
    public AdminProfileDTO getAdminProfile(Long tenantId, Long userId) {
        if (tenantId == null || userId == null) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND);
        }
        SysUser user = findUserByTenantAndId(tenantId, userId);
        if (user == null && !SolidConstants.PLATFORM_TENANT_ID.equals(tenantId)) {
            // 仅当登录主体为平台超级管理员时，允许按跨租户切换后的 effectiveTenantId 读取导航摘要
            user = findVerifiedPlatformSuperAdmin(userId);
        }
        TenantAuthDTO tenant = user != null ? remoteTenantService.getTenantById(tenantId) : null;
        if (user == null || tenant == null) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND);
        }

        return AdminProfileDTO.builder()
                .userId(user.getId())
                .tenantId(tenantId)
                .tenantName(tenant.getTenantName())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatarUrl(avatarStorageHelper.resolvePreviewUrl(user.getAvatar()))
                .pwdUpdateTime(user.getPwdUpdateTime())
                .loginIp(user.getLoginIp())
                .loginDate(user.getLoginDate())
                .build();
    }

    @Override
    public PersonalProfileDTO getPersonalProfile(Long tenantId, Long userId) {
        SysUser user = findUserByTenantAndId(tenantId, userId);
        if (user == null) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND);
        }

        TenantAuthDTO tenant = remoteTenantService.getTenantById(tenantId);
        String tenantName = tenant != null ? tenant.getTenantName() : "";
        String deptName = loadDeptName(tenantId, user.getDeptId());
        List<String> postNames = TenantContextHolder.runWithTenant(
                tenantId,
                () -> safeList(sysUserPostMapper.selectActivePostNamesByUserId(tenantId, userId))
        );
        List<String> roleNames = loadActiveRoleNames(tenantId, userId);

        return PersonalProfileDTO.builder()
                .userId(user.getId())
                .tenantId(user.getTenantId())
                .tenantName(tenantName)
                .username(user.getUsername())
                .nickname(user.getNickname())
                .email(user.getEmail())
                .phone(user.getPhone())
                .gender(user.getGender())
                .avatarUrl(avatarStorageHelper.resolvePreviewUrl(user.getAvatar()))
                .deptName(deptName)
                .postNames(postNames)
                .roles(roleNames)
                .loginIp(user.getLoginIp())
                .loginDate(user.getLoginDate())
                .createTime(user.getCreateTime())
                .pwdUpdateTime(user.getPwdUpdateTime())
                .build();
    }

    @Override
    public void updatePersonalProfile(Long tenantId, Long userId, UpdatePersonalProfileCommand cmd) {
        if (tenantId == null || userId == null) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND);
        }
        if (cmd == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "个人资料更新参数不能为空");
        }

        try {
            TenantContextHolder.runWithTenant(tenantId, () -> transactionTemplate.executeWithoutResult(status -> {
                SysUser existing = requireUserInternal(tenantId, userId);
                if (cmd.getGender() != null && !StringUtils.hasText(cmd.getGender())) {
                    throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "用户性别编码不能为空白字符");
                }
                String nickname = cmd.getNickname() != null
                        ? userContactAndDictSupport.requireValidNickname(cmd.getNickname())
                        : existing.getNickname();
                UserContactAndDictSupport.NormalizedContactAndGender profileFields =
                        userContactAndDictSupport.normalizeAndValidateProfileFields(
                                cmd.getPhone(), cmd.getEmail(), cmd.getGender(), existing.getGender());

                assertContactUnique(tenantId, userId, profileFields.phone(), profileFields.email());

                // LambdaUpdateWrapper#set 无自动 TypeHandler，必须传确定性密文且显式置 null 清空绑定
                LambdaUpdateWrapper<SysUser> updateWrapper = Wrappers.<SysUser>lambdaUpdate()
                        .eq(SysUser::getTenantId, tenantId)
                        .eq(SysUser::getId, userId)
                        .set(SysUser::getNickname, nickname)
                        .set(SysUser::getGender, profileFields.gender())
                        .set(SysUser::getPhone, ContactFieldCrypto.encryptPhoneForQuery(profileFields.phone()))
                        .set(SysUser::getEmail, ContactFieldCrypto.encryptEmailForQuery(profileFields.email()));
                sysUserMapper.update(new SysUser(), updateWrapper);
            }));
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "手机号码或电子邮箱已被同一租户下其他用户使用", ex);
        }
    }

    @Override
    public AvatarUploadDTO uploadAvatar(Long tenantId, Long userId, UploadAvatarCommand cmd) {
        AvatarStorageHelper.ValidatedAvatar validatedAvatar = avatarStorageHelper.validateUpload(cmd);
        StorageOperations storage = avatarStorageHelper.requireStorageOperations();
        if (tenantId == null || userId == null) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND);
        }

        // 按规格 §3.1 在单事务内绑定 uploadWithRollbackDelete 与 deleteAfterCommit，保障对象与库表最终一致
        String persistedPath = TenantContextHolder.runWithTenant(
                tenantId,
                () -> transactionTemplate.execute(status -> persistAvatarInTx(
                        storage, tenantId, userId, cmd, validatedAvatar
                ))
        );

        return AvatarUploadDTO.builder()
                .objectPath(persistedPath)
                .avatarUrl(avatarStorageHelper.resolvePreviewUrl(persistedPath))
                .build();
    }

    @Override
    public void updatePasswordHash(Long tenantId, Long userId, String encodedPassword, LocalDateTime pwdUpdateTime) {
        LocalDateTime effectiveUpdateTime = pwdUpdateTime != null ? pwdUpdateTime : LocalDateTime.now();
        persistPasswordHashInternal(tenantId, userId, encodedPassword, effectiveUpdateTime);
    }

    @Override
    public void upgradePasswordHash(Long tenantId, Long userId, String encodedPassword) {
        persistPasswordHashInternal(tenantId, userId, encodedPassword, null);
    }

    private void persistPasswordHashInternal(
            Long tenantId,
            Long userId,
            String encodedPassword,
            LocalDateTime pwdUpdateTime
    ) {
        if (tenantId == null || userId == null) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND);
        }
        if (!StringUtils.hasText(encodedPassword)) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "密码哈希值不能为空");
        }

        TenantContextHolder.runWithTenant(tenantId, () -> transactionTemplate.executeWithoutResult(status -> {
            requireUserInternal(tenantId, userId);
            LambdaUpdateWrapper<SysUser> updateWrapper = Wrappers.<SysUser>lambdaUpdate()
                    .eq(SysUser::getTenantId, tenantId)
                    .eq(SysUser::getId, userId)
                    .set(SysUser::getPassword, encodedPassword);
            if (pwdUpdateTime != null) {
                updateWrapper.set(SysUser::getPwdUpdateTime, pwdUpdateTime);
            }
            sysUserMapper.update(new SysUser(), updateWrapper);
        }));
    }

    private String persistAvatarInTx(
            StorageOperations storage,
            Long tenantId,
            Long userId,
            UploadAvatarCommand cmd,
            AvatarStorageHelper.ValidatedAvatar validatedAvatar
    ) {
        SysUser existing = requireUserInternal(tenantId, userId);
        String finalPath = avatarStorageHelper.uploadWithRollbackCompensation(
                storage, tenantId, userId, cmd, validatedAvatar
        );
        sysUserMapper.update(
                new SysUser(),
                Wrappers.<SysUser>lambdaUpdate()
                        .eq(SysUser::getTenantId, tenantId)
                        .eq(SysUser::getId, userId)
                        .set(SysUser::getAvatar, finalPath)
        );
        avatarStorageHelper.scheduleDeleteOldAvatarAfterCommit(storage, existing.getAvatar(), finalPath);
        return finalPath;
    }

    private SysUser findUserByTenantAndId(Long tenantId, Long userId) {
        if (tenantId == null || userId == null) {
            return null;
        }
        return TenantContextHolder.runWithTenant(tenantId, () -> findUserByTenantAndIdInternal(tenantId, userId));
    }

    private SysUser requireUserInternal(Long tenantId, Long userId) {
        SysUser user = findUserByTenantAndIdInternal(tenantId, userId);
        if (user == null) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND);
        }
        return user;
    }

    private SysUser findUserByTenantAndIdInternal(Long tenantId, Long userId) {
        return sysUserMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getTenantId, tenantId)
                        .eq(SysUser::getId, userId)
        );
    }

    private SysUser findVerifiedPlatformSuperAdmin(Long userId) {
        return TenantContextHolder.runWithTenant(SolidConstants.PLATFORM_TENANT_ID, () -> {
            SysUser platformUser = findUserByTenantAndIdInternal(SolidConstants.PLATFORM_TENANT_ID, userId);
            if (platformUser == null) {
                return null;
            }
            List<SysRole> roles = sysUserRoleMapper.selectActiveRolesByUserId(
                    SolidConstants.PLATFORM_TENANT_ID, userId);
            boolean hasSuperAdmin = roles != null && roles.stream()
                    .anyMatch(role -> SolidConstants.SUPER_ADMIN_ROLE.equals(role.getRoleKey()));
            return hasSuperAdmin ? platformUser : null;
        });
    }

    private String loadDeptName(Long tenantId, Long deptId) {
        if (deptId == null) {
            return null;
        }
        return TenantContextHolder.runWithTenant(tenantId, () -> {
            SysDept dept = sysDeptMapper.selectOne(
                    Wrappers.<SysDept>lambdaQuery()
                            .eq(SysDept::getTenantId, tenantId)
                            .eq(SysDept::getId, deptId)
            );
            return dept != null ? dept.getDeptName() : null;
        });
    }

    private List<String> loadActiveRoleNames(Long tenantId, Long userId) {
        List<SysRole> roles = TenantContextHolder.runWithTenant(
                tenantId,
                () -> sysUserRoleMapper.selectActiveRolesByUserId(tenantId, userId)
        );
        if (CollectionUtils.isEmpty(roles)) {
            return Collections.emptyList();
        }
        List<String> names = roles.stream().map(SysRole::getRoleName).filter(StringUtils::hasText).toList();
        return names.isEmpty() ? Collections.emptyList() : names;
    }

    private void assertContactUnique(Long tenantId, Long userId, String normalizedPhone, String normalizedEmail) {
        if (normalizedPhone != null) {
            Long phoneCount = sysUserMapper.selectCount(
                    Wrappers.<SysUser>lambdaQuery()
                            .eq(SysUser::getTenantId, tenantId)
                            .eq(SysUser::getPhone, normalizedPhone)
                            .ne(SysUser::getId, userId)
            );
            if (phoneCount != null && phoneCount > 0) {
                throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "手机号码已被同一租户下其他用户绑定");
            }
        }
        if (normalizedEmail != null) {
            Long emailCount = sysUserMapper.selectCount(
                    Wrappers.<SysUser>lambdaQuery()
                            .eq(SysUser::getTenantId, tenantId)
                            .eq(SysUser::getEmail, normalizedEmail)
                            .ne(SysUser::getId, userId)
            );
            if (emailCount != null && emailCount > 0) {
                throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "电子邮箱已被同一租户下其他用户绑定");
            }
        }
    }

    private static List<String> safeList(List<String> values) {
        return CollectionUtils.isEmpty(values) ? Collections.emptyList() : values;
    }

    private static AdminAuthUserDTO toAdminAuthUserDTO(SysUser user) {
        if (user == null) {
            return null;
        }
        return AdminAuthUserDTO.builder()
                .userId(user.getId())
                .tenantId(user.getTenantId())
                .deptId(user.getDeptId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .passwordHash(user.getPassword())
                .status(user.getStatus())
                .pwdUpdateTime(user.getPwdUpdateTime())
                .loginIp(user.getLoginIp())
                .loginDate(user.getLoginDate())
                .build();
    }
}

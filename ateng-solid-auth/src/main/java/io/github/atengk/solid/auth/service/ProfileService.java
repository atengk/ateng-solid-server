package io.github.atengk.solid.auth.service;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.boot.satoken.util.LoginHelper;
import io.github.atengk.solid.auth.request.UpdatePasswordRequest;
import io.github.atengk.solid.auth.request.UpdateProfileRequest;
import io.github.atengk.solid.auth.result.AuthResultCode;
import io.github.atengk.solid.auth.vo.AvatarUploadVO;
import io.github.atengk.solid.auth.vo.PersonalProfileVO;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.common.validator.PasswordPolicyValidator;
import io.github.atengk.solid.system.api.command.UpdatePersonalProfileCommand;
import io.github.atengk.solid.system.api.command.UploadAvatarCommand;
import io.github.atengk.solid.system.api.dto.AdminAuthUserDTO;
import io.github.atengk.solid.system.api.dto.AvatarUploadDTO;
import io.github.atengk.solid.system.api.dto.PersonalProfileDTO;
import io.github.atengk.solid.system.api.service.RemoteUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 个人中心领域服务：编排本人内存真明文资料读写、头像字节安全前置校验及零明文跨模块改密与全端强退。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Service
@RequiredArgsConstructor
public class ProfileService {

    private static final long MAX_AVATAR_BYTES = 2L * 1024 * 1024;

    private final RemoteUserService remoteUserService;

    /**
     * 获取当前登录用户归属租户下的完整个人资料（含内存真明文邮箱与手机号）。
     *
     * @return 个人中心完整资料视图对象
     * @throws BusinessException 未登录或目标用户不存在时抛出对应业务异常
     */
    public PersonalProfileVO getPersonalProfile() {
        Long ownerTenantId = AuthContextSupport.requireOwnerTenantId();
        Long userId = AuthContextSupport.requireCurrentUserId();

        PersonalProfileDTO dto = remoteUserService.getPersonalProfile(ownerTenantId, userId);
        return PersonalProfileVO.builder()
                .userId(dto.getUserId())
                .tenantId(dto.getTenantId())
                .tenantName(dto.getTenantName())
                .username(dto.getUsername())
                .nickname(dto.getNickname())
                .email(dto.getEmail())
                .phone(dto.getPhone())
                .gender(dto.getGender())
                .avatar(dto.getAvatarUrl())
                .deptName(dto.getDeptName())
                .postNames(AuthContextSupport.safeList(dto.getPostNames()))
                .roles(AuthContextSupport.safeList(dto.getRoles()))
                .loginIp(dto.getLoginIp())
                .loginDate(dto.getLoginDate())
                .createTime(dto.getCreateTime())
                .passwordNeedsChange(dto.getPwdUpdateTime() == null)
                .build();
    }

    /**
     * 更新当前登录用户本人基本资料（邮箱/手机传空白时允许清空绑定，同租户冲突抛出 {@code 102002}）。
     *
     * @param request 个人基本资料更新请求
     * @throws BusinessException 请求参数为空、昵称为空或同租户手机号/邮箱冲突时抛出对应业务异常
     */
    public void updatePersonalProfile(UpdateProfileRequest request) {
        if (request == null || !StringUtils.hasText(request.getNickname())) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "个人资料更新参数及用户昵称不能为空");
        }

        Long ownerTenantId = AuthContextSupport.requireOwnerTenantId();
        Long userId = AuthContextSupport.requireCurrentUserId();
        UpdatePersonalProfileCommand command = UpdatePersonalProfileCommand.builder()
                .nickname(request.getNickname().trim())
                .email(request.getEmail())
                .phone(request.getPhone())
                .gender(request.getGender())
                .build();
        remoteUserService.updatePersonalProfile(ownerTenantId, userId, command);
    }

    /**
     * 前置拦截空文件与 2MB 体积上限后，将头像载荷交由系统域执行魔数校验与事务安全上传。
     *
     * @param command 头像上传跨模块指令
     * @return 动态签发预览 URL 与持久对象键
     * @throws BusinessException 文件为空、超出 2MB 上限或魔数/类型不合法时抛出 {@code 100004}
     */
    public AvatarUploadVO uploadAvatar(UploadAvatarCommand command) {
        if (command == null || command.getContent() == null || command.getContent().length == 0) {
            throw new BusinessException(CommonBizResultCode.FILE_GUARD_REJECTED, "上传头像文件不能为空");
        }
        if (command.getContent().length > MAX_AVATAR_BYTES) {
            throw new BusinessException(CommonBizResultCode.FILE_GUARD_REJECTED, "头像文件大小不能超过 2MB");
        }

        Long ownerTenantId = AuthContextSupport.requireOwnerTenantId();
        Long userId = AuthContextSupport.requireCurrentUserId();
        AvatarUploadDTO dto = remoteUserService.uploadAvatar(ownerTenantId, userId, command);
        return AvatarUploadVO.builder()
                .avatarUrl(dto.getAvatarUrl())
                .objectPath(dto.getObjectPath())
                .build();
    }

    /**
     * 自助修改当前用户登录密码：在认证域内完成旧密核验、强口令复杂度校验与 PBKDF2 哈希计算，落库后注销全部 {@code admin} 会话。
     *
     * @param request 旧密码与新密码载荷
     * @throws BusinessException 旧密码错误抛出 {@code 101006}，新密码违背复杂度策略抛出 {@code 100001}，用户不存在抛出 {@code 404}
     */
    public void updatePassword(UpdatePasswordRequest request) {
        if (request == null || !StringUtils.hasText(request.getOldPassword())
                || !StringUtils.hasText(request.getNewPassword())) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "改密参数不能为空");
        }
        Long ownerTenantId = AuthContextSupport.requireOwnerTenantId();
        Long userId = AuthContextSupport.requireCurrentUserId();

        // 1. 核验当前旧口令并执行强口令复杂度策略校验（失败提前阻断，不触发慢哈希）
        AdminAuthUserDTO authUser = remoteUserService.getAuthUserById(ownerTenantId, userId);
        if (authUser == null) {
            throw new BusinessException(CommonResultCode.NOT_FOUND, "用户账号不存在");
        }
        if (!PasswordUtil.matches(request.getOldPassword(), authUser.getPasswordHash())) {
            throw new BusinessException(AuthResultCode.OLD_PASSWORD_MISMATCH);
        }
        PasswordPolicyValidator.validate(request.getNewPassword());

        // 2. 认证域内完成 PBKDF2 慢哈希计算（零明文跨模块传输），落库后强制注销该用户全部 admin 会话
        String encodedHash = PasswordUtil.hash(request.getNewPassword());
        remoteUserService.updatePasswordHash(ownerTenantId, userId, encodedHash, LocalDateTime.now());

        String compositeLoginId = LoginHelper.buildCompositeLoginId(
                String.valueOf(ownerTenantId),
                String.valueOf(userId)
        );
        LoginHelper.logoutByLoginType(SolidConstants.ADMIN_LOGIN_TYPE, compositeLoginId);
    }
}

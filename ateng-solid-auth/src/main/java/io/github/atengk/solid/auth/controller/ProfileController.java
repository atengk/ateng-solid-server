package io.github.atengk.solid.auth.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.web.annotation.OperateLog;
import io.github.atengk.solid.auth.request.UpdatePasswordRequest;
import io.github.atengk.solid.auth.request.UpdateProfileRequest;
import io.github.atengk.solid.auth.service.ProfileService;
import io.github.atengk.solid.auth.vo.AvatarUploadVO;
import io.github.atengk.solid.auth.vo.PersonalProfileVO;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.command.UploadAvatarCommand;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 个人中心控制器，提供当前用户真明文资料维护、头像上传及自助改密接口（路由前缀：{@code /admin/auth}）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@RestController
@RequestMapping("/admin/auth")
@RequiredArgsConstructor
public class ProfileController {

    private static final long MAX_AVATAR_BYTES = 2L * 1024 * 1024;

    private final ProfileService profileService;

    /**
     * 获取个人中心完整内存真明文资料。
     *
     * @return 个人完整资料
     */
    @SaCheckLogin
    @GetMapping("/profile")
    public Result<PersonalProfileVO> getProfile() {
        return Result.success(profileService.getPersonalProfile());
    }

    /**
     * 更新当前用户本人基本资料。
     *
     * @param request 更新资料载荷
     * @return 空成功响应
     */
    @SaCheckLogin
    @OperateLog(module = "个人中心", type = OperateTypeEnum.UPDATE, description = "更新个人基本资料", excludeParams = {"phone", "email"})
    @PutMapping("/profile")
    public Result<Void> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        profileService.updatePersonalProfile(request);
        return Result.success();
    }

    /**
     * 上传并替换当前用户头像。
     *
     * @param file 客户端上传的图片文件
     * @return 预览访问 URL 与持久对象键
     */
    @SaCheckLogin
    @OperateLog(module = "个人中心", type = OperateTypeEnum.UPDATE, description = "上传个人头像", saveParams = false)
    @PostMapping("/profile/avatar")
    public Result<AvatarUploadVO> uploadAvatar(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(CommonBizResultCode.FILE_GUARD_REJECTED, "上传头像文件不能为空");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new BusinessException(CommonBizResultCode.FILE_GUARD_REJECTED, "头像文件大小不能超过 2MB");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException(CommonBizResultCode.FILE_GUARD_REJECTED, "读取头像文件字节流失败", ex);
        }
        UploadAvatarCommand command = UploadAvatarCommand.builder()
                .originalFilename(file.getOriginalFilename())
                .contentType(file.getContentType())
                .content(content)
                .build();
        return Result.success(profileService.uploadAvatar(command));
    }

    /**
     * 自助修改当前用户登录密码并注销全部 {@code admin} 会话。
     *
     * @param request 旧密码与新密码载荷
     * @return 空成功响应
     */
    @SaCheckLogin
    @OperateLog(module = "个人中心", type = OperateTypeEnum.UPDATE, description = "自助修改登录密码", saveParams = false)
    @PutMapping("/password")
    public Result<Void> updatePassword(@Valid @RequestBody UpdatePasswordRequest request) {
        profileService.updatePassword(request);
        return Result.success();
    }
}

package io.github.atengk.solid.system.api.service;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.solid.system.api.command.UpdatePersonalProfileCommand;
import io.github.atengk.solid.system.api.command.UploadAvatarCommand;
import io.github.atengk.solid.system.api.dto.AdminAuthUserDTO;
import io.github.atengk.solid.system.api.dto.AdminProfileDTO;
import io.github.atengk.solid.system.api.dto.AvatarUploadDTO;
import io.github.atengk.solid.system.api.dto.PersonalProfileDTO;

import java.time.LocalDateTime;

/**
 * 系统用户跨模块远程服务契约，提供认证用户查询、个人资料读写、头像上传与密码哈希落库能力。
 *
 * @author Ateng
 * @since 2026-11-25
 */
public interface RemoteUserService {

    /**
     * 在指定租户下按登录账号查询用于认证的用户信息。
     *
     * @param tenantId 显式定界的租户主键，不可为 {@code null}
     * @param username 登录用户名
     * @return 用户认证信息；用户不存在时返回 {@code null}（防用户名枚举）
     */
    AdminAuthUserDTO getAuthUserByUsername(Long tenantId, String username);

    /**
     * 在指定租户下按用户主键查询用于改密核对的认证用户信息。
     *
     * @param tenantId 显式定界的租户主键，不可为 {@code null}
     * @param userId   用户主键
     * @return 用户认证信息；用户不存在时返回 {@code null}
     */
    AdminAuthUserDTO getAuthUserById(Long tenantId, Long userId);

    /**
     * 获取当前登录用户在指定生效租户视角下的轻量身份摘要。
     *
     * @param tenantId 显式定界的生效租户主键，不可为 {@code null}
     * @param userId   用户主键
     * @return 轻量工作台与顶栏摘要信息
     * @throws BusinessException 目标用户不存在时抛出 {@code SystemResultCode.RECORD_NOT_FOUND}（102001）
     */
    AdminProfileDTO getAdminProfile(Long tenantId, Long userId);

    /**
     * 获取个人中心所需的完整用户资料（包含内存真明文联系方式）。
     *
     * @param tenantId 显式定界的租户主键，不可为 {@code null}
     * @param userId   用户主键
     * @return 个人中心完整用户资料
     * @throws BusinessException 目标用户不存在时抛出 {@code SystemResultCode.RECORD_NOT_FOUND}（102001）
     */
    PersonalProfileDTO getPersonalProfile(Long tenantId, Long userId);

    /**
     * 更新用户本人基本资料，校验同租户内手机号与邮箱唯一性并归一化空串与小写邮箱。
     *
     * @param tenantId 显式定界的租户主键，不可为 {@code null}
     * @param userId   用户主键
     * @param cmd      个人资料更新指令
     * @throws BusinessException 用户不存在时抛出 {@code 102001}，手机号或邮箱冲突时抛出 {@code 102002}
     */
    void updatePersonalProfile(Long tenantId, Long userId, UpdatePersonalProfileCommand cmd);

    /**
     * 上传用户本人头像并更新头像对象键，返回动态签发的内联预览地址。
     *
     * @param tenantId 显式定界的租户主键，不可为 {@code null}
     * @param userId   用户主键
     * @param cmd      头像字节载荷与元信息指令
     * @return 上传后的预览地址与相对对象键
     * @throws BusinessException 文件类型/魔数/大小不合法时抛出 {@code 100004}，用户不存在时抛出 {@code 102001}
     */
    AvatarUploadDTO uploadAvatar(Long tenantId, Long userId, UploadAvatarCommand cmd);

    /**
     * 更新用户已哈希后的口令密文及最近改密时间戳（严禁传输明文口令）。
     *
     * @param tenantId        显式定界的租户主键，不可为 {@code null}
     * @param userId          用户主键
     * @param encodedPassword 认证域完成 PBKDF2 计算后的口令慢哈希字符串
     * @param pwdUpdateTime   本次改密生效时间戳
     * @throws BusinessException 目标用户不存在时抛出 {@code 102001}
     */
    void updatePasswordHash(Long tenantId, Long userId, String encodedPassword, LocalDateTime pwdUpdateTime);
}

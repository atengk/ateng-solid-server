package io.github.atengk.solid.auth.grant;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.boot.satoken.defense.LoginFailureLockService;
import io.github.atengk.boot.web.captcha.template.CaptchaTemplate;
import io.github.atengk.solid.auth.properties.SolidAuthProperties;
import io.github.atengk.solid.auth.result.AuthResultCode;
import io.github.atengk.solid.auth.security.LoginLockedException;
import io.github.atengk.solid.auth.service.TenantResolutionSupport;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.spi.auth.AuthGrantCommand;
import io.github.atengk.solid.common.spi.auth.AuthGrantHandler;
import io.github.atengk.solid.common.spi.auth.AuthenticatedPrincipal;
import io.github.atengk.solid.system.api.dto.AdminAuthUserDTO;
import io.github.atengk.solid.system.api.dto.TenantAuthDTO;
import io.github.atengk.solid.system.api.service.RemotePermissionService;
import io.github.atengk.solid.system.api.service.RemoteUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

/**
 * 账号密码授权策略处理器：严格执行验证码、租户可用性、IP/账号防爆破与 PBKDF2 防枚举 4 步串行校验链。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PasswordAuthGrantHandler implements AuthGrantHandler {

    /**
     * 不存在用户恒定耗时防枚举哑哈希（合法 310,000 次迭代 PBKDF2-SHA256 载荷，16 字节盐 + 32 字节摘要）
     */
    private static final String DUMMY_PASSWORD_HASH =
            "$pbkdf2-sha256$310000$AAECAwQFBgcICQoLDA0ODw$AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";

    private final SolidAuthProperties authProperties;
    private final TenantResolutionSupport tenantResolutionSupport;
    private final ObjectProvider<CaptchaTemplate> captchaTemplateProvider;
    private final RemoteUserService remoteUserService;
    private final RemotePermissionService remotePermissionService;
    private final LoginFailureLockService loginFailureLockService;

    @Override
    public String getGrantType() {
        return SolidConstants.DEFAULT_GRANT_TYPE;
    }

    @Override
    public AuthenticatedPrincipal authenticate(AuthGrantCommand command) {
        if (command == null) {
            throw new BusinessException(AuthResultCode.BAD_CREDENTIALS);
        }

        // 1. 校验一次一密图形验证码，失败直接阻断且不扣除密码重试次数
        verifyCaptchaIfEnabled(command);

        // 2. 解析并验证目标租户存在性、启用状态与有效期，不可用时不计密码失败次数
        TenantAuthDTO tenant = tenantResolutionSupport.resolveAvailableTenant(command);
        String tenantIdStr = String.valueOf(tenant.getTenantId());
        String loginType = StringUtils.hasText(command.getLoginType())
                ? command.getLoginType().trim()
                : SolidConstants.ADMIN_LOGIN_TYPE;
        String username = command.getUsername() != null ? command.getUsername().trim() : "";
        String resolvedIp = resolveEffectiveClientIp(command.getClientIp());

        // 3. 防爆破前置检查：先查客户端 IP 熔断，再查租户作用域账号锁定
        throwIfAlreadyLocked(tenantIdStr, loginType, username, resolvedIp);

        // 4. 在显式租户定界内校验账号与 PBKDF2 口令（防计时枚举 + 停用检查 + 低迭代哈希平滑升级）
        AdminAuthUserDTO authUser = verifyPasswordAndUpgradeIfNeeded(
                tenant.getTenantId(), tenantIdStr, loginType, username, command.getPassword(), resolvedIp
        );

        loginFailureLockService.clearFailure(tenantIdStr, loginType, username);
        List<String> roleKeys = safeList(
                remotePermissionService.getRoleKeysByUserId(tenant.getTenantId(), authUser.getUserId())
        );

        return AuthenticatedPrincipal.builder()
                .userId(authUser.getUserId())
                .tenantId(tenant.getTenantId())
                .tenantCode(tenant.getTenantCode())
                .tenantName(tenant.getTenantName())
                .deptId(authUser.getDeptId())
                .username(authUser.getUsername())
                .nickname(authUser.getNickname())
                .loginType(loginType)
                .roles(roleKeys)
                .permissions(Collections.emptyList())
                .build();
    }

    private AdminAuthUserDTO verifyPasswordAndUpgradeIfNeeded(
            Long tenantId,
            String tenantIdStr,
            String loginType,
            String username,
            String rawPassword,
            String resolvedIp
    ) {
        AdminAuthUserDTO authUser = TenantContextHolder.runWithTenant(
                tenantId,
                () -> remoteUserService.getAuthUserByUsername(tenantId, username)
        );
        boolean passwordProvided = StringUtils.hasText(rawPassword);
        if (authUser == null) {
            if (passwordProvided) {
                PasswordUtil.matches(rawPassword, DUMMY_PASSWORD_HASH);
            }
            recordFailureAndThrow(tenantIdStr, loginType, username, resolvedIp);
        }
        if (!passwordProvided || !PasswordUtil.matches(rawPassword, authUser.getPasswordHash())) {
            recordFailureAndThrow(tenantIdStr, loginType, username, resolvedIp);
        }
        if (!CommonStatusEnum.NORMAL.getCode().equals(authUser.getStatus())) {
            throw new BusinessException(AuthResultCode.ACCOUNT_DISABLED);
        }
        if (PasswordUtil.needsUpgrade(authUser.getPasswordHash())) {
            String upgradedHash = PasswordUtil.hash(rawPassword);
            try {
                TenantContextHolder.runWithTenant(
                        tenantId,
                        () -> remoteUserService.upgradePasswordHash(tenantId, authUser.getUserId(), upgradedHash)
                );
            } catch (RuntimeException ex) {
                log.warn("平滑升级用户口令慢哈希失败，跳过本次写入: tenantId={}, userId={}",
                        tenantId, authUser.getUserId(), ex);
            }
        }
        return authUser;
    }

    private void verifyCaptchaIfEnabled(AuthGrantCommand command) {
        if (!authProperties.isCaptchaEnabled()) {
            return;
        }
        CaptchaTemplate captchaTemplate = captchaTemplateProvider.getIfAvailable();
        if (captchaTemplate == null) {
            throw new IllegalStateException("已启用登录图形验证码，但容器未装配 CaptchaTemplate");
        }
        if (!StringUtils.hasText(command.getCaptchaKey()) || !StringUtils.hasText(command.getCaptchaCode())) {
            throw new BusinessException(AuthResultCode.CAPTCHA_INVALID);
        }
        boolean matched = captchaTemplate.verify(command.getCaptchaKey().trim(), command.getCaptchaCode().trim());
        if (!matched) {
            throw new BusinessException(AuthResultCode.CAPTCHA_INVALID);
        }
    }

    private String resolveEffectiveClientIp(String rawClientIp) {
        String normalized = loginFailureLockService.resolveClientIp(rawClientIp);
        if (StringUtils.hasText(normalized)) {
            return normalized.trim();
        }
        return StringUtils.hasText(rawClientIp) ? rawClientIp.trim() : null;
    }

    private void throwIfAlreadyLocked(String tenantIdStr, String loginType, String username, String resolvedIp) {
        LoginLockedException ipLocked = checkIpLockIfActive(resolvedIp);
        if (ipLocked != null) {
            throw ipLocked;
        }
        if (StringUtils.hasText(username) && loginFailureLockService.isLocked(tenantIdStr, loginType, username)) {
            throw buildAccountLockedException(tenantIdStr, loginType, username);
        }
    }

    private void recordFailureAndThrow(String tenantIdStr, String loginType, String username, String resolvedIp) {
        int remainRetryCount = loginFailureLockService.recordFailure(tenantIdStr, loginType, username, resolvedIp);
        if (remainRetryCount <= 0) {
            LoginLockedException ipLocked = checkIpLockIfActive(resolvedIp);
            if (ipLocked != null) {
                throw ipLocked;
            }
            throw buildAccountLockedException(tenantIdStr, loginType, username);
        }
        throw new BusinessException(AuthResultCode.BAD_CREDENTIALS);
    }

    private LoginLockedException checkIpLockIfActive(String resolvedIp) {
        if (loginFailureLockService.isIpLocked(resolvedIp)) {
            return new LoginLockedException(
                    LoginLockedException.LOCK_TYPE_IP,
                    loginFailureLockService.getIpLockTime(resolvedIp)
            );
        }
        return null;
    }

    private LoginLockedException buildAccountLockedException(String tenantIdStr, String loginType, String username) {
        return new LoginLockedException(
                LoginLockedException.LOCK_TYPE_ACCOUNT,
                loginFailureLockService.getDisableTime(tenantIdStr, loginType, username)
        );
    }

    private static List<String> safeList(List<String> values) {
        return CollectionUtils.isEmpty(values) ? Collections.emptyList() : values;
    }
}

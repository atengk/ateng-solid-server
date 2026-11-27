package io.github.atengk.solid.auth.security;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.satoken.util.LoginHelper;
import io.github.atengk.solid.auth.result.AuthResultCode;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.properties.SolidTenantProperties;
import io.github.atengk.solid.system.api.service.RemoteTenantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 租户运行时可用性拦截器（始终装配，运行于 {@code AtengSaTokenInterceptor} 之后）：
 * 校验普通租户登录态归属租户是否在当前模式及运行时可用，多租户已关闭或租户停用/过期时立即注销会话并抛出 {@code 101005}。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Component
@RequiredArgsConstructor
public class TenantAvailabilityInterceptor implements HandlerInterceptor {

    private final RemoteTenantService remoteTenantService;
    private final SolidTenantProperties solidTenantProperties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!LoginHelper.isLogin(SolidConstants.ADMIN_LOGIN_TYPE)) {
            return true;
        }
        String ownerTenantId = LoginHelper.getTenantId(SolidConstants.ADMIN_LOGIN_TYPE);
        if (!StringUtils.hasText(ownerTenantId)
                || SolidConstants.PLATFORM_TENANT_ID_STR.equals(ownerTenantId.trim())) {
            return true;
        }
        if (!solidTenantProperties.isEnabled()) {
            LoginHelper.logout(SolidConstants.ADMIN_LOGIN_TYPE);
            throw new BusinessException(AuthResultCode.TENANT_UNAVAILABLE);
        }
        Long tenantId = parseTenantId(ownerTenantId);
        if (tenantId == null || !remoteTenantService.isTenantAvailable(tenantId)) {
            LoginHelper.logout(SolidConstants.ADMIN_LOGIN_TYPE);
            throw new BusinessException(AuthResultCode.TENANT_UNAVAILABLE);
        }
        return true;
    }

    private static Long parseTenantId(String tenantId) {
        try {
            return Long.parseLong(tenantId.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}

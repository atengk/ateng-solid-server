package io.github.atengk.solid.system.security;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.satoken.cache.UserRolePermissionLoader;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.system.api.service.RemotePermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

/**
 * Sa-Token 二级权限缓存回源加载器 SPI 实现：全面实现 {@link UserRolePermissionLoader} 三参重载（含 {@code loginType} 与 {@code tenantId}），隔离非管理端账号体系并透传显式租户边界。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Component
@RequiredArgsConstructor
public class SolidUserRolePermissionLoader implements UserRolePermissionLoader {

    private final RemotePermissionService remotePermissionService;

    @Override
    public List<String> loadRolesByUserId(String userId) {
        return loadRolesByUserId(userId, SolidConstants.ADMIN_LOGIN_TYPE, null);
    }

    @Override
    public List<String> loadRolesByUserId(String userId, String loginType) {
        return loadRolesByUserId(userId, loginType, null);
    }

    @Override
    public List<String> loadRolesByUserId(String userId, String loginType, String tenantId) {
        if (!isSupportedAdminLoginType(loginType)) {
            return Collections.emptyList();
        }
        Long parsedUserId = parseUserId(userId);
        Long parsedTenantId = resolveTenantId(tenantId);
        if (parsedUserId == null || parsedTenantId == null) {
            return Collections.emptyList();
        }
        return remotePermissionService.getRoleKeysByUserId(parsedTenantId, parsedUserId);
    }

    @Override
    public List<String> loadPermissionsByRole(String roleCode) {
        return loadPermissionsByRole(roleCode, SolidConstants.ADMIN_LOGIN_TYPE, null);
    }

    @Override
    public List<String> loadPermissionsByRole(String roleCode, String loginType) {
        return loadPermissionsByRole(roleCode, loginType, null);
    }

    @Override
    public List<String> loadPermissionsByRole(String roleCode, String loginType, String tenantId) {
        if (!isSupportedAdminLoginType(loginType) || !StringUtils.hasText(roleCode)) {
            return Collections.emptyList();
        }
        Long parsedTenantId = resolveTenantId(tenantId);
        if (parsedTenantId == null) {
            return Collections.emptyList();
        }
        return remotePermissionService.getPermissionsByRoleKey(parsedTenantId, roleCode.trim());
    }

    @Override
    public List<String> loadDirectPermissionsByUserId(String userId) {
        return loadDirectPermissionsByUserId(userId, SolidConstants.ADMIN_LOGIN_TYPE, null);
    }

    @Override
    public List<String> loadDirectPermissionsByUserId(String userId, String loginType) {
        return loadDirectPermissionsByUserId(userId, loginType, null);
    }

    @Override
    public List<String> loadDirectPermissionsByUserId(String userId, String loginType, String tenantId) {
        if (!isSupportedAdminLoginType(loginType)) {
            return Collections.emptyList();
        }
        Long parsedUserId = parseUserId(userId);
        Long parsedTenantId = resolveTenantId(tenantId);
        if (parsedUserId == null || parsedTenantId == null) {
            return Collections.emptyList();
        }
        return remotePermissionService.getDirectPermissionsByUserId(parsedTenantId, parsedUserId);
    }

    private static boolean isSupportedAdminLoginType(String loginType) {
        return !StringUtils.hasText(loginType) || SolidConstants.ADMIN_LOGIN_TYPE.equals(loginType.trim());
    }

    private static Long parseUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            return null;
        }
        String trimmed = userId.trim();
        int colonIdx = trimmed.lastIndexOf(':');
        String rawId = colonIdx >= 0 ? trimmed.substring(colonIdx + 1) : trimmed;
        if (!StringUtils.hasText(rawId)) {
            return null;
        }
        try {
            return Long.parseLong(rawId);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Long resolveTenantId(String tenantId) {
        if (StringUtils.hasText(tenantId)) {
            try {
                return Long.parseLong(tenantId.trim());
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        Long contextTenantId = TenantContextHolder.getAsLong();
        return contextTenantId != null ? contextTenantId : SolidConstants.PLATFORM_TENANT_ID;
    }
}

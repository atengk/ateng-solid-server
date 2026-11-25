package io.github.atengk.solid.system.security;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.satoken.spi.TenantHeaderSwitchValidator;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.properties.SolidTenantProperties;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.service.RemoteTenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * 超级管理员跨租户切换请求头（{@code X-Tenant-Id}）可用性校验 SPI 实现：未开启多租户模式时仅放行平台默认租户 {@code 0}；开启后校验目标租户可用性，失败统一抛出 {@code 100002} 绝不注销原会话。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Component
@RequiredArgsConstructor
public class TenantHeaderSwitchValidatorImpl implements TenantHeaderSwitchValidator {

    private final RemoteTenantService remoteTenantService;
    private final SolidTenantProperties tenantProperties;

    @Override
    public boolean validate(String targetTenantId, String currentTenantId, String loginType, boolean anonymous) {
        if (anonymous || !StringUtils.hasText(targetTenantId)) {
            throw new BusinessException(CommonBizResultCode.INVALID_TENANT_SWITCH);
        }
        Long tenantId;
        try {
            tenantId = Long.parseLong(targetTenantId.trim());
        } catch (NumberFormatException ex) {
            throw new BusinessException(CommonBizResultCode.INVALID_TENANT_SWITCH);
        }
        if (tenantId < 0) {
            throw new BusinessException(CommonBizResultCode.INVALID_TENANT_SWITCH);
        }
        boolean multiTenantEnabled = tenantProperties != null && tenantProperties.isEnabled();
        if (!multiTenantEnabled && !Objects.equals(tenantId, SolidConstants.PLATFORM_TENANT_ID)) {
            throw new BusinessException(CommonBizResultCode.INVALID_TENANT_SWITCH);
        }
        if (!remoteTenantService.isTenantAvailable(tenantId)) {
            throw new BusinessException(CommonBizResultCode.INVALID_TENANT_SWITCH);
        }
        return true;
    }
}

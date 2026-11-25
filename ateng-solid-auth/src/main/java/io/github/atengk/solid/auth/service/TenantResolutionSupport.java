package io.github.atengk.solid.auth.service;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.solid.auth.result.AuthResultCode;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.properties.SolidTenantProperties;
import io.github.atengk.solid.common.spi.auth.AuthGrantCommand;
import io.github.atengk.solid.system.api.dto.TenantAuthDTO;
import io.github.atengk.solid.system.api.service.RemoteTenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 认证域租户解析与可用性校验协作者：兼容 {@code tenantCode} 与 {@code X-Tenant-Id} 请求头两种登录入口参数，并复用至登录校验与失败审计兜底。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Component
@RequiredArgsConstructor
public class TenantResolutionSupport {

    /**
     * {@link AuthGrantCommand#getExtras()} 中透传 {@code X-Tenant-Id} 请求头的键名
     */
    public static final String EXTRA_TENANT_ID_HEADER = "tenantIdHeader";

    private static final String DEFAULT_TENANT_NAME = "平台默认租户";

    private final SolidTenantProperties tenantProperties;
    private final RemoteTenantService remoteTenantService;

    /**
     * 指示当前是否开启 SaaS 多租户模式。
     *
     * @return 开启多租户模式返回 {@code true}
     */
    public boolean isMultiTenantEnabled() {
        return tenantProperties != null && tenantProperties.isEnabled();
    }

    /**
     * 解析并校验目标登录租户是否可用；优先匹配显式传入的 {@code tenantCode}，空白时兼容识别 {@code X-Tenant-Id} 请求头，均为空时回退默认租户 {@code 000000}。
     *
     * @param command 认证授权指令
     * @return 可用租户摘要信息
     * @throws BusinessException 租户不存在、已停用、已过期或格式非法时抛出 {@code 101005}
     */
    public TenantAuthDTO resolveAvailableTenant(AuthGrantCommand command) {
        if (!isMultiTenantEnabled()) {
            return resolveSingleTenantDefault();
        }

        TenantAuthDTO tenant = lookupTenantByCommand(command);
        if (tenant == null || !tenant.isAvailableAt(LocalDateTime.now())) {
            throw new BusinessException(AuthResultCode.TENANT_UNAVAILABLE);
        }
        return tenant;
    }

    /**
     * 为失败审计日志解析目标租户主键，未命中或异常时安全兜底为平台默认租户 {@code 0L}。
     *
     * @param command 认证授权指令
     * @return 目标租户主键（未解析出时返回 {@code 0L}）
     */
    public Long resolveTenantIdForAudit(AuthGrantCommand command) {
        if (!isMultiTenantEnabled() || command == null) {
            return SolidConstants.PLATFORM_TENANT_ID;
        }
        TenantAuthDTO tenant = lookupTenantByCommand(command);
        return tenant != null && tenant.getTenantId() != null
                ? tenant.getTenantId()
                : SolidConstants.PLATFORM_TENANT_ID;
    }

    private TenantAuthDTO resolveSingleTenantDefault() {
        TenantAuthDTO platformTenant = remoteTenantService.getTenantById(SolidConstants.PLATFORM_TENANT_ID);
        if (platformTenant == null) {
            return TenantAuthDTO.builder()
                    .tenantId(SolidConstants.PLATFORM_TENANT_ID)
                    .tenantCode(SolidConstants.DEFAULT_TENANT_CODE)
                    .tenantName(DEFAULT_TENANT_NAME)
                    .status(CommonStatusEnum.NORMAL.getCode())
                    .build();
        }
        if (!platformTenant.isAvailableAt(LocalDateTime.now())) {
            throw new BusinessException(AuthResultCode.TENANT_UNAVAILABLE);
        }
        return platformTenant;
    }

    private TenantAuthDTO lookupTenantByCommand(AuthGrantCommand command) {
        String tenantCode = command != null ? command.getTenantCode() : null;
        if (StringUtils.hasText(tenantCode)) {
            return remoteTenantService.getTenantByCode(tenantCode.trim());
        }
        String headerTenantId = extractHeaderTenantId(command);
        if (StringUtils.hasText(headerTenantId)) {
            Long parsedId = AuthContextSupport.parseLongOrNull(headerTenantId);
            return parsedId != null ? remoteTenantService.getTenantById(parsedId) : null;
        }
        return remoteTenantService.getTenantByCode(SolidConstants.DEFAULT_TENANT_CODE);
    }

    private static String extractHeaderTenantId(AuthGrantCommand command) {
        if (command == null || command.getExtras() == null) {
            return null;
        }
        Map<String, Object> extras = command.getExtras();
        Object rawHeader = extras.get(EXTRA_TENANT_ID_HEADER);
        return rawHeader != null ? String.valueOf(rawHeader) : null;
    }
}

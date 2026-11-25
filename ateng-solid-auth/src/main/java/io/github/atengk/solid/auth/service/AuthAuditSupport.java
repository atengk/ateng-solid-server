package io.github.atengk.solid.auth.service;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.web.trace.TraceUtil;
import io.github.atengk.solid.auth.result.AuthResultCode;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.LogStatusEnum;
import io.github.atengk.solid.common.event.DomainEventPublisher;
import io.github.atengk.solid.common.event.LoginAuditEvent;
import io.github.atengk.solid.common.spi.auth.AuthGrantCommand;
import io.github.atengk.solid.common.spi.auth.AuthenticatedPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * 认证域登录与登出审计事件发布协作者，统一收敛成功、失败与注销全链路 {@link LoginAuditEvent} 装配与租户归属兜底。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthAuditSupport {

    private static final int MAX_MESSAGE_LENGTH = 255;

    private final DomainEventPublisher domainEventPublisher;
    private final TenantResolutionSupport tenantResolutionSupport;

    /**
     * 发布登录成功审计事件。
     *
     * @param principal 认证通过的主体信息
     * @param clientIp  客户端来源 IP
     * @param userAgent 客户端原始 User-Agent
     */
    public void publishLoginSuccess(AuthenticatedPrincipal principal, String clientIp, String userAgent) {
        if (principal == null) {
            return;
        }
        Long tenantId = principal.getTenantId() != null ? principal.getTenantId() : SolidConstants.PLATFORM_TENANT_ID;
        domainEventPublisher.publish(LoginAuditEvent.builder()
                .tenantId(tenantId)
                .traceId(TraceUtil.getTraceId())
                .userId(principal.getUserId())
                .username(principal.getUsername())
                .loginType(defaultLoginType(principal.getLoginType()))
                .clientIp(clientIp)
                .userAgent(userAgent)
                .status(LogStatusEnum.SUCCESS.getCode())
                .message("登录成功")
                .build());
    }

    /**
     * 安全发布登录失败审计事件：审计事件构建或发布自身异常仅记警告日志，绝不覆盖原始认证失败异常。
     *
     * @param command 原始认证输入指令
     * @param cause   导致登录失败的异常
     */
    public void publishLoginFailure(AuthGrantCommand command, Exception cause) {
        if (command == null) {
            return;
        }
        try {
            Long tenantId = resolveFailureTenantId(command, cause);
            String message = resolveFailureMessage(cause);
            String username = StringUtils.hasText(command.getUsername()) ? command.getUsername().trim() : "";

            domainEventPublisher.publish(LoginAuditEvent.builder()
                    .tenantId(tenantId)
                    .traceId(TraceUtil.getTraceId())
                    .userId(null)
                    .username(username)
                    .loginType(defaultLoginType(command.getLoginType()))
                    .clientIp(command.getClientIp())
                    .userAgent(command.getUserAgent())
                    .status(LogStatusEnum.FAIL.getCode())
                    .message(truncateMessage(message))
                    .build());
        } catch (RuntimeException auditEx) {
            log.warn("发布登录失败审计事件异常（已忽略以透传原始认证异常）: username={}",
                    command.getUsername(), auditEx);
        }
    }

    /**
     * 发布注销登出成功审计事件。
     *
     * @param tenantId  会话归属租户主键
     * @param userId    用户主键
     * @param username  登录账号
     * @param clientIp  客户端来源 IP
     * @param userAgent 客户端原始 User-Agent
     */
    public void publishLogoutSuccess(
            Long tenantId,
            Long userId,
            String username,
            String clientIp,
            String userAgent
    ) {
        domainEventPublisher.publish(LoginAuditEvent.builder()
                .tenantId(tenantId != null ? tenantId : SolidConstants.PLATFORM_TENANT_ID)
                .traceId(TraceUtil.getTraceId())
                .userId(userId)
                .username(username != null ? username : "")
                .loginType(SolidConstants.ADMIN_LOGIN_TYPE)
                .clientIp(clientIp)
                .userAgent(userAgent)
                .status(LogStatusEnum.SUCCESS.getCode())
                .message("退出登录成功")
                .build());
    }

    private Long resolveFailureTenantId(AuthGrantCommand command, Exception cause) {
        if (cause instanceof BusinessException be
                && Objects.equals(AuthResultCode.CAPTCHA_INVALID.getCode(), be.getCode())) {
            return SolidConstants.PLATFORM_TENANT_ID;
        }
        return tenantResolutionSupport.resolveTenantIdForAudit(command);
    }

    private static String resolveFailureMessage(Exception cause) {
        if (cause instanceof BusinessException be && StringUtils.hasText(be.getMsg())) {
            return be.getMsg();
        }
        return cause != null && StringUtils.hasText(cause.getMessage()) ? cause.getMessage() : "登录认证失败";
    }

    private static String truncateMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return "登录认证失败";
        }
        String trimmed = message.trim();
        return trimmed.length() <= MAX_MESSAGE_LENGTH ? trimmed : trimmed.substring(0, MAX_MESSAGE_LENGTH);
    }

    private static String defaultLoginType(String loginType) {
        return StringUtils.hasText(loginType) ? loginType.trim() : SolidConstants.ADMIN_LOGIN_TYPE;
    }
}

package io.github.atengk.solid.system.listener;

import io.github.atengk.solid.common.event.LoginAuditEvent;
import io.github.atengk.solid.system.api.command.LoginAuditCommand;
import io.github.atengk.solid.system.api.service.RemoteAuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 认证域登录与登出领域事件异步监听器：将 {@link LoginAuditEvent} 转换为命令并委派 {@link RemoteAuditLogService} 异步幂等落库，隔离非核心审计异常避免干扰主登录流程。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginAuditEventListener {

    private final RemoteAuditLogService remoteAuditLogService;

    /**
     * 异步消费登录/登出审计领域事件并写入登录日志表。
     *
     * @param event 登录与登出审计事件
     */
    @Async
    @EventListener
    public void onLoginAudit(LoginAuditEvent event) {
        if (event == null) {
            return;
        }
        LoginAuditCommand command = LoginAuditCommand.builder()
                .eventId(event.getEventId())
                .occurredAt(event.getOccurredAt())
                .tenantId(event.getTenantId())
                .traceId(event.getTraceId())
                .userId(event.getUserId())
                .username(event.getUsername())
                .loginType(event.getLoginType())
                .clientIp(event.getClientIp())
                .userAgent(event.getUserAgent())
                .status(event.getStatus())
                .action(event.getAction())
                .message(event.getMessage())
                .build();
        try {
            remoteAuditLogService.recordLoginAudit(command);
        } catch (Exception ex) {
            log.error("异步消费登录审计事件失败: eventId={}, traceId={}, username={}",
                    event.getEventId(), event.getTraceId(), event.getUsername(), ex);
        }
    }
}

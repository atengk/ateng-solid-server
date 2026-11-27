package io.github.atengk.solid.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 登录与登出审计领域事件，由认证域在登录成功、失败及主动登出时发布，系统域异步消费写入登录审计日志。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginAuditEvent implements DomainEvent {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 登录审计动作标识
     */
    public static final String ACTION_LOGIN = "LOGIN";

    /**
     * 登出审计动作标识
     */
    public static final String ACTION_LOGOUT = "LOGOUT";

    /**
     * 事件全局唯一标识（用于日志写入幂等）
     */
    @Builder.Default
    private String eventId = UUID.randomUUID().toString();

    /**
     * 事件触发时间
     */
    @Builder.Default
    private LocalDateTime occurredAt = LocalDateTime.now();

    /**
     * 目标租户主键（无法解析目标租户时记录平台租户 0）
     */
    private Long tenantId;

    /**
     * 全链路追踪标识
     */
    private String traceId;

    /**
     * 认证主体用户主键（登录失败且未能匹配用户时可为 null）
     */
    private Long userId;

    /**
     * 尝试登录的账号名
     */
    private String username;

    /**
     * 多账号登录体系标识（如 admin）
     */
    private String loginType;

    /**
     * 客户端来源 IP
     */
    private String clientIp;

    /**
     * 客户端原始 User-Agent 请求头（系统域负责解析浏览器与操作系统）
     */
    private String userAgent;

    /**
     * 执行状态（0=成功，1=失败，对齐 LogStatusEnum）
     */
    private Integer status;

    /**
     * 审计动作类型（{@link #ACTION_LOGIN} / {@link #ACTION_LOGOUT}）
     */
    private String action;

    /**
     * 认证结果或失败提示摘要
     */
    private String message;
}

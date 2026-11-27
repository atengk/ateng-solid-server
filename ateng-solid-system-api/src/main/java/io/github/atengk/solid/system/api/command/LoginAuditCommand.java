package io.github.atengk.solid.system.api.command;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 登录审计记录持久化指令，用于跨模块落库登录日志并按需回写用户最近登录轨迹。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginAuditCommand implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 事件全局唯一标识（保障重复投递幂等）
     */
    private String eventId;

    /**
     * 审计发生时间
     */
    private LocalDateTime occurredAt;

    /**
     * 所属租户主键
     */
    private Long tenantId;

    /**
     * 全链路追踪标识
     */
    private String traceId;

    /**
     * 认证主体用户主键（失败且未定位用户时可为空）
     */
    private Long userId;

    /**
     * 登录尝试账号
     */
    private String username;

    /**
     * 登录账号体系标识（如 admin）
     */
    private String loginType;

    /**
     * 客户端来源 IP
     */
    private String clientIp;

    /**
     * 原始 User-Agent 字符串
     */
    private String userAgent;

    /**
     * 执行状态（0=成功，1=失败）
     */
    private Integer status;

    /**
     * 审计动作类型（{@code LOGIN} / {@code LOGOUT}）
     */
    private String action;

    /**
     * 提示消息摘要
     */
    private String message;
}

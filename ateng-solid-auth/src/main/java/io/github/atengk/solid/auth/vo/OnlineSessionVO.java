package io.github.atengk.solid.auth.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管理端在线会话只读列表项视图对象（仅下发不透明会话句柄与掩码令牌，严禁暴露原始 Token）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnlineSessionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 服务端签密的不透明会话操作句柄（用于强退请求路径参数）
     */
    private String sessionId;

    /**
     * 脱敏展示令牌串（如 {@code f9a2c1****7e3b}）
     */
    private String tokenMask;

    /**
     * 是否为当前请求所属自身会话
     */
    private boolean currentSession;

    /**
     * 用户主键（JSON 序列化为字符串）
     */
    private Long userId;

    /**
     * 登录账号
     */
    private String username;

    /**
     * 用户昵称
     */
    private String nickname;

    /**
     * 所属租户主键（JSON 序列化为字符串）
     */
    private Long tenantId;

    /**
     * 登录终端类型
     */
    private String loginDevice;

    /**
     * 客户端来源 IP
     */
    private String clientIp;

    /**
     * 会话创建登录时间
     */
    private LocalDateTime loginTime;

    /**
     * 令牌剩余有效期（秒，{@code -1} 表示永不过期）
     */
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private long tokenTimeout;
}

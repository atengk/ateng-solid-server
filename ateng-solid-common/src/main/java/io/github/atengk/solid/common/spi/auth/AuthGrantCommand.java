package io.github.atengk.solid.common.spi.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

/**
 * 认证授权策略统一输入指令，封装授权类型、租户识别码、账号凭据与请求来源上下文。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "password")
public class AuthGrantCommand implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 授权模式标识（如 password）
     */
    private String grantType;

    /**
     * 登录账号体系标识（如 admin）
     */
    private String loginType;

    /**
     * 目标租户业务编码（单租户模式默认 000000）
     */
    private String tenantCode;

    /**
     * 登录用户名
     */
    private String username;

    /**
     * 登录密码或凭据密钥（严禁序列化至日志）
     */
    private String password;

    /**
     * 图形验证码唯一标识
     */
    private String captchaKey;

    /**
     * 用户提交的验证码文本
     */
    private String captchaCode;

    /**
     * 客户端请求来源 IP
     */
    private String clientIp;

    /**
     * 客户端原始 User-Agent
     */
    private String userAgent;

    /**
     * 供后续扩展授权模式（如短信、三方 OAuth2）携带的附加参数（无附加参数时保底为空集合）
     */
    @Builder.Default
    private Map<String, Object> extras = Collections.emptyMap();
}

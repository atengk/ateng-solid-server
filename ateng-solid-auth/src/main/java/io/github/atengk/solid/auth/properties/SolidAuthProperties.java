package io.github.atengk.solid.auth.properties;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 管理端认证策略与在线会话句柄加密配置属性（前缀：{@code ateng.solid.auth}）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@ToString(exclude = "sessionHandleSecret")
@ConfigurationProperties(prefix = "ateng.solid.auth")
public class SolidAuthProperties {

    /**
     * 是否在管理端登录入口强制启用图形验证码校验；默认 {@code true}
     */
    private boolean captchaEnabled = true;

    /**
     * 在线会话不透明句柄 AES-256-GCM 签名加密密钥；单机环境留空将自动生成进程内 32 字节随机密钥并告警，集群部署须配置统一密钥
     */
    private String sessionHandleSecret = "";
}

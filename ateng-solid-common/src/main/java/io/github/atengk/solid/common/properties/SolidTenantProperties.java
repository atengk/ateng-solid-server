package io.github.atengk.solid.common.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 多租户运行模式与状态缓存强类型配置属性（前缀：{@code ateng.solid.tenant}）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@ConfigurationProperties(prefix = "ateng.solid.tenant")
public class SolidTenantProperties {

    /**
     * 是否开启 SaaS 多租户业务模式；默认 {@code false}（平台单租户开箱即用模式）
     */
    private boolean enabled = false;

    /**
     * 租户状态缓存有效期（默认 60 秒；读取时仍会实时比对过期时间以实现 0 秒过期截断）
     */
    private Duration statusCacheTtl = Duration.ofSeconds(60);
}

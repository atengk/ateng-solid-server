package io.github.atengk.solid.auth.security;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 认证域 Spring MVC 拦截器配置：当多租户模式开启时注册 {@link TenantAvailabilityInterceptor}（order=10），位于 Sa-Token 鉴权之后生效。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ateng.solid.tenant", name = "enabled", havingValue = "true")
public class AuthWebMvcConfigurer implements WebMvcConfigurer {

    private static final int TENANT_AVAILABILITY_INTERCEPTOR_ORDER = 10;

    private final TenantAvailabilityInterceptor tenantAvailabilityInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantAvailabilityInterceptor)
                .addPathPatterns("/admin/**")
                .excludePathPatterns(
                        "/admin/auth/captcha",
                        "/admin/auth/login",
                        "/admin/auth/logout"
                )
                .order(TENANT_AVAILABILITY_INTERCEPTOR_ORDER);
    }
}

package io.github.atengk.solid.auth.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 认证域 Spring MVC 拦截器配置：注册 {@link TenantAvailabilityInterceptor}（order=10），位于 Sa-Token 鉴权之后生效。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Configuration
@RequiredArgsConstructor
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

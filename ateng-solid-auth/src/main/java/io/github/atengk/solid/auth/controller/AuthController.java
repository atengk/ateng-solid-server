package io.github.atengk.solid.auth.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.satoken.annotation.Anonymous;
import io.github.atengk.boot.web.util.IpUtil;
import io.github.atengk.boot.web.util.ServletUtil;
import io.github.atengk.solid.auth.request.AdminLoginRequest;
import io.github.atengk.solid.auth.service.AuthService;
import io.github.atengk.solid.auth.vo.AdminLoginVO;
import io.github.atengk.solid.auth.vo.AdminProfileVO;
import io.github.atengk.solid.auth.vo.CaptchaVO;
import io.github.atengk.solid.auth.vo.RouteMenuVO;
import io.github.atengk.solid.common.constant.SolidConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端认证中心控制器，提供验证码下发、多租户登录注销及当前用户身份与动态路由接口（路由前缀：{@code /admin/auth}）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@RestController
@RequestMapping("/admin/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 拉取登录前置图形验证码与多租户开启状态（匿名开放）。
     *
     * @return 验证码及开关配置包装响应
     */
    @Anonymous
    @GetMapping("/captcha")
    public Result<CaptchaVO> captcha() {
        return Result.success(authService.getCaptcha());
    }

    /**
     * 管理端账号登录认证并签发 {@code satoken-admin} 令牌（匿名开放）。
     *
     * @param request        登录请求载荷
     * @param tenantIdHeader 可选工作租户请求头（开启多租户且未传 {@code tenantCode} 时作为兜底租户标识）
     * @param httpRequest    当前 HTTP 请求（用于解析来源 IP 与 User-Agent）
     * @return 登录凭据与生效租户摘要
     */
    @Anonymous
    @PostMapping("/login")
    public Result<AdminLoginVO> login(
            @Valid @RequestBody AdminLoginRequest request,
            @RequestHeader(value = SolidConstants.TENANT_HEADER_NAME, required = false) String tenantIdHeader,
            HttpServletRequest httpRequest
    ) {
        String clientIp = IpUtil.getClientIp(httpRequest);
        String userAgent = ServletUtil.getUserAgent(httpRequest);
        return Result.success(authService.login(request, tenantIdHeader, clientIp, userAgent));
    }

    /**
     * 注销当前管理端登录会话。
     *
     * @param httpRequest 当前 HTTP 请求
     * @return 空成功响应
     */
    @SaCheckLogin
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest httpRequest) {
        String clientIp = IpUtil.getClientIp(httpRequest);
        String userAgent = ServletUtil.getUserAgent(httpRequest);
        authService.logout(clientIp, userAgent);
        return Result.success();
    }

    /**
     * 获取当前生效租户视角下的登录用户轻量身份摘要与实时权限集合。
     *
     * @return 轻量身份摘要
     */
    @SaCheckLogin
    @GetMapping("/info")
    public Result<AdminProfileVO> info() {
        return Result.success(authService.getAdminInfo());
    }

    /**
     * 获取当前生效租户视角下的嵌套动态菜单路由树。
     *
     * @return 嵌套菜单路由树
     */
    @SaCheckLogin
    @GetMapping("/routes")
    public Result<List<RouteMenuVO>> routes() {
        return Result.success(authService.getAdminRoutes());
    }
}

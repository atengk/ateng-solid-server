package io.github.atengk.solid.auth.service;

import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.satoken.model.LoginUser;
import io.github.atengk.boot.satoken.util.LoginHelper;
import io.github.atengk.boot.web.captcha.model.CaptchaResult;
import io.github.atengk.boot.web.captcha.template.CaptchaTemplate;
import io.github.atengk.solid.auth.properties.SolidAuthProperties;
import io.github.atengk.solid.auth.request.AdminLoginRequest;
import io.github.atengk.solid.auth.vo.AdminLoginVO;
import io.github.atengk.solid.auth.vo.AdminProfileVO;
import io.github.atengk.solid.auth.vo.CaptchaVO;
import io.github.atengk.solid.auth.vo.RouteMenuVO;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.common.spi.auth.AuthGrantCommand;
import io.github.atengk.solid.common.spi.auth.AuthGrantHandler;
import io.github.atengk.solid.common.spi.auth.AuthenticatedPrincipal;
import io.github.atengk.solid.system.api.dto.AdminProfileDTO;
import io.github.atengk.solid.system.api.dto.RouteMenuDTO;
import io.github.atengk.solid.system.api.service.RemotePermissionService;
import io.github.atengk.solid.system.api.service.RemoteUserService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 管理端认证服务：协调验证码前置下发、{@link AuthGrantHandler} 授权路由、{@code satoken-admin} 令牌签发注销及身份路由查询。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Service
public class AuthService {

    private final Map<String, AuthGrantHandler> grantHandlerMap;
    private final SolidAuthProperties authProperties;
    private final ObjectProvider<CaptchaTemplate> captchaTemplateProvider;
    private final RemoteUserService remoteUserService;
    private final RemotePermissionService remotePermissionService;
    private final TenantResolutionSupport tenantResolutionSupport;
    private final AuthAuditSupport authAuditSupport;

    /**
     * 构造认证服务并建立不可变授权策略路由表。
     *
     * @param grantHandlers           容器中全部授权策略处理器
     * @param authProperties          管理端认证配置属性
     * @param captchaTemplateProvider 图形验证码模板提供者
     * @param remoteUserService       远程用户服务契约
     * @param remotePermissionService 远程权限与路由契约
     * @param tenantResolutionSupport 认证域租户解析协作者
     * @param authAuditSupport        登录审计发布协作者
     */
    public AuthService(
            List<AuthGrantHandler> grantHandlers,
            SolidAuthProperties authProperties,
            ObjectProvider<CaptchaTemplate> captchaTemplateProvider,
            RemoteUserService remoteUserService,
            RemotePermissionService remotePermissionService,
            TenantResolutionSupport tenantResolutionSupport,
            AuthAuditSupport authAuditSupport
    ) {
        if (grantHandlers == null || grantHandlers.isEmpty()) {
            this.grantHandlerMap = Collections.emptyMap();
        } else {
            Map<String, AuthGrantHandler> handlerMap = new LinkedHashMap<>(grantHandlers.size());
            for (AuthGrantHandler handler : grantHandlers) {
                if (handler == null || !StringUtils.hasText(handler.getGrantType())) {
                    throw new IllegalStateException("AuthGrantHandler 的 grantType 不能为空");
                }
                String grantType = handler.getGrantType().trim();
                AuthGrantHandler previous = handlerMap.putIfAbsent(grantType, handler);
                if (previous != null) {
                    throw new IllegalStateException("检测到重复注册的 AuthGrantHandler grantType: " + grantType);
                }
            }
            this.grantHandlerMap = Collections.unmodifiableMap(handlerMap);
        }
        this.authProperties = authProperties;
        this.captchaTemplateProvider = captchaTemplateProvider;
        this.remoteUserService = remoteUserService;
        this.remotePermissionService = remotePermissionService;
        this.tenantResolutionSupport = tenantResolutionSupport;
        this.authAuditSupport = authAuditSupport;
    }

    /**
     * 获取登录前置配置与图形验证码载荷。
     *
     * @return 包含验证码与多租户开启状态的视图对象
     */
    public CaptchaVO getCaptcha() {
        boolean captchaEnabled = authProperties != null && authProperties.isCaptchaEnabled();
        boolean tenantEnabled = tenantResolutionSupport.isMultiTenantEnabled();
        if (!captchaEnabled) {
            return CaptchaVO.builder()
                    .enabled(false)
                    .tenantEnabled(tenantEnabled)
                    .captchaKey("")
                    .imageBase64("")
                    .expireSeconds(0L)
                    .build();
        }
        CaptchaTemplate captchaTemplate = captchaTemplateProvider.getIfAvailable();
        if (captchaTemplate == null) {
            throw new IllegalStateException("已启用登录图形验证码，但容器未装配 CaptchaTemplate");
        }
        CaptchaResult generated = captchaTemplate.generate();
        return CaptchaVO.builder()
                .enabled(true)
                .tenantEnabled(tenantEnabled)
                .captchaKey(generated.captchaKey())
                .imageBase64(generated.imageBase64())
                .expireSeconds(generated.expireSeconds())
                .build();
    }

    /**
     * 执行管理端登录认证、签发 {@code satoken-admin} 会话并发布全链路登录审计事件。
     *
     * @param request        登录请求参数
     * @param tenantIdHeader 可选 {@code X-Tenant-Id} 请求头
     * @param clientIp       客户端来源 IP
     * @param userAgent      客户端原始 User-Agent
     * @return 登录凭据与生效租户摘要
     * @throws BusinessException 请求非法、授权模式不支持或认证前置校验失败时抛出对应业务异常
     */
    public AdminLoginVO login(
            AdminLoginRequest request,
            String tenantIdHeader,
            String clientIp,
            String userAgent
    ) {
        if (request == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "登录请求参数不能为空");
        }
        String grantType = StringUtils.hasText(request.getGrantType())
                ? request.getGrantType().trim()
                : SolidConstants.DEFAULT_GRANT_TYPE;
        AuthGrantHandler handler = grantHandlerMap.get(grantType);
        if (handler == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "不支持的认证授权模式: " + grantType);
        }

        AuthGrantCommand command = buildGrantCommand(request, grantType, tenantIdHeader, clientIp, userAgent);

        // 1. 委派对应授权策略核验身份，任何失败均安全发布登录失败审计后再原样重抛
        AuthenticatedPrincipal principal;
        try {
            principal = handler.authenticate(command);
        } catch (RuntimeException ex) {
            authAuditSupport.publishLoginFailure(command, ex);
            throw ex;
        }

        // 2. 签发 satoken-admin 会话并发布登录成功审计事件
        AdminLoginVO loginVO = issueAdminSession(principal, clientIp);
        authAuditSupport.publishLoginSuccess(principal, clientIp, userAgent);
        return loginVO;
    }

    /**
     * 注销当前管理端登录会话并发布登出审计事件。
     *
     * @param clientIp  客户端来源 IP
     * @param userAgent 客户端原始 User-Agent
     */
    public void logout(String clientIp, String userAgent) {
        LoginUser loginUser = LoginHelper.getLoginUser(SolidConstants.ADMIN_LOGIN_TYPE);
        Long ownerTenantId = AuthContextSupport.parseLongOrNull(
                LoginHelper.getTenantId(SolidConstants.ADMIN_LOGIN_TYPE));
        Long userId = LoginHelper.getUserIdAsLong(SolidConstants.ADMIN_LOGIN_TYPE);
        String username = loginUser != null ? loginUser.getUserAccount() : null;

        LoginHelper.logout(SolidConstants.ADMIN_LOGIN_TYPE);

        if (userId != null || StringUtils.hasText(username)) {
            authAuditSupport.publishLogoutSuccess(ownerTenantId, userId, username, clientIp, userAgent);
        }
    }

    /**
     * 获取当前生效租户视角下的登录用户轻量身份摘要与实时角色/权限集合。
     *
     * @return 当前用户身份、权限码与改密提醒标识
     * @throws BusinessException 未登录或目标用户不存在时抛出对应业务异常
     */
    public AdminProfileVO getAdminInfo() {
        Long ownerTenantId = AuthContextSupport.requireOwnerTenantId();
        Long effectiveTenantId = AuthContextSupport.resolveEffectiveTenantId(ownerTenantId);
        Long userId = AuthContextSupport.requireCurrentUserId();

        AdminProfileDTO profile = remoteUserService.getAdminProfile(effectiveTenantId, userId);
        StpLogic stpLogic = LoginHelper.getStpLogic(SolidConstants.ADMIN_LOGIN_TYPE);
        List<String> roles = AuthContextSupport.safeDistinctList(stpLogic.getRoleList());
        List<String> permissions = AuthContextSupport.safeDistinctList(stpLogic.getPermissionList());

        return AdminProfileVO.builder()
                .userId(profile.getUserId())
                .tenantId(profile.getTenantId())
                .tenantName(profile.getTenantName())
                .username(profile.getUsername())
                .nickname(profile.getNickname())
                .avatar(profile.getAvatarUrl())
                .roles(roles)
                .permissions(permissions)
                .tenantEnabled(tenantResolutionSupport.isMultiTenantEnabled())
                .passwordNeedsChange(profile.getPwdUpdateTime() == null)
                .loginIp(profile.getLoginIp())
                .loginDate(profile.getLoginDate())
                .build();
    }

    /**
     * 获取当前生效租户视角下可见的嵌套管理端路由菜单树。
     *
     * @return 动态菜单路由树集合（无可见菜单时返回空集合）
     * @throws BusinessException 未登录或会话上下文非法时抛出 {@code 401}
     */
    public List<RouteMenuVO> getAdminRoutes() {
        Long ownerTenantId = AuthContextSupport.requireOwnerTenantId();
        Long effectiveTenantId = AuthContextSupport.resolveEffectiveTenantId(ownerTenantId);
        Long userId = AuthContextSupport.requireCurrentUserId();
        boolean superAdmin = LoginHelper.isSuperAdmin(SolidConstants.ADMIN_LOGIN_TYPE);
        boolean tenantEnabled = tenantResolutionSupport.isMultiTenantEnabled();

        List<RouteMenuDTO> routeMenus = remotePermissionService.getRouteMenusByUserId(
                ownerTenantId, effectiveTenantId, userId, superAdmin, tenantEnabled
        );
        return toRouteMenuVOList(routeMenus);
    }

    private AuthGrantCommand buildGrantCommand(
            AdminLoginRequest request,
            String grantType,
            String tenantIdHeader,
            String clientIp,
            String userAgent
    ) {
        Map<String, Object> extras = StringUtils.hasText(tenantIdHeader)
                ? Map.of(TenantResolutionSupport.EXTRA_TENANT_ID_HEADER, tenantIdHeader.trim())
                : Collections.emptyMap();
        return AuthGrantCommand.builder()
                .grantType(grantType)
                .loginType(SolidConstants.ADMIN_LOGIN_TYPE)
                .tenantCode(StringUtils.hasText(request.getTenantCode()) ? request.getTenantCode().trim() : null)
                .username(request.getUsername())
                .password(request.getPassword())
                .captchaKey(request.getCaptchaKey())
                .captchaCode(request.getCaptchaCode())
                .clientIp(clientIp)
                .userAgent(userAgent)
                .extras(extras)
                .build();
    }

    private AdminLoginVO issueAdminSession(AuthenticatedPrincipal principal, String clientIp) {
        LoginUser loginUser = LoginUser.builder()
                .userId(String.valueOf(principal.getUserId()))
                .userAccount(principal.getUsername())
                .userName(principal.getNickname())
                .userType(SolidConstants.ADMIN_LOGIN_TYPE)
                .deptId(principal.getDeptId() != null ? String.valueOf(principal.getDeptId()) : null)
                .tenantId(String.valueOf(principal.getTenantId()))
                .roles(CollectionUtils.isEmpty(principal.getRoles())
                        ? Collections.emptySet()
                        : new LinkedHashSet<>(principal.getRoles()))
                .permissions(Collections.emptySet())
                .build();
        SaLoginParameter loginParameter = new SaLoginParameter();
        if (StringUtils.hasText(clientIp)) {
            loginParameter.setTerminalExtra("loginIp", clientIp);
        }
        LoginHelper.login(SolidConstants.ADMIN_LOGIN_TYPE, loginUser, loginParameter);

        StpLogic stpLogic = LoginHelper.getStpLogic(SolidConstants.ADMIN_LOGIN_TYPE);
        return AdminLoginVO.builder()
                .tokenName(stpLogic.getTokenName())
                .tokenValue(LoginHelper.getTokenValue(SolidConstants.ADMIN_LOGIN_TYPE))
                .expireIn(LoginHelper.getTokenTimeout(SolidConstants.ADMIN_LOGIN_TYPE))
                .tenantId(principal.getTenantId())
                .tenantName(principal.getTenantName())
                .build();
    }

    private static List<RouteMenuVO> toRouteMenuVOList(List<RouteMenuDTO> nodes) {
        if (CollectionUtils.isEmpty(nodes)) {
            return Collections.emptyList();
        }
        return nodes.stream()
                .map(node -> RouteMenuVO.builder()
                        .id(node.getId())
                        .parentId(node.getParentId())
                        .menuType(node.getMenuType())
                        .title(node.getTitle())
                        .name(node.getName())
                        .path(node.getPath())
                        .component(node.getComponent())
                        .redirect(node.getRedirect())
                        .icon(node.getIcon())
                        .sort(node.getSort())
                        .visible(node.getVisible())
                        .keepAlive(node.getKeepAlive())
                        .isExternal(node.getIsExternal())
                        .platformOnly(node.getPlatformOnly())
                        .children(toRouteMenuVOList(node.getChildren()))
                        .build())
                .toList();
    }
}

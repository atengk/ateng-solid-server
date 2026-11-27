package io.github.atengk.solid.auth.grant;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.boot.satoken.defense.LoginFailureLockService;
import io.github.atengk.boot.web.captcha.template.CaptchaTemplate;
import io.github.atengk.solid.auth.properties.SolidAuthProperties;
import io.github.atengk.solid.auth.result.AuthResultCode;
import io.github.atengk.solid.auth.security.LoginLockedException;
import io.github.atengk.solid.auth.service.TenantResolutionSupport;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.properties.SolidTenantProperties;
import io.github.atengk.solid.common.spi.auth.AuthGrantCommand;
import io.github.atengk.solid.common.spi.auth.AuthenticatedPrincipal;
import io.github.atengk.solid.system.api.dto.AdminAuthUserDTO;
import io.github.atengk.solid.system.api.dto.TenantAuthDTO;
import io.github.atengk.solid.system.api.service.RemotePermissionService;
import io.github.atengk.solid.system.api.service.RemoteTenantService;
import io.github.atengk.solid.system.api.service.RemoteUserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link PasswordAuthGrantHandler} 4 步串行校验链、{@code X-Tenant-Id} 租户兼容与防爆破优先级 Layer 1 单元测试。
 *
 * @author Ateng
 * @since 2026-11-25
 */
class PasswordAuthGrantHandlerTest {

    private static final String TEST_IP = "203.0.113.10";

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CaptchaTemplate> captchaProviderOf(CaptchaTemplate template) {
        ObjectProvider<CaptchaTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(template);
        return provider;
    }

    private static PasswordAuthGrantHandler createHandler(
            SolidAuthProperties authProperties,
            SolidTenantProperties tenantProperties,
            CaptchaTemplate captchaTemplate,
            RemoteTenantService remoteTenantService,
            RemoteUserService remoteUserService,
            RemotePermissionService remotePermissionService,
            LoginFailureLockService loginFailureLockService
    ) {
        TenantResolutionSupport tenantSupport = new TenantResolutionSupport(tenantProperties, remoteTenantService);
        return new PasswordAuthGrantHandler(
                authProperties,
                tenantSupport,
                captchaProviderOf(captchaTemplate),
                remoteUserService,
                remotePermissionService,
                loginFailureLockService
        );
    }

    private static AuthGrantCommand baseCommand() {
        return AuthGrantCommand.builder()
                .grantType("password")
                .loginType(SolidConstants.ADMIN_LOGIN_TYPE)
                .tenantCode("T1001")
                .username("alice")
                .password("Ateng@Alice1")
                .captchaKey("cap-1")
                .captchaCode("ABCD")
                .clientIp(TEST_IP)
                .userAgent("JUnit")
                .build();
    }

    @Test
    @DisplayName("步骤 1：验证码开启且校验失败时应当直接抛出 101001，不查租户且不扣密码失败次数")
    void shouldFailOnInvalidCaptchaWithoutTouchingTenantOrLockCounters() {
        SolidAuthProperties authProps = new SolidAuthProperties();
        authProps.setCaptchaEnabled(true);
        SolidTenantProperties tenantProps = new SolidTenantProperties();
        tenantProps.setEnabled(true);

        CaptchaTemplate captcha = mock(CaptchaTemplate.class);
        when(captcha.verify("cap-1", "ABCD")).thenReturn(false);

        RemoteTenantService tenantService = mock(RemoteTenantService.class);
        RemoteUserService userService = mock(RemoteUserService.class);
        RemotePermissionService permService = mock(RemotePermissionService.class);
        LoginFailureLockService lockService = mock(LoginFailureLockService.class);

        PasswordAuthGrantHandler handler = createHandler(
                authProps, tenantProps, captcha, tenantService, userService, permService, lockService
        );

        assertThat(handler.getGrantType()).isEqualTo(SolidConstants.DEFAULT_GRANT_TYPE);
        assertThatThrownBy(() -> handler.authenticate(baseCommand()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(AuthResultCode.CAPTCHA_INVALID.getCode());

        verifyNoInteractions(tenantService, userService, permService, lockService);
    }

    @Test
    @DisplayName("步骤 2：租户不存在、已停用或已过期应当抛出 101005，且不扣密码失败次数；支持 X-Tenant-Id 头兜底解析")
    void shouldResolveTenantByCodeOrHeaderAndFailOnUnavailableTenantWithoutRecordingFailure() {
        SolidAuthProperties authProps = new SolidAuthProperties();
        authProps.setCaptchaEnabled(false);
        SolidTenantProperties tenantProps = new SolidTenantProperties();
        tenantProps.setEnabled(true);

        RemoteTenantService tenantService = mock(RemoteTenantService.class);
        when(tenantService.getTenantByCode("T_MISSING")).thenReturn(null);
        when(tenantService.getTenantByCode("T_DISABLED")).thenReturn(TenantAuthDTO.builder()
                .tenantId(2002L)
                .tenantCode("T_DISABLED")
                .status(CommonStatusEnum.DISABLED.getCode())
                .build());
        when(tenantService.getTenantByCode("T_EXPIRED")).thenReturn(TenantAuthDTO.builder()
                .tenantId(2003L)
                .tenantCode("T_EXPIRED")
                .status(CommonStatusEnum.NORMAL.getCode())
                .expireTime(LocalDateTime.now().minusMinutes(1))
                .build());
        when(tenantService.getTenantById(3001L)).thenReturn(TenantAuthDTO.builder()
                .tenantId(3001L)
                .tenantCode("T3001")
                .tenantName("头解析租户")
                .status(CommonStatusEnum.NORMAL.getCode())
                .build());

        RemoteUserService userService = mock(RemoteUserService.class);
        when(userService.getAuthUserByUsername(3001L, "alice")).thenReturn(AdminAuthUserDTO.builder()
                .userId(301L)
                .tenantId(3001L)
                .username("alice")
                .nickname("爱丽丝")
                .passwordHash(PasswordUtil.hash("Ateng@Alice1"))
                .status(CommonStatusEnum.NORMAL.getCode())
                .build());
        RemotePermissionService permService = mock(RemotePermissionService.class);
        when(permService.getRoleKeysByUserId(3001L, 301L)).thenReturn(List.of("tenant_admin"));
        LoginFailureLockService lockService = mock(LoginFailureLockService.class);
        when(lockService.resolveClientIp(anyString())).thenReturn(TEST_IP);

        PasswordAuthGrantHandler handler = createHandler(
                authProps, tenantProps, null, tenantService, userService, permService, lockService
        );

        for (String badTenantCode : List.of("T_MISSING", "T_DISABLED", "T_EXPIRED")) {
            AuthGrantCommand cmd = baseCommand();
            cmd.setTenantCode(badTenantCode);
            assertThatThrownBy(() -> handler.authenticate(cmd))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getCode())
                    .isEqualTo(AuthResultCode.TENANT_UNAVAILABLE.getCode());
        }
        verifyNoInteractions(userService, permService);

        // tenantCode 为空时应通过 X-Tenant-Id 头解析租户 3001
        AuthGrantCommand headerCmd = baseCommand();
        headerCmd.setTenantCode(null);
        headerCmd.setExtras(Map.of(TenantResolutionSupport.EXTRA_TENANT_ID_HEADER, "3001"));
        AuthenticatedPrincipal principal = handler.authenticate(headerCmd);
        assertThat(principal.getTenantId()).isEqualTo(3001L);
        assertThat(principal.getTenantCode()).isEqualTo("T3001");
    }

    @Test
    @DisplayName("步骤 3：防爆破前置检查应当优先判定 IP 锁定并兼容嗅探空 clientIp，且保底 remainSeconds >= 1")
    void shouldPrioritizeIpLockOverAccountLockAndResolveSniffedIpWhenBlank() {
        SolidAuthProperties authProps = new SolidAuthProperties();
        authProps.setCaptchaEnabled(false);
        SolidTenantProperties tenantProps = new SolidTenantProperties();
        tenantProps.setEnabled(true);

        RemoteTenantService tenantService = mock(RemoteTenantService.class);
        when(tenantService.getTenantByCode("T1001")).thenReturn(TenantAuthDTO.builder()
                .tenantId(1001L)
                .tenantCode("T1001")
                .tenantName("租户1001")
                .status(CommonStatusEnum.NORMAL.getCode())
                .build());

        RemoteUserService userService = mock(RemoteUserService.class);
        RemotePermissionService permService = mock(RemotePermissionService.class);
        LoginFailureLockService lockService = mock(LoginFailureLockService.class);

        // 场景 A：command.clientIp 为空但 resolveClientIp 嗅探返回实际 IP，且 IP 与账号同时锁定 -> 先返回 IP 维度
        when(lockService.resolveClientIp(nullable(String.class))).thenReturn(TEST_IP);
        when(lockService.isIpLocked(TEST_IP)).thenReturn(true);
        when(lockService.getIpLockTime(TEST_IP)).thenReturn(0L);
        when(lockService.isLocked("1001", "admin", "alice")).thenReturn(true);
        when(lockService.getDisableTime("1001", "admin", "alice")).thenReturn(900L);

        PasswordAuthGrantHandler handler = createHandler(
                authProps, tenantProps, null, tenantService, userService, permService, lockService
        );

        AuthGrantCommand blankIpCmd = baseCommand();
        blankIpCmd.setClientIp(null);
        assertThatThrownBy(() -> handler.authenticate(blankIpCmd))
                .isInstanceOf(LoginLockedException.class)
                .satisfies(ex -> {
                    LoginLockedException locked = (LoginLockedException) ex;
                    assertThat(locked.getCode()).isEqualTo(AuthResultCode.LOGIN_LOCKED.getCode());
                    assertThat(locked.getLockType()).isEqualTo("IP");
                    assertThat(locked.getRemainSeconds()).isGreaterThanOrEqualTo(1L);
                });

        // 场景 B：仅账号锁定 -> 返回 ACCOUNT 维度
        when(lockService.isIpLocked(TEST_IP)).thenReturn(false);
        when(lockService.isLocked("1001", "admin", "alice")).thenReturn(true);
        when(lockService.getDisableTime("1001", "admin", "alice")).thenReturn(420L);

        assertThatThrownBy(() -> handler.authenticate(baseCommand()))
                .isInstanceOf(LoginLockedException.class)
                .satisfies(ex -> {
                    LoginLockedException locked = (LoginLockedException) ex;
                    assertThat(locked.getCode()).isEqualTo(AuthResultCode.LOGIN_LOCKED.getCode());
                    assertThat(locked.getLockType()).isEqualTo("ACCOUNT");
                    assertThat(locked.getRemainSeconds()).isEqualTo(420L);
                });

        verifyNoInteractions(userService, permService);
    }

    @Test
    @DisplayName("步骤 4：账号不存在与密码错误均应当防枚举返回 101002，达到阈值按先 IP 后账号返回 101004")
    void shouldPreventEnumerationAndTriggerLockAtThreshold() {
        SolidAuthProperties authProps = new SolidAuthProperties();
        authProps.setCaptchaEnabled(false);
        SolidTenantProperties tenantProps = new SolidTenantProperties();
        tenantProps.setEnabled(false);

        RemoteTenantService tenantService = mock(RemoteTenantService.class);
        when(tenantService.getTenantById(0L)).thenReturn(TenantAuthDTO.builder()
                .tenantId(0L)
                .tenantCode("000000")
                .tenantName("平台默认租户")
                .status(CommonStatusEnum.NORMAL.getCode())
                .build());

        RemoteUserService userService = mock(RemoteUserService.class);
        when(userService.getAuthUserByUsername(0L, "ghost")).thenReturn(null);
        when(userService.getAuthUserByUsername(0L, "alice")).thenReturn(AdminAuthUserDTO.builder()
                .userId(101L)
                .tenantId(0L)
                .username("alice")
                .nickname("爱丽丝")
                .passwordHash(PasswordUtil.hash("Correct@123"))
                .status(CommonStatusEnum.NORMAL.getCode())
                .build());

        RemotePermissionService permService = mock(RemotePermissionService.class);
        LoginFailureLockService lockService = mock(LoginFailureLockService.class);
        when(lockService.resolveClientIp(anyString())).thenReturn(TEST_IP);
        when(lockService.isIpLocked(anyString())).thenReturn(false);
        when(lockService.isLocked(anyString(), anyString(), anyString())).thenReturn(false);

        PasswordAuthGrantHandler handler = createHandler(
                authProps, tenantProps, null, tenantService, userService, permService, lockService
        );

        // 1. 账号不存在 -> 101002，且应执行与 PBKDF2 校验同量级耗时的 Dummy 计算防时序枚举
        when(lockService.recordFailure("0", "admin", "ghost", TEST_IP)).thenReturn(4);
        AuthGrantCommand ghostCmd = baseCommand();
        ghostCmd.setUsername("ghost");
        long ghostStartNs = System.nanoTime();
        assertThatThrownBy(() -> handler.authenticate(ghostCmd))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(AuthResultCode.BAD_CREDENTIALS.getCode());
        long ghostElapsedMs = (System.nanoTime() - ghostStartNs) / 1_000_000L;
        assertThat(ghostElapsedMs).isGreaterThan(30L);

        // 2. 密码错误未达阈值 -> 101002
        when(lockService.recordFailure("0", "admin", "alice", TEST_IP)).thenReturn(1);
        AuthGrantCommand wrongPwdCmd = baseCommand();
        wrongPwdCmd.setPassword("Wrong@123");
        assertThatThrownBy(() -> handler.authenticate(wrongPwdCmd))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(AuthResultCode.BAD_CREDENTIALS.getCode());

        // 3. 第 5 次密码错误触发账号锁 -> LoginLockedException(ACCOUNT)
        when(lockService.recordFailure("0", "admin", "alice", TEST_IP)).thenReturn(0);
        when(lockService.getDisableTime("0", "admin", "alice")).thenReturn(900L);
        assertThatThrownBy(() -> handler.authenticate(wrongPwdCmd))
                .isInstanceOf(LoginLockedException.class)
                .satisfies(ex -> {
                    LoginLockedException locked = (LoginLockedException) ex;
                    assertThat(locked.getCode()).isEqualTo(AuthResultCode.LOGIN_LOCKED.getCode());
                    assertThat(locked.getLockType()).isEqualTo("ACCOUNT");
                    assertThat(locked.getRemainSeconds()).isEqualTo(900L);
                });
    }

    @Test
    @DisplayName("步骤 4：停用账号抛 101003；正常账号验证通过后清除失败计数并返回认证主体")
    void shouldRejectDisabledUserAndClearFailuresOnSuccess() {
        SolidAuthProperties authProps = new SolidAuthProperties();
        authProps.setCaptchaEnabled(true);
        SolidTenantProperties tenantProps = new SolidTenantProperties();
        tenantProps.setEnabled(false);

        CaptchaTemplate captcha = mock(CaptchaTemplate.class);
        when(captcha.verify("cap-1", "ABCD")).thenReturn(true);

        RemoteTenantService tenantService = mock(RemoteTenantService.class);
        when(tenantService.getTenantById(0L)).thenReturn(TenantAuthDTO.builder()
                .tenantId(0L)
                .tenantCode("000000")
                .tenantName("平台默认租户")
                .status(CommonStatusEnum.NORMAL.getCode())
                .build());

        RemoteUserService userService = mock(RemoteUserService.class);
        when(userService.getAuthUserByUsername(0L, "disabled_user")).thenReturn(AdminAuthUserDTO.builder()
                .userId(102L)
                .tenantId(0L)
                .username("disabled_user")
                .nickname("已停用")
                .passwordHash(PasswordUtil.hash("Ateng@Alice1"))
                .status(CommonStatusEnum.DISABLED.getCode())
                .build());
        when(userService.getAuthUserByUsername(0L, "alice")).thenReturn(AdminAuthUserDTO.builder()
                .userId(101L)
                .tenantId(0L)
                .deptId(100L)
                .username("alice")
                .nickname("爱丽丝")
                .passwordHash(PasswordUtil.hash("Ateng@Alice1"))
                .status(CommonStatusEnum.NORMAL.getCode())
                .build());

        RemotePermissionService permService = mock(RemotePermissionService.class);
        when(permService.getRoleKeysByUserId(0L, 101L)).thenReturn(List.of("super_admin"));

        LoginFailureLockService lockService = mock(LoginFailureLockService.class);
        when(lockService.resolveClientIp(anyString())).thenReturn(TEST_IP);
        when(lockService.isIpLocked(anyString())).thenReturn(false);
        when(lockService.isLocked(anyString(), anyString(), anyString())).thenReturn(false);

        PasswordAuthGrantHandler handler = createHandler(
                authProps, tenantProps, captcha, tenantService, userService, permService, lockService
        );

        AuthGrantCommand disabledCmd = baseCommand();
        disabledCmd.setUsername("disabled_user");
        assertThatThrownBy(() -> handler.authenticate(disabledCmd))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(AuthResultCode.ACCOUNT_DISABLED.getCode());
        verify(lockService, never()).recordFailure(anyString(), anyString(), anyString(), anyString());

        AuthenticatedPrincipal principal = handler.authenticate(baseCommand());
        assertThat(principal.getUserId()).isEqualTo(101L);
        assertThat(principal.getTenantId()).isZero();
        assertThat(principal.getTenantCode()).isEqualTo("000000");
        assertThat(principal.getTenantName()).isEqualTo("平台默认租户");
        assertThat(principal.getDeptId()).isEqualTo(100L);
        assertThat(principal.getUsername()).isEqualTo("alice");
        assertThat(principal.getNickname()).isEqualTo("爱丽丝");
        assertThat(principal.getLoginType()).isEqualTo("admin");
        assertThat(principal.getRoles()).containsExactly("super_admin");
        verify(lockService).clearFailure("0", "admin", "alice");
        verify(userService, never()).upgradePasswordHash(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("低迭代 PBKDF2 历史哈希登录成功后应当平滑升级且不升级停用账号，写入异常时不影响本次登录")
    void shouldSmoothlyUpgradeLegacyPasswordHashForNormalUserOnlyAndIgnoreUpgradeError() {
        SolidAuthProperties authProps = new SolidAuthProperties();
        authProps.setCaptchaEnabled(false);
        SolidTenantProperties tenantProps = new SolidTenantProperties();
        tenantProps.setEnabled(false);

        RemoteTenantService tenantService = mock(RemoteTenantService.class);
        when(tenantService.getTenantById(0L)).thenReturn(TenantAuthDTO.builder()
                .tenantId(0L)
                .tenantCode("000000")
                .tenantName("平台默认租户")
                .status(CommonStatusEnum.NORMAL.getCode())
                .build());

        String legacyHash = PasswordUtil.hash("Ateng@Alice1", 10_000);
        RemoteUserService userService = mock(RemoteUserService.class);
        when(userService.getAuthUserByUsername(0L, "alice")).thenReturn(AdminAuthUserDTO.builder()
                .userId(101L)
                .tenantId(0L)
                .deptId(100L)
                .username("alice")
                .nickname("爱丽丝")
                .passwordHash(legacyHash)
                .status(CommonStatusEnum.NORMAL.getCode())
                .build());
        when(userService.getAuthUserByUsername(0L, "disabled_legacy")).thenReturn(AdminAuthUserDTO.builder()
                .userId(102L)
                .tenantId(0L)
                .username("disabled_legacy")
                .nickname("停用旧哈希")
                .passwordHash(legacyHash)
                .status(CommonStatusEnum.DISABLED.getCode())
                .build());

        RemotePermissionService permService = mock(RemotePermissionService.class);
        when(permService.getRoleKeysByUserId(0L, 101L)).thenReturn(List.of("super_admin"));

        LoginFailureLockService lockService = mock(LoginFailureLockService.class);
        when(lockService.resolveClientIp(anyString())).thenReturn(TEST_IP);
        when(lockService.isIpLocked(anyString())).thenReturn(false);
        when(lockService.isLocked(anyString(), anyString(), anyString())).thenReturn(false);

        PasswordAuthGrantHandler handler = createHandler(
                authProps, tenantProps, null, tenantService, userService, permService, lockService
        );

        AuthenticatedPrincipal principal = handler.authenticate(baseCommand());
        assertThat(principal.getUserId()).isEqualTo(101L);
        verify(userService).upgradePasswordHash(
                eq(0L),
                eq(101L),
                argThat(upgraded -> !PasswordUtil.needsUpgrade(upgraded) && PasswordUtil.matches("Ateng@Alice1", upgraded))
        );

        AuthGrantCommand disabledCmd = baseCommand();
        disabledCmd.setUsername("disabled_legacy");
        assertThatThrownBy(() -> handler.authenticate(disabledCmd))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(AuthResultCode.ACCOUNT_DISABLED.getCode());
        verify(userService, never()).upgradePasswordHash(eq(0L), eq(102L), anyString());

        doThrow(new RuntimeException("db busy"))
                .when(userService).upgradePasswordHash(eq(0L), eq(101L), anyString());
        AuthenticatedPrincipal resilientPrincipal = handler.authenticate(baseCommand());
        assertThat(resilientPrincipal.getUserId()).isEqualTo(101L);
    }

    @Test
    @DisplayName("入口防御：非法 tenantCode 与负数 X-Tenant-Id 应当在调用 RemoteTenantService 前直接拦截抛 101005")
    void shouldRejectMalformedTenantCodeAndNegativeTenantIdHeaderBeforeLookup() {
        SolidAuthProperties authProps = new SolidAuthProperties();
        authProps.setCaptchaEnabled(false);
        SolidTenantProperties tenantProps = new SolidTenantProperties();
        tenantProps.setEnabled(true);

        RemoteTenantService tenantService = mock(RemoteTenantService.class);
        RemoteUserService userService = mock(RemoteUserService.class);
        RemotePermissionService permService = mock(RemotePermissionService.class);
        LoginFailureLockService lockService = mock(LoginFailureLockService.class);

        PasswordAuthGrantHandler handler = createHandler(
                authProps, tenantProps, null, tenantService, userService, permService, lockService
        );

        for (String invalidCode : List.of("ab", "bad code!", "tenant#01", "a".repeat(33))) {
            AuthGrantCommand badCodeCmd = baseCommand();
            badCodeCmd.setTenantCode(invalidCode);
            assertThatThrownBy(() -> handler.authenticate(badCodeCmd))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getCode())
                    .isEqualTo(AuthResultCode.TENANT_UNAVAILABLE.getCode());
        }

        AuthGrantCommand negativeHeaderCmd = baseCommand();
        negativeHeaderCmd.setTenantCode(null);
        negativeHeaderCmd.setExtras(Map.of(TenantResolutionSupport.EXTRA_TENANT_ID_HEADER, "-1"));
        assertThatThrownBy(() -> handler.authenticate(negativeHeaderCmd))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(AuthResultCode.TENANT_UNAVAILABLE.getCode());

        verifyNoInteractions(tenantService, userService, permService, lockService);
    }
}

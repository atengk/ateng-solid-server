package io.github.atengk.solid;

import cn.dev33.satoken.stp.StpLogic;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.satoken.defense.LoginFailureLockService;
import io.github.atengk.boot.satoken.model.LoginUser;
import io.github.atengk.boot.satoken.util.LoginHelper;
import io.github.atengk.solid.auth.result.AuthResultCode;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysLoginLog;
import io.github.atengk.solid.system.entity.SysOperLog;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysRoleMenu;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.entity.SysUserRole;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysLoginLogMapper;
import io.github.atengk.solid.system.mapper.SysOperLogMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysRoleMenuMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端认证中心（{@code /admin/auth/**}）、账号防爆破 {@code 101004} 透传、不透明会话句柄、改密注销与审计事件 Layer 3 MockMvc 集成测试。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@SpringBootTest(
        classes = SolidApplication.class,
        properties = {
                "ateng.solid.init.enabled=true",
                "ateng.solid.tenant.enabled=false",
                "ateng.solid.auth.captcha-enabled=false",
                "spring.datasource.url=jdbc:h2:mem:auth_flow_mockmvc_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        }
)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthFlowMockMvcTest {

    private static final long DEPT_IT_ID = 4100L;
    private static final long ROLE_VIEWER_ID = 4201L;
    private static final long USER_VIEWER_ID = 4301L;
    private static final long USER_TARGET_ID = 4302L;
    private static final long USER_BRUTE_ID = 4303L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private LoginFailureLockService loginFailureLockService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private SysDeptMapper sysDeptMapper;
    @Autowired
    private SysRoleMapper sysRoleMapper;
    @Autowired
    private SysRoleMenuMapper sysRoleMenuMapper;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;
    @Autowired
    private SysLoginLogMapper sysLoginLogMapper;
    @Autowired
    private SysOperLogMapper sysOperLogMapper;

    private String superAdminToken;

    @BeforeAll
    void setUpFixtures() {
        loginFailureLockService.clearLocalMemory();

        TenantContextHolder.runWithTenant(SolidConstants.PLATFORM_TENANT_ID, () -> {
            SysDept dept = new SysDept();
            dept.setId(DEPT_IT_ID);
            dept.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
            dept.setParentId(SolidConstants.DEFAULT_ROOT_DEPT_ID);
            dept.setAncestors("0,100");
            dept.setDeptName("研发中心");
            dept.setSort(1);
            dept.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysDeptMapper.insert(dept);

            SysRole viewerRole = new SysRole();
            viewerRole.setId(ROLE_VIEWER_ID);
            viewerRole.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
            viewerRole.setRoleName("只读访客");
            viewerRole.setRoleKey("viewer");
            viewerRole.setDataScope(5);
            viewerRole.setMenuCheckStrictly(true);
            viewerRole.setDeptCheckStrictly(false);
            viewerRole.setSort(2);
            viewerRole.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysRoleMapper.insert(viewerRole);

            for (Long menuId : List.of(1000L, 1100L, 1101L)) {
                SysRoleMenu rm = new SysRoleMenu();
                rm.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
                rm.setRoleId(ROLE_VIEWER_ID);
                rm.setMenuId(menuId);
                sysRoleMenuMapper.insert(rm);
            }

            insertUser(USER_VIEWER_ID, "viewer_user", "只读用户", "Ateng@Viewer1");
            insertUser(USER_TARGET_ID, "kick_target", "被踢会话测试员", "Ateng@Target1");
            insertUser(USER_BRUTE_ID, "brute_force_user", "防爆破测试员", "Ateng@Brute1");
        });
    }

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        DataPermissionContextHolder.clear();
        MDC.clear();
    }

    private void insertUser(Long userId, String username, String nickname, String rawPassword) {
        SysUser user = new SysUser();
        user.setId(userId);
        user.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
        user.setDeptId(DEPT_IT_ID);
        user.setUsername(username);
        user.setNickname(nickname);
        user.setPassword(PasswordUtil.hash(rawPassword));
        user.setGender("2");
        user.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysUserMapper.insert(user);

        SysUserRole ur = new SysUserRole();
        ur.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
        ur.setUserId(userId);
        ur.setRoleId(ROLE_VIEWER_ID);
        sysUserRoleMapper.insert(ur);
    }

    @Test
    @Order(1)
    @DisplayName("匿名端点应当放行，未登录访问受保护端点应当返回 401")
    void shouldAllowAnonymousCaptchaAndRejectUnauthenticatedRequests() throws Exception {
        mockMvc.perform(get("/admin/auth/captcha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.tenantEnabled").value(false))
                .andExpect(jsonPath("$.data.captchaKey").value(""))
                .andExpect(jsonPath("$.data.imageBase64").value(""))
                .andExpect(jsonPath("$.data.expireSeconds").value(0));

        for (String path : List.of("/admin/auth/info", "/admin/auth/routes", "/admin/auth/online/page")) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(401));
        }
    }

    @Test
    @Order(2)
    @DisplayName("超管应当以首启凭据登录签发 satoken-admin，/info 返回 passwordNeedsChange=true 且 /routes 过滤多租户菜单")
    void shouldLoginSuperAdminAndReturnProfileInfoAndFilteredRoutes() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/admin/auth/login")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "superadmin",
                                "password", "Ateng@Test2026"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.tokenName").value("satoken-admin"))
                .andExpect(jsonPath("$.data.tokenValue").isString())
                .andExpect(jsonPath("$.data.expireIn").isNumber())
                .andExpect(jsonPath("$.data.tenantId").value("0"))
                .andExpect(jsonPath("$.data.tenantName").value("平台默认租户"))
                .andReturn();

        superAdminToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .path("data").path("tokenValue").asText();
        assertThat(superAdminToken).isNotBlank();

        mockMvc.perform(get("/admin/auth/info")
                        .header("satoken-admin", superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").isString())
                .andExpect(jsonPath("$.data.tenantId").value("0"))
                .andExpect(jsonPath("$.data.tenantEnabled").value(false))
                .andExpect(jsonPath("$.data.username").value("superadmin"))
                .andExpect(jsonPath("$.data.passwordNeedsChange").value(true))
                .andExpect(jsonPath("$.data.roles[0]").value("super_admin"))
                .andExpect(jsonPath("$.data.permissions[0]").value("*:*:*"));

        MvcResult routesResult = mockMvc.perform(get("/admin/auth/routes")
                        .header("satoken-admin", superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        List<String> routeIds = objectMapper.readTree(routesResult.getResponse().getContentAsString())
                .path("data").findValuesAsText("id");
        assertThat(routeIds).contains("1000", "2000", "4000").doesNotContain("3000", "1101");
    }

    @Test
    @Order(3)
    @DisplayName("账号防爆破达阈值应当返回 code=101004 且 Result.data 透传 LoginLockDetailVO")
    void shouldReturnStructured101004AccountCountdownOnBruteForceThreshold() throws Exception {
        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "non_existent_user_123",
                                "password", "Wrong@123"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(101002));

        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/admin/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", "brute_force_user",
                                    "password", "Wrong@Pass"
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(101002));
        }

        MvcResult lockResult = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "brute_force_user",
                                "password", "Wrong@Pass"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(101004))
                .andExpect(jsonPath("$.data.lockType").value("ACCOUNT"))
                .andExpect(jsonPath("$.data.remainSeconds").isNumber())
                .andReturn();
        assertThat(objectMapper.readTree(lockResult.getResponse().getContentAsString())
                .path("data").path("remainSeconds").asLong()).isGreaterThanOrEqualTo(1L);

        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "brute_force_user",
                                "password", "Ateng@Brute1"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(101004))
                .andExpect(jsonPath("$.data.lockType").value("ACCOUNT"));

        loginFailureLockService.clearFailure("0", SolidConstants.ADMIN_LOGIN_TYPE, "brute_force_user");
        loginFailureLockService.clearLocalMemory();
    }

    @Test
    @Order(4)
    @DisplayName("在线会话应当屏蔽原始 Token、拦截越权/自踢 101007/伪造句柄 400 并支持强退另一会话")
    void shouldProtectOnlineSessionsAndSupportOpaqueHandleKickout() throws Exception {
        String viewerToken = loginAndGetToken("viewer_user", "Ateng@Viewer1");
        mockMvc.perform(get("/admin/auth/online/page")
                        .header("satoken-admin", viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        String targetToken = loginAndGetToken("kick_target", "Ateng@Target1");
        MvcResult pageResult = mockMvc.perform(get("/admin/auth/online/page")
                        .header("satoken-admin", superAdminToken)
                        .param("current", "1")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        String rawJson = pageResult.getResponse().getContentAsString();
        assertThat(rawJson).doesNotContain(superAdminToken).doesNotContain(targetToken).doesNotContain(viewerToken);

        JsonNode records = objectMapper.readTree(rawJson).path("data").path("records");
        String selfSessionId = null;
        String targetSessionId = null;
        for (JsonNode item : records) {
            assertThat(item.path("userId").isTextual()).isTrue();
            assertThat(item.path("tenantId").isTextual()).isTrue();
            assertThat(item.path("tokenTimeout").isNumber()).isTrue();
            assertThat(item.path("tokenMask").asText()).contains("****");
            if (item.path("currentSession").asBoolean()) {
                selfSessionId = item.path("sessionId").asText();
            } else if ("kick_target".equals(item.path("username").asText())) {
                targetSessionId = item.path("sessionId").asText();
            }
        }
        assertThat(selfSessionId).isNotBlank();
        assertThat(targetSessionId).isNotBlank();

        mockMvc.perform(delete("/admin/auth/online/{sessionId}", selfSessionId)
                        .header("satoken-admin", superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(101007));

        mockMvc.perform(delete("/admin/auth/online/{sessionId}", "forged_invalid_handle_xyz")
                        .header("satoken-admin", superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(delete("/admin/auth/online/{sessionId}", targetSessionId)
                        .header("satoken-admin", superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/admin/auth/info")
                        .header("satoken-admin", targetToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(delete("/admin/auth/online/{sessionId}", targetSessionId)
                        .header("satoken-admin", superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        awaitKickoutOperateLog(targetSessionId);
    }

    @Test
    @Order(5)
    @DisplayName("改密后原 Token 应当立即 401 且 pwd_update_time 非空，注销与全链路登录审计均落库")
    void shouldInvalidateAllTokensOnPasswordChangeAndPersistAuditLogs() throws Exception {
        mockMvc.perform(put("/admin/auth/password")
                        .header("satoken-admin", superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "oldPassword", "WrongOld@1",
                                "newPassword", "Ateng@New2026"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(101006));

        mockMvc.perform(put("/admin/auth/password")
                        .header("satoken-admin", superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "oldPassword", "Ateng@Test2026",
                                "newPassword", "123456"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(100001));

        mockMvc.perform(put("/admin/auth/password")
                        .header("satoken-admin", superAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "oldPassword", "Ateng@Test2026",
                                "newPassword", "Ateng@New2026"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/admin/auth/info")
                        .header("satoken-admin", superAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));

        Object pwdUpdateTime = jdbcTemplate.queryForObject(
                "SELECT pwd_update_time FROM sys_user WHERE tenant_id = 0 AND username = 'superadmin' AND deleted = 0",
                Object.class
        );
        assertThat(pwdUpdateTime).isNotNull();

        String newToken = loginAndGetToken("superadmin", "Ateng@New2026");
        mockMvc.perform(get("/admin/auth/info")
                        .header("satoken-admin", newToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.passwordNeedsChange").value(false));

        mockMvc.perform(post("/admin/auth/logout")
                        .header("satoken-admin", newToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/admin/auth/info")
                        .header("satoken-admin", newToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));

        awaitLoginAuditLogs();
    }

    @Test
    @Order(99)
    @DisplayName("关闭多租户模式降级运行时，携带非 0 租户历史会话令牌应当强制注销并拒绝（101005 -> 401）")
    void shouldLogoutAndRejectNonPlatformSessionWhenMultiTenantIsDisabled() throws Exception {
        StpLogic stpLogic = LoginHelper.getStpLogic(SolidConstants.ADMIN_LOGIN_TYPE);
        String staleCompositeId = LoginHelper.buildCompositeLoginId("5001", "99999");
        String staleToken = stpLogic.createLoginSession(staleCompositeId);
        LoginUser staleUser = LoginUser.builder()
                .userId("99999")
                .userAccount("stale_tenant_user")
                .userName("残留租户用户")
                .userType(SolidConstants.ADMIN_LOGIN_TYPE)
                .tenantId("5001")
                .build();
        stpLogic.getSessionByLoginId(staleCompositeId).set("login_user", JsonUtil.toJsonString(staleUser));

        mockMvc.perform(get("/admin/auth/info")
                        .header("satoken-admin", staleToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(AuthResultCode.TENANT_UNAVAILABLE.getCode()));

        mockMvc.perform(get("/admin/auth/info")
                        .header("satoken-admin", staleToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        MvcResult res = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .path("data").path("tokenValue").asText();
    }

    private void awaitLoginAuditLogs() throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(5).toMillis();
        List<SysLoginLog> logs = List.of();
        while (System.currentTimeMillis() < deadline) {
            logs = sysLoginLogMapper.selectList(Wrappers.lambdaQuery());
            boolean hasSuccess = logs.stream().anyMatch(log -> Integer.valueOf(0).equals(log.getStatus())
                    && "登录成功".equals(log.getMsg()));
            boolean hasFailure = logs.stream().anyMatch(log -> Integer.valueOf(1).equals(log.getStatus()));
            boolean hasLogout = logs.stream().anyMatch(log -> "退出登录成功".equals(log.getMsg()));
            if (hasSuccess && hasFailure && hasLogout) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(logs).extracting(SysLoginLog::getMsg).contains("登录成功", "退出登录成功");
    }

    private void awaitKickoutOperateLog(String sensitiveSessionId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(5).toMillis();
        List<SysOperLog> logs = List.of();
        while (System.currentTimeMillis() < deadline) {
            logs = sysOperLogMapper.selectList(Wrappers.<SysOperLog>lambdaQuery()
                    .eq(SysOperLog::getModule, "在线会话")
                    .orderByDesc(SysOperLog::getId));
            if (logs != null && !logs.isEmpty()) {
                SysOperLog latest = logs.get(0);
                assertThat(latest.getRequestUrl()).isEqualTo("/admin/auth/online/{sessionId}");
                assertThat(latest.getRequestUrl()).doesNotContain(sensitiveSessionId);
                assertThat(latest.getRequestParams()).contains("[EXCLUDED]").doesNotContain(sensitiveSessionId);
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待强退在线会话操作审计落库超时");
    }
}

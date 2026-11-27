package io.github.atengk.solid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.satoken.defense.LoginFailureLockService;
import io.github.atengk.boot.web.ratelimit.aspect.RateLimiterAspect;
import io.github.atengk.boot.web.ratelimit.properties.RateLimiterProperties;
import io.github.atengk.boot.web.ratelimit.store.InMemoryRateLimiterStore;
import io.github.atengk.boot.web.ratelimit.store.RateLimiterStore;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.enums.DataScopeType;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysRoleMenu;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.entity.SysUserRole;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysRoleMenuMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 高风险匿名认证与导入导出接口声明式 {@code @RateLimiter}（IP / USER 维度、{@code 429} 拦截、
 * {@code Retry-After} 与 {@code X-RateLimit-*} 响应头及键隔离）端到端 MockMvc 专项验证。
 *
 * @author Ateng
 * @since 2026-11-27
 */
@SpringBootTest(
        classes = SolidApplication.class,
        properties = {
                "ateng.solid.init.enabled=true",
                "ateng.solid.tenant.enabled=false",
                "ateng.solid.auth.captcha-enabled=false",
                "ateng.boot.web.ratelimit.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:rate_limiter_mockmvc_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        }
)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RateLimiterMockMvcTest {

    private static final long DEPT_RATE_ID = 6100L;
    private static final long ROLE_RATE_ID = 6201L;
    private static final long USER_A_ID = 6301L;
    private static final long USER_B_ID = 6302L;

    private static final String USER_A_NAME = "rate_user_a";
    private static final String USER_B_NAME = "rate_user_b";
    private static final String USER_A_PASS = "Ateng@Rate1";
    private static final String USER_B_PASS = "Ateng@Rate2";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RateLimiterProperties rateLimiterProperties;
    @Autowired
    private RateLimiterStore rateLimiterStore;
    @Autowired
    private RateLimiterAspect rateLimiterAspect;
    @Autowired
    private LoginFailureLockService loginFailureLockService;
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

    @BeforeAll
    void setUpFixtures() {
        loginFailureLockService.clearLocalMemory();

        TenantContextHolder.runWithTenant(SolidConstants.PLATFORM_TENANT_ID, () -> {
            SysDept dept = new SysDept();
            dept.setId(DEPT_RATE_ID);
            dept.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
            dept.setParentId(SolidConstants.DEFAULT_ROOT_DEPT_ID);
            dept.setAncestors("0,100");
            dept.setDeptName("限流专项部门");
            dept.setSort(1);
            dept.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysDeptMapper.insert(dept);

            SysRole role = new SysRole();
            role.setId(ROLE_RATE_ID);
            role.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
            role.setRoleName("导入导出测试角色");
            role.setRoleKey("rate_operator");
            role.setDataScope(DataScopeType.ALL.getCode());
            role.setMenuCheckStrictly(true);
            role.setDeptCheckStrictly(false);
            role.setSort(1);
            role.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysRoleMapper.insert(role);

            for (Long menuId : List.of(1000L, 1100L, 1101L, 1105L, 1106L)) {
                SysRoleMenu rm = new SysRoleMenu();
                rm.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
                rm.setRoleId(ROLE_RATE_ID);
                rm.setMenuId(menuId);
                sysRoleMenuMapper.insert(rm);
            }

            insertUser(USER_A_ID, USER_A_NAME, "限流甲", USER_A_PASS);
            insertUser(USER_B_ID, USER_B_NAME, "限流乙", USER_B_PASS);
        });
    }

    @BeforeEach
    void resetRateLimitBuckets() {
        loginFailureLockService.clearLocalMemory();
        if (rateLimiterStore instanceof InMemoryRateLimiterStore inMemoryStore) {
            inMemoryStore.clear();
        }
    }

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        DataPermissionContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("开启限流后 /admin/auth/captcha 应当按 IP 限制 30 次/60s，超限返回 429 并隔离开关与其余端点/IP")
    void shouldRateLimitCaptchaByIpWithHeadersAndIsolation() throws Exception {
        assertThat(rateLimiterAspect).isNotNull();
        assertThat(rateLimiterStore).isInstanceOf(InMemoryRateLimiterStore.class);
        assertThat(rateLimiterProperties.isEnabled()).isTrue();
        assertThat(rateLimiterProperties.getKeyPrefix()).isEqualTo("solid:rate_limit:");
        assertThat(rateLimiterProperties.isFailOpen()).isTrue();

        String clientIp = "198.51.100.10";
        String otherIp = "198.51.100.11";

        for (int i = 0; i < 30; i++) {
            int expectedRemaining = 29 - i;
            mockMvc.perform(get("/admin/auth/captcha")
                            .header("X-Forwarded-For", clientIp))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT, "30"))
                    .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING, String.valueOf(expectedRemaining)));
        }

        // 第 31 次超额请求拦截并回写 Retry-After 与 0 剩余配额
        mockMvc.perform(get("/admin/auth/captcha")
                        .header("X-Forwarded-For", clientIp))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.TOO_MANY_REQUESTS.getCode()))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT, "30"))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING, "0"))
                .andExpect(header().string(RateLimiterAspect.HEADER_RETRY_AFTER, "60"));

        // 同一 IP 仍可访问 /login（证明 auth:captcha 与 auth:login 桶键相互隔离）
        mockMvc.perform(post("/admin/auth/login")
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", USER_A_NAME,
                                "password", USER_A_PASS
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT, "30"))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING, "29"));

        // 不同来源 IP 访问 /captcha 仍拥有完整配额
        mockMvc.perform(get("/admin/auth/captcha")
                        .header("X-Forwarded-For", otherIp))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING, "29"));
    }

    @Test
    @DisplayName("/admin/auth/login 应当按 IP 限制 30 次/60s，超限返回 429 并回写 Retry-After 与隔离其余 IP")
    void shouldRateLimitLoginByIpAndReturnTooManyRequestsOnOverflow() throws Exception {
        String clientIp = "198.51.100.20";
        String otherIp = "198.51.100.21";
        String validPayload = objectMapper.writeValueAsString(Map.of(
                "username", USER_A_NAME,
                "password", USER_A_PASS
        ));
        // 配额耗尽阶段全部走非 password 授权快速路径，避免 PBKDF2 耗时跨越 2s 令牌再生窗口引起计数抖动
        String fastProbePayload = objectMapper.writeValueAsString(Map.of(
                "username", USER_A_NAME,
                "password", USER_A_PASS,
                "grantType", "rate_probe"
        ));

        for (int i = 0; i < 30; i++) {
            int expectedRemaining = 29 - i;
            mockMvc.perform(post("/admin/auth/login")
                            .header("X-Forwarded-For", clientIp)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(fastProbePayload))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CommonBizResultCode.PARAM_INVALID.getCode()))
                    .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT, "30"))
                    .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING, String.valueOf(expectedRemaining)));
        }

        mockMvc.perform(post("/admin/auth/login")
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.TOO_MANY_REQUESTS.getCode()))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT, "30"))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING, "0"))
                .andExpect(header().string(RateLimiterAspect.HEADER_RETRY_AFTER, "60"));

        mockMvc.perform(post("/admin/auth/login")
                        .header("X-Forwarded-For", otherIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING, "29"));
    }

    @Test
    @DisplayName("/admin/system/user/import 应当按用户限制 5 次/60s，超限返回 429 且与其它用户及 /export 隔离")
    void shouldRateLimitUserImportByUserAndIsolateOtherUserAndExport() throws Exception {
        String tokenA = loginAndGetToken(USER_A_NAME, USER_A_PASS, "198.51.100.30");
        String tokenB = loginAndGetToken(USER_B_NAME, USER_B_PASS, "198.51.100.31");

        byte[] minimalFeedbackWorkbook = buildInvalidFeedbackWorkbook();

        for (int i = 0; i < 5; i++) {
            int expectedRemaining = 4 - i;
            mockMvc.perform(multipart("/admin/system/user/import")
                            .file(xlsxMultipart("rate_imp_" + i + ".xlsx", minimalFeedbackWorkbook))
                            .header("satoken-admin", tokenA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT, "5"))
                    .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING, String.valueOf(expectedRemaining)));
        }

        // 用户 A 第 6 次触发 USER 维度 429
        mockMvc.perform(multipart("/admin/system/user/import")
                        .file(xlsxMultipart("rate_imp_blocked.xlsx", minimalFeedbackWorkbook))
                        .header("satoken-admin", tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.TOO_MANY_REQUESTS.getCode()))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT, "5"))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING, "0"))
                .andExpect(header().string(RateLimiterAspect.HEADER_RETRY_AFTER, "60"));

        // 用户 B 同端点独立计数不被用户 A 牵连
        mockMvc.perform(multipart("/admin/system/user/import")
                        .file(xlsxMultipart("rate_imp_user_b.xlsx", minimalFeedbackWorkbook))
                        .header("satoken-admin", tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING, "4"));

        // 用户 A 在 /import 触发 429 后仍可执行 /export（验证双端点按独立桶键计费）
        mockMvc.perform(post("/admin/system/user/export")
                        .header("satoken-admin", tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(".xlsx")))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT, "10"))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING, "9"));
    }

    @Test
    @DisplayName("/admin/system/user/export 应当按用户限制 10 次/60s，超限返回 429 并回写 Retry-After")
    void shouldRateLimitUserExportByUserAndReturnTooManyRequestsOnOverflow() throws Exception {
        String tokenA = loginAndGetToken(USER_A_NAME, USER_A_PASS, "198.51.100.40");
        String tokenB = loginAndGetToken(USER_B_NAME, USER_B_PASS, "198.51.100.41");

        for (int i = 0; i < 10; i++) {
            int expectedRemaining = 9 - i;
            mockMvc.perform(post("/admin/system/user/export")
                            .header("satoken-admin", tokenB)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", containsString(".xlsx")))
                    .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT, "10"))
                    .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING, String.valueOf(expectedRemaining)));
        }

        mockMvc.perform(post("/admin/system/user/export")
                        .header("satoken-admin", tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.TOO_MANY_REQUESTS.getCode()))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT, "10"))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING, "0"))
                .andExpect(header().string(RateLimiterAspect.HEADER_RETRY_AFTER, "60"));

        mockMvc.perform(post("/admin/system/user/export")
                        .header("satoken-admin", tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(".xlsx")))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING, "9"));
    }

    @Test
    @DisplayName("个人改密、头像上传、管理员重置密码与新增租户接口应当按 USER 维度触发 429 限流")
    void shouldRateLimitSensitiveMutationEndpointsByUser() throws Exception {
        String superToken = loginAndGetToken("superadmin", "Ateng@Test2026", "198.51.100.50");
        String userAToken = loginAndGetToken(USER_A_NAME, USER_A_PASS, "198.51.100.51");

        // 1. PUT /admin/auth/password (5次/60s)
        String badPwdPayload = objectMapper.writeValueAsString(Map.of(
                "oldPassword", "Wrong@Old1",
                "newPassword", "Ateng@New2026"
        ));
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(put("/admin/auth/password")
                            .header("satoken-admin", userAToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(badPwdPayload))
                    .andExpect(status().isOk())
                    .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT, "5"))
                    .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING, String.valueOf(4 - i)));
        }
        mockMvc.perform(put("/admin/auth/password")
                        .header("satoken-admin", userAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badPwdPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.TOO_MANY_REQUESTS.getCode()))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING, "0"))
                .andExpect(header().string(RateLimiterAspect.HEADER_RETRY_AFTER, "60"));

        // 2. POST /admin/auth/profile/avatar (10次/60s)
        MockMultipartFile emptyAvatar = new MockMultipartFile(
                "file", "empty.png", "image/png", new byte[0]);
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(multipart("/admin/auth/profile/avatar")
                            .file(emptyAvatar)
                            .header("satoken-admin", userAToken))
                    .andExpect(status().isOk())
                    .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT, "10"))
                    .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING, String.valueOf(9 - i)));
        }
        mockMvc.perform(multipart("/admin/auth/profile/avatar")
                        .file(emptyAvatar)
                        .header("satoken-admin", userAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.TOO_MANY_REQUESTS.getCode()))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING, "0"))
                .andExpect(header().string(RateLimiterAspect.HEADER_RETRY_AFTER, "60"));

        // 3. PUT /admin/system/user/{id}/reset-password (10次/60s)
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(put("/admin/system/user/{id}/reset-password", 999999L)
                            .header("satoken-admin", superToken))
                    .andExpect(status().isOk())
                    .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT, "10"))
                    .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING, String.valueOf(9 - i)));
        }
        mockMvc.perform(put("/admin/system/user/{id}/reset-password", 999999L)
                        .header("satoken-admin", superToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.TOO_MANY_REQUESTS.getCode()))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING, "0"))
                .andExpect(header().string(RateLimiterAspect.HEADER_RETRY_AFTER, "60"));

        // 4. POST /admin/system/tenant (5次/60s)
        String tenantPayload = objectMapper.writeValueAsString(Map.of(
                "tenantCode", "TRATE1",
                "tenantName", "限流验证租户",
                "packageId", 1,
                "accountCount", -1,
                "adminUsername", "tenant_adm",
                "adminPassword", "Ateng@Tenant1"
        ));
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/admin/system/tenant")
                            .header("satoken-admin", superToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(tenantPayload))
                    .andExpect(status().isOk())
                    .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT, "5"))
                    .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING, String.valueOf(4 - i)));
        }
        mockMvc.perform(post("/admin/system/tenant")
                        .header("satoken-admin", superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.TOO_MANY_REQUESTS.getCode()))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING, "0"))
                .andExpect(header().string(RateLimiterAspect.HEADER_RETRY_AFTER, "60"));
    }

    private String loginAndGetToken(String username, String password, String clientIp) throws Exception {
        MvcResult result = mockMvc.perform(post("/admin/auth/login")
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("tokenValue").asText();
    }

    private void insertUser(Long userId, String username, String nickname, String rawPassword) {
        SysUser user = new SysUser();
        user.setId(userId);
        user.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
        user.setDeptId(DEPT_RATE_ID);
        user.setUsername(username);
        user.setNickname(nickname);
        user.setPassword(PasswordUtil.hash(rawPassword));
        user.setGender("1");
        user.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysUserMapper.insert(user);

        SysUserRole ur = new SysUserRole();
        ur.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
        ur.setUserId(userId);
        ur.setRoleId(ROLE_RATE_ID);
        sysUserRoleMapper.insert(ur);
    }

    private static MockMultipartFile xlsxMultipart(String filename, byte[] bytes) {
        return new MockMultipartFile(
                "file",
                filename,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                bytes
        );
    }

    private static byte[] buildInvalidFeedbackWorkbook() throws Exception {
        List<String> headers = List.of("用户账号", "用户昵称", "归属部门", "岗位编码", "角色编码", "手机号码", "电子邮箱", "性别", "状态");
        // 单行故意使用过短账号以触发阶段 1 快速批注返回（200 OK 且不向用户表落库产生副作用）
        List<String> row = List.of("x", "限流测试占位", "", "", "", "", "", "", "");
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("用户导入");
            Row headerRow = sheet.createRow(0);
            for (int c = 0; c < headers.size(); c++) {
                headerRow.createCell(c).setCellValue(headers.get(c));
            }
            Row dataRow = sheet.createRow(1);
            for (int c = 0; c < row.size(); c++) {
                dataRow.createCell(c).setCellValue(row.get(c));
            }
            wb.write(out);
            return out.toByteArray();
        }
    }
}

package io.github.atengk.solid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.boot.satoken.cache.PermissionCacheService;
import io.github.atengk.boot.satoken.defense.LoginFailureLockService;
import io.github.atengk.boot.satoken.util.LoginHelper;
import io.github.atengk.boot.web.ratelimit.aspect.RateLimiterAspect;
import io.github.atengk.boot.web.ratelimit.properties.RateLimiterProperties;
import io.github.atengk.boot.web.ratelimit.store.RateLimiterStore;
import io.github.atengk.boot.web.ratelimit.store.RedissonRateLimiterStore;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.enums.DataScopeType;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysRoleMenu;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.entity.SysUserRole;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysRoleMenuMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 本地真实 Redis/Redisson 与 MySQL 环境下的高风险端点 {@code @RateLimiter} 端到端 MockMvc 验证。
 *
 * @author Ateng
 * @since 2026-11-27
 */
@Tag("local")
@EnabledIfSystemProperty(named = "solid.local.test", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalRateLimiterMockMvcTest extends LocalMiddlewareTestSupport {

    private static final String TEST_USER_NAME = ("lt_rl_" + TEST_RUN_ID)
            .substring(0, Math.min(28, ("lt_rl_" + TEST_RUN_ID).length()));
    private static final String TEST_ROLE_KEY = "lt_rl_role_" + TEST_RUN_ID;
    private static final String TEST_USER_PASS = "Ateng@LocalRl1";

    private static final String CAPTCHA_IP_A = "198.51.100.71";
    private static final String CAPTCHA_IP_B = "198.51.100.72";
    private static final String LOGIN_PROBE_IP = "198.51.100.81";
    private static final String USER_LOGIN_IP = "198.51.100.91";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private RateLimiterStore rateLimiterStore;
    @Autowired
    private RateLimiterProperties rateLimiterProperties;
    @Autowired
    private LoginFailureLockService loginFailureLockService;
    @Autowired
    private PermissionCacheService permissionCacheService;
    @Autowired
    private SysRoleMapper sysRoleMapper;
    @Autowired
    private SysRoleMenuMapper sysRoleMenuMapper;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    private Long testRoleId;
    private Long testUserId;

    @BeforeAll
    void setUpIsolatedUser() {
        loginFailureLockService.clearLocalMemory();
        TenantContextHolder.runWithTenant(SolidConstants.PLATFORM_TENANT_ID, () -> {
            SysRole role = new SysRole();
            role.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
            role.setRoleName("本地限流测试角色");
            role.setRoleKey(TEST_ROLE_KEY);
            role.setDataScope(DataScopeType.ALL.getCode());
            role.setMenuCheckStrictly(true);
            role.setDeptCheckStrictly(false);
            role.setSort(99);
            role.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysRoleMapper.insert(role);
            testRoleId = role.getId();

            for (Long menuId : List.of(1000L, 1100L, 1101L, 1105L, 1106L)) {
                SysRoleMenu rm = new SysRoleMenu();
                rm.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
                rm.setRoleId(testRoleId);
                rm.setMenuId(menuId);
                sysRoleMenuMapper.insert(rm);
            }

            SysUser user = new SysUser();
            user.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
            user.setDeptId(SolidConstants.DEFAULT_ROOT_DEPT_ID);
            user.setUsername(TEST_USER_NAME);
            user.setNickname("本地限流验证员");
            user.setPassword(PasswordUtil.hash(TEST_USER_PASS));
            user.setGender("1");
            user.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysUserMapper.insert(user);
            testUserId = user.getId();

            SysUserRole ur = new SysUserRole();
            ur.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
            ur.setUserId(testUserId);
            ur.setRoleId(testRoleId);
            sysUserRoleMapper.insert(ur);
        });
    }

    @AfterAll
    void cleanUpIsolatedState() throws InterruptedException {
        if (testUserId != null) {
            try {
                String compositeLoginId = LoginHelper.buildCompositeLoginId(
                        String.valueOf(SolidConstants.PLATFORM_TENANT_ID),
                        String.valueOf(testUserId)
                );
                LoginHelper.logoutByLoginType(SolidConstants.ADMIN_LOGIN_TYPE, compositeLoginId);
            } catch (Exception ignored) {
            }
            try {
                permissionCacheService.clearUserCache(
                        SolidConstants.ADMIN_LOGIN_TYPE,
                        String.valueOf(SolidConstants.PLATFORM_TENANT_ID),
                        String.valueOf(testUserId)
                );
            } catch (Exception ignored) {
            }
        }

        for (String key : List.of(
                TEST_RATELIMIT_KEY_PREFIX + "ip:" + CAPTCHA_IP_A + ":auth:captcha",
                TEST_RATELIMIT_KEY_PREFIX + "ip:" + CAPTCHA_IP_B + ":auth:captcha",
                TEST_RATELIMIT_KEY_PREFIX + "ip:" + LOGIN_PROBE_IP + ":auth:login",
                TEST_RATELIMIT_KEY_PREFIX + "ip:" + USER_LOGIN_IP + ":auth:login"
        )) {
            try {
                rateLimiterStore.reset(key);
            } catch (Exception ignored) {
            }
        }
        if (testUserId != null) {
            for (String suffix : List.of("system:user:import", "system:user:export")) {
                try {
                    rateLimiterStore.reset(TEST_RATELIMIT_KEY_PREFIX + "user:" + testUserId + ":" + suffix);
                } catch (Exception ignored) {
                }
            }
        }

        deleteIsolatedKeysByPattern(stringRedisTemplate, "*" + TEST_RATELIMIT_KEY_PREFIX + "*");
        deleteIsolatedKeysByPattern(stringRedisTemplate, "*" + TEST_KEY_PREFIX + "excel:feedback:*");
        deleteIsolatedKeysByPattern(stringRedisTemplate, "*" + TEST_KEY_PREFIX + "idempotent:audit:login:*");

        // 等待异步登录与操作审计事件完成落库后精准删除测试账号记录
        Thread.sleep(500);
        jdbcTemplate.update("DELETE FROM sys_login_log WHERE username = ?", TEST_USER_NAME);
        jdbcTemplate.update("DELETE FROM sys_oper_log WHERE user_name = ?", TEST_USER_NAME);

        if (testUserId != null) {
            jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", testUserId);
            jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", testUserId);
        }
        if (testRoleId != null) {
            jdbcTemplate.update("DELETE FROM sys_role_menu WHERE role_id = ?", testRoleId);
            jdbcTemplate.update("DELETE FROM sys_role WHERE id = ?", testRoleId);
        }
    }

    @Test
    @DisplayName("真实 RedissonRateLimiterStore 下 /captcha 与 /login 应当按 IP 限流并落盘 solid:rate_limit:* 键")
    void shouldEnforceRealRedissonRateLimitOnCaptchaAndLoginEndpoints() throws Exception {
        assertThat(rateLimiterStore).isInstanceOf(RedissonRateLimiterStore.class);
        assertThat(rateLimiterProperties.isEnabled()).isTrue();
        assertThat(rateLimiterProperties.getKeyPrefix()).isEqualTo(TEST_RATELIMIT_KEY_PREFIX);

        for (int i = 0; i < 30; i++) {
            int expectedRemaining = 29 - i;
            mockMvc.perform(get("/admin/auth/captcha").header("X-Forwarded-For", CAPTCHA_IP_A))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT, "30"))
                    .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING,
                            String.valueOf(expectedRemaining)));
        }

        mockMvc.perform(get("/admin/auth/captcha").header("X-Forwarded-For", CAPTCHA_IP_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.TOO_MANY_REQUESTS.getCode()))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT, "30"))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING, "0"))
                .andExpect(header().string(RateLimiterAspect.HEADER_RETRY_AFTER, "60"));

        Set<String> captchaKeys = scanIsolatedKeys(
                stringRedisTemplate,
                "*" + TEST_RATELIMIT_KEY_PREFIX + "ip:" + CAPTCHA_IP_A + ":auth:captcha*"
        );
        assertThat(captchaKeys).isNotEmpty();

        mockMvc.perform(get("/admin/auth/captcha").header("X-Forwarded-For", CAPTCHA_IP_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING, "29"));

        // /login 使用非 password 探针路径快速耗尽配额，避免 PBKDF2 耗时跨窗或触发失败锁审计
        String fastProbePayload = objectMapper.writeValueAsString(Map.of(
                "username", TEST_USER_NAME,
                "password", TEST_USER_PASS,
                "grantType", "rate_probe"
        ));
        for (int i = 0; i < 30; i++) {
            int expectedRemaining = 29 - i;
            mockMvc.perform(post("/admin/auth/login")
                            .header("X-Forwarded-For", LOGIN_PROBE_IP)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(fastProbePayload))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CommonBizResultCode.PARAM_INVALID.getCode()))
                    .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT, "30"))
                    .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING,
                            String.valueOf(expectedRemaining)));
        }

        mockMvc.perform(post("/admin/auth/login")
                        .header("X-Forwarded-For", LOGIN_PROBE_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fastProbePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.TOO_MANY_REQUESTS.getCode()))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT, "30"))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING, "0"))
                .andExpect(header().string(RateLimiterAspect.HEADER_RETRY_AFTER, "60"));

        Set<String> loginKeys = scanIsolatedKeys(
                stringRedisTemplate,
                "*" + TEST_RATELIMIT_KEY_PREFIX + "ip:" + LOGIN_PROBE_IP + ":auth:login*"
        );
        assertThat(loginKeys).isNotEmpty();
    }

    @Test
    @DisplayName("真实 RedissonRateLimiterStore 下 /user/import 与 /user/export 应当按用户维度限流且互相隔离")
    void shouldEnforceRealRedissonRateLimitOnUserImportAndExportEndpoints() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/admin/auth/login")
                        .header("X-Forwarded-For", USER_LOGIN_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", TEST_USER_NAME,
                                "password", TEST_USER_PASS
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode root = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String token = root.path("data").path("tokenValue").asText();
        assertThat(token).isNotBlank();

        byte[] minimalInvalidWorkbook = buildInvalidFeedbackWorkbook();
        for (int i = 0; i < 5; i++) {
            int expectedRemaining = 4 - i;
            mockMvc.perform(multipart("/admin/system/user/import")
                            .file(new MockMultipartFile(
                                    "file",
                                    "local_rl_" + i + ".xlsx",
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                    minimalInvalidWorkbook
                            ))
                            .header("satoken-admin", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT, "5"))
                    .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING,
                            String.valueOf(expectedRemaining)));
        }

        mockMvc.perform(multipart("/admin/system/user/import")
                        .file(new MockMultipartFile(
                                "file",
                                "local_rl_blocked.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                minimalInvalidWorkbook
                        ))
                        .header("satoken-admin", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.TOO_MANY_REQUESTS.getCode()))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT, "5"))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING, "0"))
                .andExpect(header().string(RateLimiterAspect.HEADER_RETRY_AFTER, "60"));

        Set<String> importKeys = scanIsolatedKeys(
                stringRedisTemplate,
                "*" + TEST_RATELIMIT_KEY_PREFIX + "user:" + testUserId + ":system:user:import*"
        );
        assertThat(importKeys).isNotEmpty();

        for (int i = 0; i < 10; i++) {
            int expectedRemaining = 9 - i;
            mockMvc.perform(post("/admin/system/user/export")
                            .header("satoken-admin", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", containsString(".xlsx")))
                    .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT, "10"))
                    .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING,
                            String.valueOf(expectedRemaining)));
        }

        mockMvc.perform(post("/admin/system/user/export")
                        .header("satoken-admin", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.TOO_MANY_REQUESTS.getCode()))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT, "10"))
                .andExpect(header().string(RateLimiterAspect.HEADER_RATE_LIMIT_REMAINING, "0"))
                .andExpect(header().string(RateLimiterAspect.HEADER_RETRY_AFTER, "60"));

        Set<String> exportKeys = scanIsolatedKeys(
                stringRedisTemplate,
                "*" + TEST_RATELIMIT_KEY_PREFIX + "user:" + testUserId + ":system:user:export*"
        );
        assertThat(exportKeys).isNotEmpty();
    }

    private static byte[] buildInvalidFeedbackWorkbook() throws Exception {
        List<String> headers = List.of("用户账号", "用户昵称", "归属部门", "岗位编码", "角色编码", "手机号码", "电子邮箱", "性别", "状态");
        List<String> row = List.of("x", "本地限流占位", "", "", "", "", "", "", "");
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

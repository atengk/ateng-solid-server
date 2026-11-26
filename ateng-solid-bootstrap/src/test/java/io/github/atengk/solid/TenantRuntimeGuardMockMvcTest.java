package io.github.atengk.solid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.satoken.defense.LoginFailureLockService;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysRoleMenu;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysTenantPackage;
import io.github.atengk.solid.system.entity.SysTenantPackageMenu;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.entity.SysUserRole;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysRoleMenuMapper;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMenuMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import io.github.atengk.solid.system.support.TenantStatusCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 开启多租户模式下的 {@code TenantAvailabilityInterceptor}（{@code 101005}）与平台超管跨租户切换（{@code 100002}）Layer 3 MockMvc 契约测试。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@SpringBootTest(
        classes = SolidApplication.class,
        properties = {
                "ateng.solid.init.enabled=true",
                "ateng.solid.tenant.enabled=true",
                "ateng.boot.mybatis-plus.tenant.enabled=true",
                "ateng.solid.auth.captcha-enabled=false",
                "spring.datasource.url=jdbc:h2:mem:tenant_runtime_guard_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        }
)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantRuntimeGuardMockMvcTest {

    private static final long PACKAGE_STD_ID = 6001L;
    private static final long TENANT_ACTIVE_ID = 5001L;
    private static final long TENANT_EXPIRED_ID = 5002L;
    private static final long TENANT_DISABLED_ID = 5003L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private LoginFailureLockService loginFailureLockService;
    @Autowired
    private TenantStatusCache tenantStatusCache;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private SysTenantPackageMapper sysTenantPackageMapper;
    @Autowired
    private SysTenantPackageMenuMapper sysTenantPackageMenuMapper;
    @Autowired
    private SysTenantMapper sysTenantMapper;
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

        SysTenantPackage pkg = new SysTenantPackage();
        pkg.setId(PACKAGE_STD_ID);
        pkg.setPackageName("标准SaaS套餐");
        pkg.setMenuCheckStrictly(true);
        pkg.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysTenantPackageMapper.insert(pkg);

        for (Long menuId : List.of(1000L, 1100L, 1101L, 4000L, 4300L, 4301L, 4302L)) {
            SysTenantPackageMenu pm = new SysTenantPackageMenu();
            pm.setPackageId(PACKAGE_STD_ID);
            pm.setMenuId(menuId);
            sysTenantPackageMenuMapper.insert(pm);
        }

        insertTenant(TENANT_ACTIVE_ID, "T5001", "正常企业A", null, CommonStatusEnum.NORMAL.getCode());
        insertTenant(TENANT_EXPIRED_ID, "T5002", "将过期企业B", LocalDateTime.now().plusHours(1), CommonStatusEnum.NORMAL.getCode());
        insertTenant(TENANT_DISABLED_ID, "T5003", "已停用企业C", null, CommonStatusEnum.DISABLED.getCode());

        seedTenantAdmin(TENANT_ACTIVE_ID, 5101L, 5201L, 5301L, "tenant_a_admin", "租户A管理员");
        seedTenantAdmin(TENANT_EXPIRED_ID, 5102L, 5202L, 5302L, "tenant_b_admin", "租户B管理员");
    }

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        DataPermissionContextHolder.clear();
        MDC.clear();
    }

    private void insertTenant(Long id, String code, String name, LocalDateTime expireTime, Integer status) {
        SysTenant tenant = new SysTenant();
        tenant.setId(id);
        tenant.setTenantCode(code);
        tenant.setTenantName(name);
        tenant.setPackageId(PACKAGE_STD_ID);
        tenant.setExpireTime(expireTime);
        tenant.setAccountCount(-1);
        tenant.setStatus(status);
        sysTenantMapper.insert(tenant);
    }

    private void seedTenantAdmin(Long tenantId, Long deptId, Long roleId, Long userId, String username, String nickname) {
        TenantContextHolder.runWithTenant(tenantId, () -> {
            SysDept dept = new SysDept();
            dept.setId(deptId);
            dept.setTenantId(tenantId);
            dept.setParentId(0L);
            dept.setAncestors("0");
            dept.setDeptName("总部");
            dept.setSort(1);
            dept.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysDeptMapper.insert(dept);

            SysRole role = new SysRole();
            role.setId(roleId);
            role.setTenantId(tenantId);
            role.setRoleName("租户管理员");
            role.setRoleKey("tenant_admin");
            role.setDataScope(1);
            role.setMenuCheckStrictly(true);
            role.setDeptCheckStrictly(false);
            role.setSort(1);
            role.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysRoleMapper.insert(role);

            for (Long menuId : List.of(1000L, 1100L, 1101L, 4000L, 4300L, 4301L, 4302L)) {
                SysRoleMenu rm = new SysRoleMenu();
                rm.setTenantId(tenantId);
                rm.setRoleId(roleId);
                rm.setMenuId(menuId);
                sysRoleMenuMapper.insert(rm);
            }

            SysUser user = new SysUser();
            user.setId(userId);
            user.setTenantId(tenantId);
            user.setDeptId(deptId);
            user.setUsername(username);
            user.setNickname(nickname);
            user.setPassword(PasswordUtil.hash("Ateng@Tenant1"));
            user.setGender("2");
            user.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysUserMapper.insert(user);

            SysUserRole ur = new SysUserRole();
            ur.setTenantId(tenantId);
            ur.setUserId(userId);
            ur.setRoleId(roleId);
            sysUserRoleMapper.insert(ur);
        });
    }

    @Test
    @DisplayName("普通租户停用或自然过期时应当在 TenantAvailabilityInterceptor 注销会话并返回 101005")
    void shouldLogoutAndRejectWith101005WhenOrdinaryTenantBecomesUnavailable() throws Exception {
        // 1. 前置接口应当下发 tenantEnabled=true
        mockMvc.perform(get("/admin/auth/captcha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.tenantEnabled").value(true));

        // 2. 停用租户登录直接返回 101005
        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", "T5003",
                                "username", "any_user",
                                "password", "Ateng@Tenant1"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(101005));

        // 3. 租户B正常登录成功，随后租户B过期并失效缓存 -> 运行时拦截器自动注销会话并返回 101005
        String tenantBToken = loginAndGetToken("T5002", "tenant_b_admin", "Ateng@Tenant1");
        mockMvc.perform(get("/admin/auth/info")
                        .header("satoken-admin", tenantBToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.tenantId").value(String.valueOf(TENANT_EXPIRED_ID)));

        jdbcTemplate.update("UPDATE sys_tenant SET expire_time = ? WHERE id = ?",
                LocalDateTime.now().minusSeconds(5), TENANT_EXPIRED_ID);
        tenantStatusCache.evict(TENANT_EXPIRED_ID);

        mockMvc.perform(get("/admin/auth/info")
                        .header("satoken-admin", tenantBToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(101005));

        // 即使后续恢复租户有效期，刚才被拦截器注销的旧 Token 也绝不允许复活（返回 401）
        jdbcTemplate.update("UPDATE sys_tenant SET expire_time = ? WHERE id = ?",
                LocalDateTime.now().plusHours(1), TENANT_EXPIRED_ID);
        tenantStatusCache.evict(TENANT_EXPIRED_ID);

        mockMvc.perform(get("/admin/auth/info")
                        .header("satoken-admin", tenantBToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("平台 super_admin 切换至失效租户头应当返回 100002 且自身 0 号会话保持有效，并校验跨租户句柄隔离")
    void shouldReturn100002WithoutLoggingOutSuperAdminOnInvalidTenantSwitchHeader() throws Exception {
        String superToken = loginAndGetToken("000000", "superadmin", "Ateng@Test2026");
        String tenantAToken = loginAndGetToken("T5001", "tenant_a_admin", "Ateng@Tenant1");

        // 1. 合法切换至正常企业A：/info 返回目标租户摘要与 tenantEnabled=true，/routes 过滤 platform_only
        mockMvc.perform(get("/admin/auth/info")
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ACTIVE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.tenantId").value(String.valueOf(TENANT_ACTIVE_ID)))
                .andExpect(jsonPath("$.data.tenantEnabled").value(true))
                .andExpect(jsonPath("$.data.tenantName").value("正常企业A"));

        MvcResult switchedRoutes = mockMvc.perform(get("/admin/auth/routes")
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ACTIVE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        List<String> switchedMenuIds = objectMapper.readTree(switchedRoutes.getResponse().getContentAsString())
                .path("data").findValuesAsText("id");
        assertThat(switchedMenuIds).contains("1000", "1100").doesNotContain("1300", "3000");

        // 2. 切换至已停用企业C：拦截返回 100002，绝不注销超管自身 "0" 会话
        mockMvc.perform(get("/admin/auth/info")
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_DISABLED_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(100002));

        // 3. 超管复位或不带 X-Tenant-Id 后，自身 "0" 会话仍完全有效且 tenantEnabled=true
        mockMvc.perform(get("/admin/auth/info")
                        .header("satoken-admin", superToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.tenantId").value("0"))
                .andExpect(jsonPath("$.data.tenantEnabled").value(true));

        // 4. 在租户A视角签发的不透明句柄，回到平台租户 0 视角强退时应当因 AAD 不匹配返回 400
        MvcResult onlinePage = mockMvc.perform(get("/admin/auth/online/page")
                        .header("satoken-admin", tenantAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode records = objectMapper.readTree(onlinePage.getResponse().getContentAsString())
                .path("data").path("records");
        assertThat(records.isArray()).isTrue();
        assertThat(records).isNotEmpty();
        String tenantASessionId = records.get(0).path("sessionId").asText();

        mockMvc.perform(delete("/admin/auth/online/{sessionId}", tenantASessionId)
                        .header("satoken-admin", superToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    private String loginAndGetToken(String tenantCode, String username, String password) throws Exception {
        MvcResult res = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", tenantCode,
                                "username", username,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString())
                .path("data")
                .path("tokenValue")
                .asText();
    }
}

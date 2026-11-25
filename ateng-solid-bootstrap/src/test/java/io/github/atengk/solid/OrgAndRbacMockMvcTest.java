package io.github.atengk.solid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.satoken.defense.LoginFailureLockService;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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
 * 部门、岗位、菜单、角色全套 {@code /admin/system/**} HTTP 接口 MockMvc 契约与权限守卫集成测试。
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
                "spring.datasource.url=jdbc:h2:mem:org_rbac_mockmvc_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        }
)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrgAndRbacMockMvcTest {

    private static final long PACKAGE_ID = 8001L;
    private static final long TENANT_ID = 8002L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private LoginFailureLockService loginFailureLockService;
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
        pkg.setId(PACKAGE_ID);
        pkg.setPackageName("组织权限MockMvc测试套餐");
        pkg.setMenuCheckStrictly(true);
        pkg.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysTenantPackageMapper.insert(pkg);

        for (Long mid : List.of(1000L, 1200L, 1201L, 1400L, 1401L, 1500L, 1501L)) {
            SysTenantPackageMenu pm = new SysTenantPackageMenu();
            pm.setPackageId(PACKAGE_ID);
            pm.setMenuId(mid);
            sysTenantPackageMenuMapper.insert(pm);
        }

        SysTenant tenant = new SysTenant();
        tenant.setId(TENANT_ID);
        tenant.setTenantCode("T8002");
        tenant.setTenantName("组织RBAC测试租户");
        tenant.setPackageId(PACKAGE_ID);
        tenant.setAccountCount(-1);
        tenant.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysTenantMapper.insert(tenant);

        TenantContextHolder.runWithTenant(TENANT_ID, () -> {
            SysDept dept = new SysDept();
            dept.setId(8100L);
            dept.setTenantId(TENANT_ID);
            dept.setParentId(0L);
            dept.setAncestors("0");
            dept.setDeptName("测试研发总部");
            dept.setPhone("13800008002");
            dept.setSort(1);
            dept.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysDeptMapper.insert(dept);

            SysRole role = new SysRole();
            role.setId(8200L);
            role.setTenantId(TENANT_ID);
            role.setRoleName("只读观察员");
            role.setRoleKey("readonly_viewer");
            role.setDataScope(1);
            role.setMenuCheckStrictly(true);
            role.setDeptCheckStrictly(false);
            role.setSort(1);
            role.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysRoleMapper.insert(role);

            for (Long mid : List.of(1000L, 1200L, 1201L, 1400L, 1401L)) {
                SysRoleMenu rm = new SysRoleMenu();
                rm.setTenantId(TENANT_ID);
                rm.setRoleId(8200L);
                rm.setMenuId(mid);
                sysRoleMenuMapper.insert(rm);
            }

            SysUser user = new SysUser();
            user.setId(8300L);
            user.setTenantId(TENANT_ID);
            user.setDeptId(8100L);
            user.setUsername("viewer8002");
            user.setNickname("只读测试员");
            user.setPassword(PasswordUtil.hash("Ateng@8002"));
            user.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysUserMapper.insert(user);

            SysUserRole ur = new SysUserRole();
            ur.setTenantId(TENANT_ID);
            ur.setUserId(8300L);
            ur.setRoleId(8200L);
            sysUserRoleMapper.insert(ur);
        });
    }

    @AfterEach
    void clearContexts() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        DataPermissionContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("接口鉴权与权限守卫：未登录 401、越权 403、/dept/options 脱敏与普通租户禁访 /menu/**")
    void shouldEnforceAuthAndPermissionGuardsAcrossOrgRbacEndpoints() throws Exception {
        mockMvc.perform(get("/admin/system/dept/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));

        String viewerToken = loginAndGetToken("T8002", "viewer8002", "Ateng@8002");
        mockMvc.perform(get("/admin/system/dept/options").header("satoken-admin", viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].phone").doesNotExist());

        mockMvc.perform(get("/admin/system/dept/{id}", 8100L).header("satoken-admin", viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.phone").value("13800008002"));

        mockMvc.perform(post("/admin/system/dept")
                        .header("satoken-admin", viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "parentId", 0, "deptName", "无权新增", "sort", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(get("/admin/system/menu/tree").header("satoken-admin", viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("菜单与平台 super_admin 守卫：仅平台 0 号租户可维护菜单，内置 1 号角色禁改 102007")
    void shouldManageMenusAndProtectBuiltinRoleOnPlatformTenant() throws Exception {
        String superToken = loginAndGetToken("000000", "superadmin", "Ateng@Test2026");

        mockMvc.perform(get("/admin/system/menu/tree")
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        MvcResult platformRoleOpts = mockMvc.perform(get("/admin/system/role/options")
                        .header("satoken-admin", superToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        assertThat(objectMapper.readTree(platformRoleOpts.getResponse().getContentAsString())
                .path("data").findValuesAsText("roleKey")).contains("super_admin");

        mockMvc.perform(put("/admin/system/role/{id}/status", 1L)
                        .header("satoken-admin", superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(102007));

        MvcResult createdRes = mockMvc.perform(post("/admin/system/menu")
                        .header("satoken-admin", superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.ofEntries(
                                Map.entry("parentId", 1000), Map.entry("menuType", "C"),
                                Map.entry("title", "MockMvc测试菜单"), Map.entry("name", "SysMockMvcMenu"),
                                Map.entry("path", "/system/mockmvc-menu"), Map.entry("component", "system/mockmvc/index"),
                                Map.entry("sort", 95), Map.entry("visible", 0), Map.entry("status", 0),
                                Map.entry("keepAlive", true), Map.entry("isExternal", false), Map.entry("platformOnly", false)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        String menuId = objectMapper.readTree(createdRes.getResponse().getContentAsString()).path("data").asText();
        assertThat(Long.parseLong(menuId)).isPositive();

        mockMvc.perform(put("/admin/system/menu/{id}/status", menuId)
                        .header("satoken-admin", superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(delete("/admin/system/menu/{id}", menuId).header("satoken-admin", superToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("部门、岗位与角色 HTTP 闭环：状态切换、options 隔离、保留编码 102004、详情回显与 CUSTOM 100001")
    void shouldManageDeptPostRoleViaHttpOnSwitchedTenant() throws Exception {
        String superToken = loginAndGetToken("000000", "superadmin", "Ateng@Test2026");

        MvcResult deptRes = mockMvc.perform(post("/admin/system/dept")
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "parentId", 8100, "deptName", "MockMvc子部门", "sort", 2, "status", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        String subDeptId = objectMapper.readTree(deptRes.getResponse().getContentAsString()).path("data").asText();

        MvcResult postRes = mockMvc.perform(post("/admin/system/post")
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "postCode", "DEV", "postName", "研发工程师", "sort", 1, "status", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        String postId = objectMapper.readTree(postRes.getResponse().getContentAsString()).path("data").asText();

        mockMvc.perform(get("/admin/system/post/page")
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                        .param("postCode", "DEV"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        MvcResult grantableTreeRes = mockMvc.perform(get("/admin/system/role/menu-tree")
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ID)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(grantableTreeRes.getResponse().getContentAsString())
                .path("data").findValuesAsText("id")).contains("1000", "1400", "1500").doesNotContain("1300", "3000");

        mockMvc.perform(post("/admin/system/role")
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "roleName", "越权管理员", "roleKey", "admin", "sort", 1, "menuIds", List.of(1000, 1400)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(102004));

        MvcResult roleRes = mockMvc.perform(post("/admin/system/role")
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "roleName", "组织维护员", "roleKey", "org_maintainer", "sort", 2, "menuIds", List.of(1000, 1400, 1401)))))
                .andExpect(status().isOk())
                .andReturn();
        String roleId = objectMapper.readTree(roleRes.getResponse().getContentAsString()).path("data").asText();

        mockMvc.perform(put("/admin/system/role/{id}/data-scope", roleId)
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("dataScope", 2, "deptIds", List.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(100001));

        mockMvc.perform(put("/admin/system/role/{id}/data-scope", roleId)
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "dataScope", 2, "deptCheckStrictly", false, "deptIds", List.of(Long.parseLong(subDeptId))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/admin/system/role/{id}", roleId)
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deptIds[0]").value(subDeptId));

        MvcResult tenantRoleOpts = mockMvc.perform(get("/admin/system/role/options")
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ID)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(tenantRoleOpts.getResponse().getContentAsString())
                .path("data").findValuesAsText("roleKey")).contains("org_maintainer").doesNotContain("super_admin");

        mockMvc.perform(delete("/admin/system/role/{ids}", roleId)
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ID)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(delete("/admin/system/post/{ids}", postId)
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ID)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(delete("/admin/system/dept/{id}", subDeptId)
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ID)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
    }

    private String loginAndGetToken(String tenantCode, String username, String password) throws Exception {
        MvcResult res = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", tenantCode, "username", username, "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode root = objectMapper.readTree(res.getResponse().getContentAsString());
        return root.path("data").path("tokenValue").asText();
    }
}

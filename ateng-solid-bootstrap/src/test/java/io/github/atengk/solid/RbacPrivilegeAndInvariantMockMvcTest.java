package io.github.atengk.solid;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.satoken.cache.PermissionCacheService;
import io.github.atengk.boot.satoken.defense.LoginFailureLockService;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.enums.DataScopeType;
import io.github.atengk.solid.system.api.result.SystemResultCode;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RBAC 权限子集防提权防线、角色双清缓存、角色名唯一索引及部门业务不变式全链路 MockMvc 集成测试。
 *
 * @author Ateng
 * @since 2026-11-27
 */
@SpringBootTest(
        classes = SolidApplication.class,
        properties = {
                "ateng.solid.init.enabled=true",
                "ateng.solid.tenant.enabled=true",
                "ateng.boot.mybatis-plus.tenant.enabled=true",
                "ateng.solid.auth.captcha-enabled=false",
                "spring.datasource.url=jdbc:h2:mem:rbac_privilege_invariant_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        }
)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RbacPrivilegeAndInvariantMockMvcTest {

    private static final long PACKAGE_ID = 65001L;
    private static final long TENANT_ID = 65002L;

    private static final long DEPT_HQ = 65100L;
    private static final long DEPT_A = 65101L;
    private static final long DEPT_B = 65102L;
    private static final long DEPT_DISABLED = 65103L;

    private static final long ROLE_SUB_ADMIN = 65201L;
    private static final long ROLE_HIGH_ALL = 65202L;
    private static final long ROLE_EXTRA_MENU = 65203L;
    private static final long ROLE_USER_MGR_SCOPED = 65204L;
    private static final long ROLE_LOWER_SUB = 65205L;

    private static final long USER_SUB_ROLE_ADMIN = 65301L;
    private static final long USER_SUB_USER_MGR = 65302L;
    private static final long USER_PEER_EXTRA_MENU = 65303L;
    private static final long USER_PEER_HIGH_SCOPE = 65304L;
    private static final long USER_LOWER_MEMBER = 65305L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private LoginFailureLockService loginFailureLockService;
    @Autowired
    private PermissionCacheService permissionCacheService;
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
        pkg.setPackageName("RBAC防提权测试套餐");
        pkg.setMenuCheckStrictly(true);
        pkg.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysTenantPackageMapper.insert(pkg);

        for (Long menuId : List.of(1000L, 1100L, 1101L, 1102L, 1103L, 1104L, 1200L, 1201L, 1202L, 1203L, 1204L,
                1400L, 1401L, 1402L, 1403L, 1404L, 1500L, 1501L)) {
            SysTenantPackageMenu pm = new SysTenantPackageMenu();
            pm.setPackageId(PACKAGE_ID);
            pm.setMenuId(menuId);
            sysTenantPackageMenuMapper.insert(pm);
        }

        SysTenant tenant = new SysTenant();
        tenant.setId(TENANT_ID);
        tenant.setTenantCode("T65002");
        tenant.setTenantName("RBAC防提权企业");
        tenant.setPackageId(PACKAGE_ID);
        tenant.setAccountCount(-1);
        tenant.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysTenantMapper.insert(tenant);

        TenantContextHolder.runWithTenant(TENANT_ID, () -> {
            insertDept(DEPT_HQ, 0L, "0", "总部", CommonStatusEnum.NORMAL);
            insertDept(DEPT_A, DEPT_HQ, "0," + DEPT_HQ, "研发一部", CommonStatusEnum.NORMAL);
            insertDept(DEPT_B, DEPT_HQ, "0," + DEPT_HQ, "研发二部", CommonStatusEnum.NORMAL);
            insertDept(DEPT_DISABLED, DEPT_HQ, "0," + DEPT_HQ, "已撤销部", CommonStatusEnum.DISABLED);

            // 角色管理子管理员：仅持角色模块与部门只读，数据范围为 DEPT(3)
            insertRole(ROLE_SUB_ADMIN, "角色受限管理员", "sub_role_admin", DataScopeType.DEPT,
                    List.of(1000L, 1200L, 1201L, 1202L, 1203L, 1204L, 1400L, 1401L));
            // ALL 范围高权角色
            insertRole(ROLE_HIGH_ALL, "全局观察角色", "high_all_role", DataScopeType.ALL,
                    List.of(1000L, 1100L, 1101L));
            // 含额外菜单 1500/1501 的平级越权角色
            insertRole(ROLE_EXTRA_MENU, "岗位管理角色", "extra_post_role", DataScopeType.DEPT,
                    List.of(1000L, 1500L, 1501L));
            // 用户域受限主管：DEPT_AND_CHILD(4) + 用户增删改查
            insertRole(ROLE_USER_MGR_SCOPED, "用户受限主管", "sub_user_mgr", DataScopeType.DEPT_AND_CHILD,
                    List.of(1000L, 1100L, 1101L, 1102L, 1103L, 1104L));
            // 下级最小权限角色：SELF(5) + 仅用户列表
            insertRole(ROLE_LOWER_SUB, "普通只读员工", "lower_self_role", DataScopeType.SELF,
                    List.of(1000L, 1100L, 1101L));

            insertUser(USER_SUB_ROLE_ADMIN, DEPT_A, "sub_role_mgr", ROLE_SUB_ADMIN);
            insertUser(USER_SUB_USER_MGR, DEPT_A, "sub_user_mgr", ROLE_USER_MGR_SCOPED);
            insertUser(USER_PEER_EXTRA_MENU, DEPT_A, "peer_post_user", ROLE_EXTRA_MENU);
            insertUser(USER_PEER_HIGH_SCOPE, DEPT_A, "peer_all_user", ROLE_HIGH_ALL);
            insertUser(USER_LOWER_MEMBER, DEPT_A, "lower_member", ROLE_LOWER_SUB);
        });
    }

    @AfterEach
    void clearContexts() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        DataPermissionContextHolder.clear();
        MDC.clear();
        loginFailureLockService.clearLocalMemory();
    }

    @Test
    @DisplayName("非超管角色表单树裁剪、禁超自身权限建改角色、禁自改自身持有角色、禁越权调 dataScope 与安全初始范围")
    void shouldEnforceRoleMenuTreePruningSubsetAndSelfRoleProtection() throws Exception {
        String subRoleToken = loginAndGetToken("T65002", "sub_role_mgr", "Ateng@65002");

        // 1. /role/menu-tree 仅返回操作人自身持有的节点
        MvcResult treeRes = mockMvc.perform(get("/admin/system/role/menu-tree")
                        .header("satoken-admin", subRoleToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        List<String> visibleMenuIds = objectMapper.readTree(treeRes.getResponse().getContentAsString())
                .path("data").findValuesAsText("id");
        assertThat(visibleMenuIds)
                .contains("1000", "1200", "1201", "1400", "1401")
                .doesNotContain("1100", "1101", "1500", "1501");

        // 2. 新增角色包含未持有菜单 1500/1501 必须返回 403
        mockMvc.perform(post("/admin/system/role")
                        .header("satoken-admin", subRoleToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "roleName", "越权岗位角色",
                                "roleKey", "escalate_post",
                                "sort", 10,
                                "menuIds", List.of(1000, 1500, 1501)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.FORBIDDEN.getCode()));

        // 3. 合法子集新建角色：未传 dataScope 时安全继承操作人最大受控范围 DEPT(3)，绝非 ALL(1)
        MvcResult createRes = mockMvc.perform(post("/admin/system/role")
                        .header("satoken-admin", subRoleToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "roleName", "子集部门观察员",
                                "roleKey", "subset_dept_viewer",
                                "sort", 10,
                                "menuIds", List.of(1000, 1400, 1401)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        long createdRoleId = Long.parseLong(objectMapper.readTree(createRes.getResponse().getContentAsString())
                .path("data").asText());

        try {
            mockMvc.perform(get("/admin/system/role/{id}", createdRoleId).header("satoken-admin", subRoleToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.dataScope").value(DataScopeType.DEPT.getCode()));

            // 4. 禁止修改、启停、调范围或删除操作人自身持有的角色
            mockMvc.perform(put("/admin/system/role")
                            .header("satoken-admin", subRoleToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "id", ROLE_SUB_ADMIN, "roleName", "改自身", "roleKey", "sub_role_admin",
                                    "sort", 1, "menuIds", List.of(1000, 1200)
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CommonResultCode.FORBIDDEN.getCode()));

            mockMvc.perform(put("/admin/system/role/{id}/status", ROLE_SUB_ADMIN)
                            .header("satoken-admin", subRoleToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":1}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CommonResultCode.FORBIDDEN.getCode()));

            mockMvc.perform(delete("/admin/system/role/{ids}", ROLE_SUB_ADMIN)
                            .header("satoken-admin", subRoleToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CommonResultCode.FORBIDDEN.getCode()));

            // 5. 禁止操作更高数据范围或含额外菜单的同级/高权角色
            mockMvc.perform(put("/admin/system/role/{id}/status", ROLE_HIGH_ALL)
                            .header("satoken-admin", subRoleToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":1}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CommonResultCode.FORBIDDEN.getCode()));

            mockMvc.perform(delete("/admin/system/role/{ids}", ROLE_EXTRA_MENU)
                            .header("satoken-admin", subRoleToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CommonResultCode.FORBIDDEN.getCode()));

            // 6. 调高子集角色的 dataScope 至 ALL / DEPT_AND_CHILD / 跨部门 CUSTOM 全部 403；调为 DEPT_A CUSTOM 或 SELF 放行
            mockMvc.perform(put("/admin/system/role/{id}/data-scope", createdRoleId)
                            .header("satoken-admin", subRoleToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("dataScope", 1))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CommonResultCode.FORBIDDEN.getCode()));

            mockMvc.perform(put("/admin/system/role/{id}/data-scope", createdRoleId)
                            .header("satoken-admin", subRoleToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("dataScope", 4))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CommonResultCode.FORBIDDEN.getCode()));

            mockMvc.perform(put("/admin/system/role/{id}/data-scope", createdRoleId)
                            .header("satoken-admin", subRoleToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("dataScope", 2, "deptIds", List.of(DEPT_B)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CommonResultCode.FORBIDDEN.getCode()));

            mockMvc.perform(put("/admin/system/role/{id}/data-scope", createdRoleId)
                            .header("satoken-admin", subRoleToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("dataScope", 2, "deptIds", List.of(DEPT_A)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(put("/admin/system/role/{id}/data-scope", createdRoleId)
                            .header("satoken-admin", subRoleToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("dataScope", 5))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        } finally {
            String superToken = Spec4TestFixtures.loginAsSuperAdmin(mockMvc, objectMapper);
            mockMvc.perform(delete("/admin/system/role/{ids}", createdRoleId)
                    .header("satoken-admin", superToken)
                    .header("X-Tenant-Id", String.valueOf(TENANT_ID)));
        }
    }

    @Test
    @DisplayName("角色启停与编辑变更状态应当双清角色权限及绑定用户缓存，且 V1.0.2 唯一索引应阻断底层重名角色竞态")
    void shouldEvictBothRoleAndUserCachesOnRoleStatusAndUpdateChangesAndEnforceUniqueNameIndex() throws Exception {
        String superToken = Spec4TestFixtures.loginAsSuperAdmin(mockMvc, objectMapper);

        String rolePermsKey = PermissionCacheService.buildRolePermsKey(
                "lower_self_role", SolidConstants.ADMIN_LOGIN_TYPE, String.valueOf(TENANT_ID)
        );
        String userRolesKey = PermissionCacheService.buildUserRolesKey(
                String.valueOf(USER_LOWER_MEMBER), SolidConstants.ADMIN_LOGIN_TYPE, String.valueOf(TENANT_ID)
        );

        // 1. updateRoleStatus 停用并恢复时均应双清 role-perms 与绑定用户 user-roles 缓存
        permissionCacheService.set(rolePermsKey, "[\"system:user:list\"]", 60L);
        permissionCacheService.set(userRolesKey, "[\"lower_self_role\"]", 60L);

        mockMvc.perform(put("/admin/system/role/{id}/status", ROLE_LOWER_SUB)
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertThat(permissionCacheService.get(rolePermsKey)).isNull();
        assertThat(permissionCacheService.get(userRolesKey)).isNull();

        permissionCacheService.set(rolePermsKey, "[\"system:user:list\"]", 60L);
        permissionCacheService.set(userRolesKey, "[\"lower_self_role\"]", 60L);
        mockMvc.perform(put("/admin/system/role/{id}/status", ROLE_LOWER_SUB)
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertThat(permissionCacheService.get(rolePermsKey)).isNull();
        assertThat(permissionCacheService.get(userRolesKey)).isNull();

        // 2. updateRole 在 roleKey 不变仅切换 status 时同样双清用户角色缓存
        permissionCacheService.set(userRolesKey, "[\"lower_self_role\"]", 60L);
        mockMvc.perform(put("/admin/system/role")
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "id", ROLE_LOWER_SUB,
                                "roleName", "普通只读员工",
                                "roleKey", "lower_self_role",
                                "sort", 1,
                                "status", 1,
                                "menuIds", List.of(1000, 1100, 1101)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertThat(permissionCacheService.get(userRolesKey)).isNull();

        // 恢复状态避免影响后续测试
        mockMvc.perform(put("/admin/system/role/{id}/status", ROLE_LOWER_SUB)
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":0}"))
                .andExpect(status().isOk());

        // 3. 数据库级 uk_role_tenant_name_deleted 必须阻断绕过应用层检查的同租户并发重名角色插入
        TenantContextHolder.runWithTenant(TENANT_ID, () -> {
            SysRole duplicateNameRole = new SysRole();
            duplicateNameRole.setTenantId(TENANT_ID);
            duplicateNameRole.setRoleName("普通只读员工");
            duplicateNameRole.setRoleKey("unique_key_diff");
            duplicateNameRole.setDataScope(DataScopeType.SELF.getCode());
            duplicateNameRole.setMenuCheckStrictly(true);
            duplicateNameRole.setDeptCheckStrictly(false);
            duplicateNameRole.setSort(9);
            duplicateNameRole.setStatus(CommonStatusEnum.NORMAL.getCode());
            assertThatThrownBy(() -> sysRoleMapper.insert(duplicateNameRole))
                    .isInstanceOf(DuplicateKeyException.class);
        });
    }

    @Test
    @DisplayName("用户写路径防提权：禁授高权/超菜单角色，禁越权改/停/重密/删同部门更高权限账号")
    void shouldEnforceUserPrivilegeSubsetOnCreateUpdateResetPasswordStatusAndDelete() throws Exception {
        String subUserMgrToken = loginAndGetToken("T65002", "sub_user_mgr", "Ateng@65002");

        // 1. 新增用户禁止分配持额外菜单（ROLE_EXTRA_MENU）或更高范围（ROLE_HIGH_ALL）的角色 -> 403
        for (Long badRoleId : List.of(ROLE_EXTRA_MENU, ROLE_HIGH_ALL)) {
            mockMvc.perform(post("/admin/system/user")
                            .header("satoken-admin", subUserMgrToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", "esc_u_" + badRoleId,
                                    "nickname", "越权用户",
                                    "password", "Ateng@Esc1",
                                    "deptId", DEPT_A,
                                    "roleIds", List.of(badRoleId)
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CommonResultCode.FORBIDDEN.getCode()));
        }

        // 2. 对同部门下持有高权或额外权限的同事（@DataScope 可见但 RBAC 不覆盖），改/停/重密/删全返回 403
        for (Long targetUserId : List.of(USER_PEER_EXTRA_MENU, USER_PEER_HIGH_SCOPE)) {
            mockMvc.perform(put("/admin/system/user")
                            .header("satoken-admin", subUserMgrToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "id", targetUserId,
                                    "nickname", "篡改同事",
                                    "deptId", DEPT_A,
                                    "roleIds", List.of(ROLE_LOWER_SUB)
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CommonResultCode.FORBIDDEN.getCode()));

            mockMvc.perform(put("/admin/system/user/{id}/status", targetUserId)
                            .header("satoken-admin", subUserMgrToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":1}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CommonResultCode.FORBIDDEN.getCode()));

            mockMvc.perform(put("/admin/system/user/{id}/reset-password", targetUserId)
                            .header("satoken-admin", subUserMgrToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"Ateng@Hijack9\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CommonResultCode.FORBIDDEN.getCode()));

            mockMvc.perform(delete("/admin/system/user/{ids}", targetUserId)
                            .header("satoken-admin", subUserMgrToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CommonResultCode.FORBIDDEN.getCode()));
        }

        // 3. 修改受管辖低权下属时同样禁止赋予超边界角色
        mockMvc.perform(put("/admin/system/user")
                        .header("satoken-admin", subUserMgrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "id", USER_LOWER_MEMBER,
                                "nickname", "尝试提权下属",
                                "deptId", DEPT_A,
                                "roleIds", List.of(ROLE_HIGH_ALL)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.FORBIDDEN.getCode()));

        // 4. 在自身权界内新建、修改、重密、删下属均正常成功
        MvcResult createOk = mockMvc.perform(post("/admin/system/user")
                        .header("satoken-admin", subUserMgrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "valid_sub_u",
                                "nickname", "合法下属",
                                "password", "Ateng@Valid1",
                                "deptId", DEPT_A,
                                "roleIds", List.of(ROLE_LOWER_SUB)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        String validSubId = objectMapper.readTree(createOk.getResponse().getContentAsString()).path("data").asText();

        mockMvc.perform(delete("/admin/system/user/{ids}", validSubId)
                        .header("satoken-admin", subUserMgrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("部门域不变式：CUSTOM 角色禁绑停用部门，CUSTOM 角色唯一绑定部门禁删（102005），部门名禁止含斜杠 /")
    void shouldEnforceCustomRoleDeptActiveStatusSoleDeptDeleteAndSlashDeptNameRejection() throws Exception {
        String superToken = Spec4TestFixtures.loginAsSuperAdmin(mockMvc, objectMapper);

        // 1. 部门名称包含 '/' 时新增与修改均返回 100001
        mockMvc.perform(post("/admin/system/dept")
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "parentId", DEPT_HQ, "deptName", "研发/测试部", "sort", 5, "status", 0
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonBizResultCode.PARAM_INVALID.getCode()));

        mockMvc.perform(put("/admin/system/dept")
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "id", DEPT_A, "parentId", DEPT_HQ, "deptName", "非法/改名", "sort", 5, "status", 0
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonBizResultCode.PARAM_INVALID.getCode()));

        // 创建两个干净叶子部门与一个 CUSTOM 角色验证防孤儿与防停用部门绑定
        String leaf1Id = createTempDept(superToken, "叶子部门一");
        String leaf2Id = createTempDept(superToken, "叶子部门二");
        String customRoleId = createTempRole(superToken, "custom_dept_guard");

        try {
            // 2. CUSTOM 绑定已停用部门 DEPT_DISABLED 必须返回 100001
            mockMvc.perform(put("/admin/system/role/{id}/data-scope", customRoleId)
                            .header("satoken-admin", superToken)
                            .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "dataScope", 2, "deptIds", List.of(DEPT_DISABLED)
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CommonBizResultCode.PARAM_INVALID.getCode()));

            // 3. CUSTOM 仅绑 leaf1Id 时禁止删除 leaf1Id（102005）；同时绑 leaf1Id+leaf2Id 时可先删 leaf1Id，再删最后一个 leaf2Id 被拒
            mockMvc.perform(put("/admin/system/role/{id}/data-scope", customRoleId)
                            .header("satoken-admin", superToken)
                            .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "dataScope", 2, "deptIds", List.of(Long.parseLong(leaf1Id))
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(delete("/admin/system/dept/{id}", leaf1Id)
                            .header("satoken-admin", superToken)
                            .header("X-Tenant-Id", String.valueOf(TENANT_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(SystemResultCode.RESOURCE_IN_USE.getCode()));

            mockMvc.perform(put("/admin/system/role/{id}/data-scope", customRoleId)
                            .header("satoken-admin", superToken)
                            .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "dataScope", 2,
                                    "deptIds", List.of(Long.parseLong(leaf1Id), Long.parseLong(leaf2Id))
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(delete("/admin/system/dept/{id}", leaf1Id)
                            .header("satoken-admin", superToken)
                            .header("X-Tenant-Id", String.valueOf(TENANT_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(delete("/admin/system/dept/{id}", leaf2Id)
                            .header("satoken-admin", superToken)
                            .header("X-Tenant-Id", String.valueOf(TENANT_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(SystemResultCode.RESOURCE_IN_USE.getCode()));
        } finally {
            mockMvc.perform(delete("/admin/system/role/{ids}", customRoleId)
                    .header("satoken-admin", superToken)
                    .header("X-Tenant-Id", String.valueOf(TENANT_ID)));
            mockMvc.perform(delete("/admin/system/dept/{id}", leaf2Id)
                    .header("satoken-admin", superToken)
                    .header("X-Tenant-Id", String.valueOf(TENANT_ID)));
        }
    }

    private String createTempDept(String superToken, String name) throws Exception {
        MvcResult res = mockMvc.perform(post("/admin/system/dept")
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "parentId", DEPT_HQ, "deptName", name, "sort", 20, "status", 0
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).path("data").asText();
    }

    private String createTempRole(String superToken, String roleKey) throws Exception {
        MvcResult res = mockMvc.perform(post("/admin/system/role")
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "roleName", roleKey, "roleKey", roleKey, "sort", 20, "menuIds", List.of(1000, 1400, 1401)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).path("data").asText();
    }

    private String loginAndGetToken(String tenantCode, String username, String password) throws Exception {
        MvcResult res = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", tenantCode, "username", username, "password", password
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode root = objectMapper.readTree(res.getResponse().getContentAsString());
        return root.path("data").path("tokenValue").asText();
    }

    private void insertDept(Long id, Long parentId, String ancestors, String name, CommonStatusEnum status) {
        SysDept dept = new SysDept();
        dept.setId(id);
        dept.setTenantId(TENANT_ID);
        dept.setParentId(parentId);
        dept.setAncestors(ancestors);
        dept.setDeptName(name);
        dept.setSort(1);
        dept.setStatus(status.getCode());
        sysDeptMapper.insert(dept);
    }

    private void insertRole(Long id, String name, String key, DataScopeType scope, List<Long> menuIds) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setTenantId(TENANT_ID);
        role.setRoleName(name);
        role.setRoleKey(key);
        role.setDataScope(scope.getCode());
        role.setMenuCheckStrictly(true);
        role.setDeptCheckStrictly(false);
        role.setSort(1);
        role.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysRoleMapper.insert(role);

        for (Long menuId : menuIds) {
            SysRoleMenu rm = new SysRoleMenu();
            rm.setTenantId(TENANT_ID);
            rm.setRoleId(id);
            rm.setMenuId(menuId);
            sysRoleMenuMapper.insert(rm);
        }
    }

    private void insertUser(Long id, Long deptId, String username, Long roleId) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setTenantId(TENANT_ID);
        user.setDeptId(deptId);
        user.setUsername(username);
        user.setNickname(username);
        user.setPassword(PasswordUtil.hash("Ateng@65002"));
        user.setGender("2");
        user.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysUserMapper.insert(user);

        SysUserRole ur = new SysUserRole();
        ur.setTenantId(TENANT_ID);
        ur.setUserId(id);
        ur.setRoleId(roleId);
        sysUserRoleMapper.insert(ur);
    }
}

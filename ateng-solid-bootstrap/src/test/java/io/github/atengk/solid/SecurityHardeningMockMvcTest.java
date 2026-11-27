package io.github.atengk.solid;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.satoken.defense.LoginFailureLockService;
import io.github.atengk.solid.auth.result.AuthResultCode;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.enums.DataScopeType;
import io.github.atengk.solid.system.entity.SysConfig;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysOperLog;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysRoleMenu;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysTenantPackage;
import io.github.atengk.solid.system.entity.SysTenantPackageMenu;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.entity.SysUserRole;
import io.github.atengk.solid.system.mapper.SysConfigMapper;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysOperLogMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysRoleMenuMapper;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMenuMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import io.github.atengk.solid.system.support.DictDataCache;
import io.github.atengk.solid.system.support.SysConfigCache;
import io.github.atengk.solid.system.support.TenantStatusCache;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 补齐 #64~#66 安全加固端到端缺口的 MockMvc 专项验证，覆盖全路径审计脱敏、参数分级鉴权、
 * 列表脱敏/详情明文对照、SELF 导入与角色修改防提权、恒定耗时与停用不升级旧哈希及负向缓存零污染。
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
                "spring.datasource.url=jdbc:h2:mem:security_hardening_mockmvc_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        }
)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityHardeningMockMvcTest {

    private static final long PACKAGE_ID = 68100L;
    private static final long TENANT_ID = 68101L;
    private static final String TENANT_CODE = "T68101";

    private static final long DEPT_HQ = 68201L;
    private static final long DEPT_SUB = 68202L;

    private static final long ROLE_TENANT_ADMIN = 68301L;
    private static final long ROLE_USER_UPDATE_ONLY = 68302L;
    private static final long ROLE_SELF_IMPORTER = 68303L;
    private static final long ROLE_SUB_ROLE_MGR = 68304L;
    private static final long ROLE_LOWER_TARGET = 68305L;
    private static final long ROLE_HIGH_ALL = 68306L;
    private static final long ROLE_PLAT_TENANT_CREATE = 68307L;

    private static final long USER_TENANT_ADMIN = 68401L;
    private static final long USER_UPDATE_ONLY = 68402L;
    private static final long USER_SELF_IMPORTER = 68403L;
    private static final long USER_SUB_ROLE_MGR = 68404L;
    private static final long USER_DISABLED_LEGACY = 68405L;
    private static final long USER_PROFILE_OWNER = 68406L;
    private static final long USER_PLAT_TENANT_CREATE = 68407L;

    private static final long CONFIG_AUDIT_ID = 68501L;
    private static final String SECRET_WEBHOOK_VALUE = "whsec-top-secret-68101";
    private static final String COMMON_PASS = "Ateng@Sec68";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private LoginFailureLockService loginFailureLockService;
    @Autowired
    private DictDataCache dictDataCache;
    @Autowired
    private SysConfigCache sysConfigCache;
    @Autowired
    private TenantStatusCache tenantStatusCache;
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
    @Autowired
    private SysConfigMapper sysConfigMapper;
    @Autowired
    private SysOperLogMapper sysOperLogMapper;

    @BeforeAll
    void setUpFixtures() {
        loginFailureLockService.clearLocalMemory();

        SysTenantPackage pkg = new SysTenantPackage();
        pkg.setId(PACKAGE_ID);
        pkg.setPackageName("安全加固专项套餐");
        pkg.setMenuCheckStrictly(true);
        pkg.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysTenantPackageMapper.insert(pkg);

        List<Long> pkgMenus = List.of(
                1000L, 1100L, 1101L, 1102L, 1103L, 1104L, 1105L, 1106L,
                1200L, 1201L, 1202L, 1203L, 1204L,
                1400L, 1401L, 1402L, 1403L, 1404L,
                1500L, 1501L,
                2000L, 2200L, 2201L, 2202L, 2203L, 2204L
        );
        for (Long menuId : pkgMenus) {
            SysTenantPackageMenu pm = new SysTenantPackageMenu();
            pm.setPackageId(PACKAGE_ID);
            pm.setMenuId(menuId);
            sysTenantPackageMenuMapper.insert(pm);
        }

        SysTenant tenant = new SysTenant();
        tenant.setId(TENANT_ID);
        tenant.setTenantCode(TENANT_CODE);
        tenant.setTenantName("安全加固专项企业");
        tenant.setPackageId(PACKAGE_ID);
        tenant.setAccountCount(-1);
        tenant.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysTenantMapper.insert(tenant);

        TenantContextHolder.runWithTenant(SolidConstants.PLATFORM_TENANT_ID, () -> {
            seedRole(SolidConstants.PLATFORM_TENANT_ID, ROLE_PLAT_TENANT_CREATE,
                    "平台建租户员", "plat_tenant_creator", DataScopeType.ALL, List.of(3000L, 3100L, 3102L));
            seedUser(SolidConstants.PLATFORM_TENANT_ID, USER_PLAT_TENANT_CREATE, SolidConstants.DEFAULT_ROOT_DEPT_ID,
                    "plat_tenant_creator", "平台租户创建员", COMMON_PASS, null, null,
                    CommonStatusEnum.NORMAL.getCode(), ROLE_PLAT_TENANT_CREATE);
        });

        TenantContextHolder.runWithTenant(TENANT_ID, () -> {
            seedDept(DEPT_HQ, 0L, "0", "安全总部", "13968100001", "hq.sec@example.com");
            seedDept(DEPT_SUB, DEPT_HQ, "0," + DEPT_HQ, "安全研发部", "13968100002", "sub.sec@example.com");

            seedRole(TENANT_ID, ROLE_TENANT_ADMIN, "安全企业管理员", "sec_tenant_admin", DataScopeType.ALL, pkgMenus);
            seedRole(TENANT_ID, ROLE_USER_UPDATE_ONLY, "仅用户修改员", "user_update_only", DataScopeType.SELF, List.of(1000L, 1100L, 1103L));
            seedRole(TENANT_ID, ROLE_SELF_IMPORTER, "仅本人范围导入员", "self_importer", DataScopeType.SELF, List.of(1000L, 1100L, 1101L, 1105L));
            seedRole(TENANT_ID, ROLE_SUB_ROLE_MGR, "受限角色主管", "sub_role_mgr", DataScopeType.DEPT,
                    List.of(1000L, 1200L, 1201L, 1202L, 1203L, 1204L, 1400L, 1401L));
            seedRole(TENANT_ID, ROLE_LOWER_TARGET, "低权目标角色", "lower_target_role", DataScopeType.SELF,
                    List.of(1000L, 1400L, 1401L));
            seedRole(TENANT_ID, ROLE_HIGH_ALL, "高权全量角色", "high_all_role", DataScopeType.ALL,
                    List.of(1000L, 1100L, 1101L));

            seedUser(TENANT_ID, USER_TENANT_ADMIN, DEPT_HQ, "sec_admin", "安全管理员", COMMON_PASS,
                    "13968101001", "sec.admin@example.com", CommonStatusEnum.NORMAL.getCode(), ROLE_TENANT_ADMIN);
            seedUser(TENANT_ID, USER_UPDATE_ONLY, DEPT_HQ, "user_upd_only", "用户修改员", COMMON_PASS,
                    null, null, CommonStatusEnum.NORMAL.getCode(), ROLE_USER_UPDATE_ONLY);
            seedUser(TENANT_ID, USER_SELF_IMPORTER, DEPT_SUB, "self_importer", "本人导入员", COMMON_PASS,
                    null, null, CommonStatusEnum.NORMAL.getCode(), ROLE_SELF_IMPORTER);
            seedUser(TENANT_ID, USER_SUB_ROLE_MGR, DEPT_HQ, "sub_role_mgr", "角色主管", COMMON_PASS,
                    null, null, CommonStatusEnum.NORMAL.getCode(), ROLE_SUB_ROLE_MGR);
            seedUser(TENANT_ID, USER_PROFILE_OWNER, DEPT_SUB, "profile_owner", "个人资料专员", COMMON_PASS,
                    "13968107000", "owner.init@example.com", CommonStatusEnum.NORMAL.getCode(), ROLE_LOWER_TARGET);

            SysUser legacyDisabled = new SysUser();
            legacyDisabled.setId(USER_DISABLED_LEGACY);
            legacyDisabled.setTenantId(TENANT_ID);
            legacyDisabled.setDeptId(DEPT_SUB);
            legacyDisabled.setUsername("disabled_legacy");
            legacyDisabled.setNickname("停用低迭代用户");
            legacyDisabled.setPassword(PasswordUtil.hash("Ateng@Legacy1", 10_000));
            legacyDisabled.setGender("2");
            legacyDisabled.setStatus(CommonStatusEnum.DISABLED.getCode());
            sysUserMapper.insert(legacyDisabled);

            SysConfig internalConfig = new SysConfig();
            internalConfig.setId(CONFIG_AUDIT_ID);
            internalConfig.setTenantId(TENANT_ID);
            internalConfig.setConfigName("审计校验密钥");
            internalConfig.setConfigKey("internal.audit.secret");
            internalConfig.setConfigValue("old-value-68");
            internalConfig.setConfigType("N");
            sysConfigMapper.insert(internalConfig);
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
    @DisplayName("个人资料、部门、系统参数与导入导出操作日志必须全量脱敏且绝不落库敏感明文与反馈令牌")
    void shouldRedactAllSensitiveParamsAndResultsInAsyncOperateLogs() throws Exception {
        String adminToken = loginAndGetToken(TENANT_CODE, "sec_admin", COMMON_PASS);
        String profileToken = loginAndGetToken(TENANT_CODE, "profile_owner", COMMON_PASS);

        String profilePhone = "13968107777";
        String profileEmail = "profile.secret68@example.com";
        mockMvc.perform(put("/admin/auth/profile")
                        .header("satoken-admin", profileToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "nickname", "个人资料专员",
                                "phone", profilePhone,
                                "email", profileEmail,
                                "gender", "1"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        String deptPhone = "13968101111";
        String deptEmail = "dept.secret68@example.com";
        MvcResult deptCreateRes = mockMvc.perform(post("/admin/system/dept")
                        .header("satoken-admin", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "parentId", DEPT_HQ,
                                "deptName", "审计脱敏验证部",
                                "sort", 9,
                                "phone", deptPhone,
                                "email", deptEmail,
                                "status", 0
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        long createdDeptId = Long.parseLong(objectMapper.readTree(deptCreateRes.getResponse().getContentAsString())
                .path("data").asText());

        String deptUpdatedPhone = "13968102222";
        String deptUpdatedEmail = "dept.updated68@example.com";
        mockMvc.perform(put("/admin/system/dept")
                        .header("satoken-admin", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "id", createdDeptId,
                                "parentId", DEPT_HQ,
                                "deptName", "审计脱敏验证部",
                                "sort", 9,
                                "phone", deptUpdatedPhone,
                                "email", deptUpdatedEmail,
                                "status", 0
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(put("/admin/system/config")
                        .header("satoken-admin", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "id", CONFIG_AUDIT_ID,
                                "configName", "审计校验密钥",
                                "configKey", "internal.audit.secret",
                                "configValue", SECRET_WEBHOOK_VALUE,
                                "configType", "N"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        String badImportPhone = "13968108888";
        String badImportEmail = "imp.bad68@example.com";
        byte[] badImportBytes = buildImportWorkbook(List.of(
                List.of("x", "审计导入测试", "安全总部", "", "", badImportPhone, badImportEmail, "男", "正常")
        ));
        MvcResult importRes = mockMvc.perform(multipart("/admin/system/user/import")
                        .file(new MockMultipartFile(
                                "file", "audit_bad.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", badImportBytes))
                        .header("satoken-admin", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        String feedbackToken = objectMapper.readTree(importRes.getResponse().getContentAsString())
                .path("data").path("feedbackToken").asText();
        assertThat(feedbackToken).isNotBlank();

        String exportPhone = "13968109999";
        mockMvc.perform(post("/admin/system/user/export")
                        .header("satoken-admin", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", exportPhone))))
                .andExpect(status().isOk());

        SysOperLog profileLog = awaitOperateLog("个人中心", OperateTypeEnum.UPDATE, "更新个人基本资料");
        assertThat(profileLog.getRequestParams())
                .contains("[EXCLUDED]")
                .doesNotContain(profilePhone, profileEmail);

        SysOperLog deptCreateLog = awaitOperateLog("部门管理", OperateTypeEnum.INSERT, "新增部门");
        assertThat(deptCreateLog.getRequestParams())
                .contains("[EXCLUDED]")
                .doesNotContain(deptPhone, deptEmail);

        SysOperLog deptUpdateLog = awaitOperateLog("部门管理", OperateTypeEnum.UPDATE, "修改部门");
        assertThat(deptUpdateLog.getRequestParams())
                .contains("[EXCLUDED]")
                .doesNotContain(deptUpdatedPhone, deptUpdatedEmail);

        SysOperLog configUpdateLog = awaitOperateLog("系统参数", OperateTypeEnum.UPDATE, "修改系统参数");
        assertThat(configUpdateLog.getRequestParams())
                .contains("[EXCLUDED]")
                .doesNotContain(SECRET_WEBHOOK_VALUE);

        SysOperLog importLog = awaitOperateLog("用户管理", OperateTypeEnum.IMPORT, "批量导入用户");
        assertThat(importLog.getRequestParams()).isNullOrEmpty();
        assertThat(importLog.getResponseResult()).isNullOrEmpty();

        SysOperLog exportLog = awaitOperateLog("用户管理", OperateTypeEnum.EXPORT, "导出用户数据");
        assertThat(exportLog.getRequestParams())
                .contains("[EXCLUDED]")
                .doesNotContain(exportPhone);
        assertThat(exportLog.getResponseResult()).isNullOrEmpty();

        // 恢复个人资料与清理临时测试部门，保证用例间互不污染
        mockMvc.perform(put("/admin/auth/profile")
                        .header("satoken-admin", profileToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "nickname", "个人资料专员",
                                "phone", "13968107000",
                                "email", "owner.init@example.com",
                                "gender", "1"
                        ))))
                .andExpect(status().isOk());
        TenantContextHolder.runWithTenant(TENANT_ID, () -> sysDeptMapper.deleteById(createdDeptId));
    }

    @Test
    @DisplayName("config/key 应当校验 user:update/tenant:create 分级放行，并端到端对照列表脱敏与详情明文")
    void shouldEnforceGradedConfigKeyAuthAndAdr0007ListMaskingVsDetailPlaintext() throws Exception {
        String userUpdateToken = loginAndGetToken(TENANT_CODE, "user_upd_only", COMMON_PASS);
        String platTenantCreateToken = loginAndGetToken(SolidConstants.PLATFORM_TENANT_CODE, "plat_tenant_creator", COMMON_PASS);
        String adminToken = loginAndGetToken(TENANT_CODE, "sec_admin", COMMON_PASS);
        String profileToken = loginAndGetToken(TENANT_CODE, "profile_owner", COMMON_PASS);

        mockMvc.perform(get("/admin/system/config/key/sys.user.initPassword")
                        .header("satoken-admin", userUpdateToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("Ateng@2026"));

        mockMvc.perform(get("/admin/system/config/key/internal.audit.secret")
                        .header("satoken-admin", userUpdateToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(get("/admin/system/config/key/sys.user.initPassword")
                        .header("satoken-admin", platTenantCreateToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("Ateng@2026"));

        mockMvc.perform(get("/admin/system/config/key/internal.nonpublic.key")
                        .header("satoken-admin", platTenantCreateToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        // 列表脱敏 vs 详情与个人中心真明文对照
        mockMvc.perform(get("/admin/system/user/page")
                        .header("satoken-admin", adminToken)
                        .param("username", "sec_admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].phone").value("139****1001"))
                .andExpect(jsonPath("$.data.records[0].email").value("s*******n@example.com"));

        mockMvc.perform(get("/admin/system/user/{id}", USER_TENANT_ADMIN)
                        .header("satoken-admin", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value("13968101001"))
                .andExpect(jsonPath("$.data.email").value("sec.admin@example.com"));

        mockMvc.perform(get("/admin/system/dept/tree")
                        .header("satoken-admin", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].phone").value("139****0001"))
                .andExpect(jsonPath("$.data[0].email").value("h****c@example.com"));

        mockMvc.perform(get("/admin/system/dept/{id}", DEPT_HQ)
                        .header("satoken-admin", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value("13968100001"))
                .andExpect(jsonPath("$.data.email").value("hq.sec@example.com"));

        mockMvc.perform(get("/admin/auth/profile")
                        .header("satoken-admin", profileToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value("13968107000"))
                .andExpect(jsonPath("$.data.email").value("owner.init@example.com"));
    }

    @Test
    @DisplayName("SELF 范围禁止批量导入他人，受限角色管理员禁止 PUT /role 追加未持有菜单或调高权角色 data-scope")
    void shouldBlockSelfScopeBatchImportAndRoleUpdatePrivilegeEscalation() throws Exception {
        String selfImportToken = loginAndGetToken(TENANT_CODE, "self_importer", COMMON_PASS);
        byte[] selfImportBytes = buildImportWorkbook(List.of(
                List.of("self_imp_1", "自建一", "安全总部/安全研发部", "", "", "", "", "", ""),
                List.of("self_imp_2", "自建二", "", "", "", "", "", "", "")
        ));

        mockMvc.perform(multipart("/admin/system/user/import")
                        .file(new MockMultipartFile(
                                "file", "self_scope_reject.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", selfImportBytes))
                        .header("satoken-admin", selfImportToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.successCount").value(0))
                .andExpect(jsonPath("$.data.failCount").value(2));

        Long insertedBySelf = TenantContextHolder.runWithTenant(TENANT_ID, () -> sysUserMapper.selectCount(
                Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getTenantId, TENANT_ID)
                        .in(SysUser::getUsername, List.of("self_imp_1", "self_imp_2"))
        ));
        assertThat(insertedBySelf).isZero();

        String subRoleToken = loginAndGetToken(TENANT_CODE, "sub_role_mgr", COMMON_PASS);
        mockMvc.perform(put("/admin/system/role")
                        .header("satoken-admin", subRoleToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "id", ROLE_LOWER_TARGET,
                                "roleName", "低权目标角色",
                                "roleKey", "lower_target_role",
                                "sort", 1,
                                "menuIds", List.of(1000L, 1500L, 1501L)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.FORBIDDEN.getCode()));

        mockMvc.perform(put("/admin/system/role/{id}/data-scope", ROLE_HIGH_ALL)
                        .header("satoken-admin", subRoleToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("dataScope", DataScopeType.SELF.getCode()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.FORBIDDEN.getCode()));
    }

    @Test
    @DisplayName("不存在账号应当走 310000 次 Dummy PBKDF2 防枚举，停用旧哈希账号拒绝升级，Long.MAX_VALUE 在线分页安全返回空页")
    void shouldPerformConstantTimeDummyHashRejectDisabledHashUpgradeAndGuardLongMaxOnlinePage() throws Exception {
        long startNanos = System.nanoTime();
        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", TENANT_CODE,
                                "username", "non_existent_user_68",
                                "password", "WrongPass@68"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(AuthResultCode.BAD_CREDENTIALS.getCode()));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(20L);

        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", TENANT_CODE,
                                "username", "disabled_legacy",
                                "password", "Ateng@Legacy1"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(AuthResultCode.ACCOUNT_DISABLED.getCode()));

        String legacyHashAfter = TenantContextHolder.runWithTenant(TENANT_ID, () ->
                sysUserMapper.selectById(USER_DISABLED_LEGACY).getPassword()
        );
        assertThat(legacyHashAfter).startsWith("$pbkdf2-sha256$10000$");
        assertThat(PasswordUtil.needsUpgrade(legacyHashAfter)).isTrue();

        String superToken = Spec4TestFixtures.loginAsSuperAdmin(mockMvc, objectMapper);
        mockMvc.perform(get("/admin/auth/online/page")
                        .header("satoken-admin", superToken)
                        .param("current", String.valueOf(Long.MAX_VALUE))
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isEmpty());
    }

    @Test
    @DisplayName("恶意 dictType、configKey、tenantCode 与负数 X-Tenant-Id 应当零负向缓存污染且保留超管合法会话")
    void shouldPreventNegativeCachePollutionAndRejectNegativeTenantHeaderEndToEnd() throws Exception {
        String superToken = Spec4TestFixtures.loginAsSuperAdmin(mockMvc, objectMapper);

        int dictBefore = dictDataCache.localSize();
        mockMvc.perform(get("/admin/system/dict/data/type/BAD!INVALID_TYPE")
                        .header("satoken-admin", superToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isEmpty());
        assertThat(dictDataCache.localSize()).isEqualTo(dictBefore);

        int configBefore = sysConfigCache.localSize();
        mockMvc.perform(get("/admin/system/config/key/public.bad:key")
                        .header("satoken-admin", superToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isEmpty());
        assertThat(sysConfigCache.localSize()).isEqualTo(configBefore);

        int tenantBefore = tenantStatusCache.localSize();
        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", "BAD!CODE",
                                "username", "any_user",
                                "password", "Ateng@Any1"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(AuthResultCode.TENANT_UNAVAILABLE.getCode()));
        assertThat(tenantStatusCache.localSize()).isEqualTo(tenantBefore);

        mockMvc.perform(post("/admin/auth/login")
                        .header(SolidConstants.TENANT_HEADER_NAME, "-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "any_user",
                                "password", "Ateng@Any1"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(AuthResultCode.TENANT_UNAVAILABLE.getCode()));
        assertThat(tenantStatusCache.localSize()).isEqualTo(tenantBefore);

        // 携带负数 X-Tenant-Id 头访问受保护端点被底座 TENANT_ID_PATTERN 前置拦截返回 400，且超管会话与负向缓存均不受影响
        mockMvc.perform(get("/admin/auth/info")
                        .header("satoken-admin", superToken)
                        .header(SolidConstants.TENANT_HEADER_NAME, "-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.BAD_REQUEST.getCode()));
        assertThat(tenantStatusCache.localSize()).isEqualTo(tenantBefore);

        mockMvc.perform(get("/admin/auth/info")
                        .header("satoken-admin", superToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.tenantId").value("0"));
    }

    private SysOperLog awaitOperateLog(String module, OperateTypeEnum operateType, String description) throws InterruptedException {
        return TenantAndPackageTestFixtures.awaitLatestOperateLog(
                sysOperLogMapper, TENANT_ID, module, operateType, description
        );
    }

    private String loginAndGetToken(String tenantCode, String username, String password) throws Exception {
        return Spec4TestFixtures.loginAndGetToken(mockMvc, objectMapper, tenantCode, username, password);
    }

    private void seedDept(Long id, Long parentId, String ancestors, String name, String phone, String email) {
        SysDept dept = new SysDept();
        dept.setId(id);
        dept.setTenantId(TENANT_ID);
        dept.setParentId(parentId);
        dept.setAncestors(ancestors);
        dept.setDeptName(name);
        dept.setPhone(phone);
        dept.setEmail(email);
        dept.setSort(1);
        dept.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysDeptMapper.insert(dept);
    }

    private void seedRole(Long tenantId, Long id, String name, String key, DataScopeType scope, List<Long> menuIds) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setTenantId(tenantId);
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
            rm.setTenantId(tenantId);
            rm.setRoleId(id);
            rm.setMenuId(menuId);
            sysRoleMenuMapper.insert(rm);
        }
    }

    private void seedUser(
            Long tenantId, Long id, Long deptId, String username, String nickname,
            String rawPassword, String phone, String email, Integer status, Long roleId) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setTenantId(tenantId);
        user.setDeptId(deptId);
        user.setUsername(username);
        user.setNickname(nickname);
        user.setPassword(PasswordUtil.hash(rawPassword));
        user.setPhone(phone);
        user.setEmail(email);
        user.setGender("1");
        user.setStatus(status);
        sysUserMapper.insert(user);

        if (roleId != null) {
            SysUserRole ur = new SysUserRole();
            ur.setTenantId(tenantId);
            ur.setUserId(id);
            ur.setRoleId(roleId);
            sysUserRoleMapper.insert(ur);
        }
    }

    private static byte[] buildImportWorkbook(List<List<String>> rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("用户导入");
            Row header = sheet.createRow(0);
            String[] cols = {"用户账号", "用户昵称", "归属部门", "岗位编码", "角色编码", "手机号码", "电子邮箱", "性别", "状态"};
            for (int i = 0; i < cols.length; i++) {
                header.createCell(i).setCellValue(cols[i]);
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<String> values = rows.get(r);
                for (int c = 0; c < values.size(); c++) {
                    row.createCell(c).setCellValue(values.get(c));
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }
}

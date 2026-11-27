package io.github.atengk.solid;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.PageResult;
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
import io.github.atengk.solid.system.entity.SysOperLog;
import io.github.atengk.solid.system.entity.SysPost;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysRoleDept;
import io.github.atengk.solid.system.entity.SysRoleMenu;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysTenantPackage;
import io.github.atengk.solid.system.entity.SysTenantPackageMenu;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.entity.SysUserRole;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysOperLogMapper;
import io.github.atengk.solid.system.mapper.SysPostMapper;
import io.github.atengk.solid.system.mapper.SysRoleDeptMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysRoleMenuMapper;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMenuMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import io.github.atengk.solid.system.request.UserCreateRequest;
import io.github.atengk.solid.system.request.UserQueryRequest;
import io.github.atengk.solid.system.request.UserResetPasswordRequest;
import io.github.atengk.solid.system.request.UserUpdateRequest;
import io.github.atengk.solid.system.service.SysUserQueryService;
import io.github.atengk.solid.system.service.SysUserService;
import io.github.atengk.solid.system.vo.UserDetailVO;
import io.github.atengk.solid.system.vo.UserVO;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户管理 CRUD、配额行锁防超卖、5 级 {@code @DataScope}、密文精确检索及事务提交后缓存/强退集成测试。
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
                "spring.datasource.url=jdbc:h2:mem:sys_user_crud_datascope_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        }
)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SysUserCrudAndDataScopeTest {

    private static final long PACKAGE_ID = 31001L;
    private static final long TENANT_ID = 31002L;
    private static final long QUOTA_TENANT_ID = 31003L;

    private static final long DEPT_ROOT = 31100L;
    private static final long DEPT_RD = 31101L;
    private static final long DEPT_BACKEND = 31102L;
    private static final long DEPT_SALES = 31103L;

    private static final long POST_DEV = 31201L;
    private static final long ROLE_FULL_USER_MGR = 31301L;
    private static final long ROLE_DEPT_AND_CHILD = 31302L;
    private static final long ROLE_CUSTOM_SALES = 31303L;
    private static final long ROLE_SELF_ONLY = 31304L;
    private static final long ROLE_DEPT_ONLY = 31305L;
    private static final long PLATFORM_NORMAL_ROLE = 31390L;

    private static final long USER_RD_LEAD = 31401L;
    private static final long USER_BACKEND_DEV = 31402L;
    private static final long USER_SALES_REP = 31403L;
    private static final long USER_ROOT_OP = 31404L;
    private static final long PLATFORM_SUB_USER = 31490L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SysUserService sysUserService;
    @Autowired
    private SysUserQueryService sysUserQueryService;
    @Autowired
    private PermissionCacheService permissionCacheService;
    @Autowired
    private LoginFailureLockService loginFailureLockService;
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
    private SysPostMapper sysPostMapper;
    @Autowired
    private SysRoleMapper sysRoleMapper;
    @Autowired
    private SysRoleMenuMapper sysRoleMenuMapper;
    @Autowired
    private SysRoleDeptMapper sysRoleDeptMapper;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;
    @Autowired
    private SysOperLogMapper sysOperLogMapper;

    @BeforeAll
    void setUpFixtures() {
        loginFailureLockService.clearLocalMemory();

        SysTenantPackage pkg = new SysTenantPackage();
        pkg.setId(PACKAGE_ID);
        pkg.setPackageName("用户域测试套餐");
        pkg.setMenuCheckStrictly(true);
        pkg.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysTenantPackageMapper.insert(pkg);

        for (Long menuId : List.of(1000L, 1100L, 1101L, 1102L, 1103L, 1104L)) {
            SysTenantPackageMenu pm = new SysTenantPackageMenu();
            pm.setPackageId(PACKAGE_ID);
            pm.setMenuId(menuId);
            sysTenantPackageMenuMapper.insert(pm);
        }

        insertTenant(TENANT_ID, "T31002", "用户DataScope测试租户", PACKAGE_ID, -1);
        insertTenant(QUOTA_TENANT_ID, "T31003", "用户配额测试租户", PACKAGE_ID, 3);

        TenantContextHolder.runWithTenant(SolidConstants.PLATFORM_TENANT_ID, () -> {
            insertRole(SolidConstants.PLATFORM_TENANT_ID, PLATFORM_NORMAL_ROLE,
                    "平台普通用户管理员", "platform_user_mgr", DataScopeType.ALL,
                    List.of(1000L, 1100L, 1101L, 1102L, 1103L, 1104L));
            insertUser(SolidConstants.PLATFORM_TENANT_ID, PLATFORM_SUB_USER, SolidConstants.DEFAULT_ROOT_DEPT_ID,
                    "plat_sub_mgr", "平台子管理员", "Ateng@Plat1", null, null, PLATFORM_NORMAL_ROLE);
        });

        TenantContextHolder.runWithTenant(TENANT_ID, () -> {
            insertDept(TENANT_ID, DEPT_ROOT, 0L, "0", "华东总部");
            insertDept(TENANT_ID, DEPT_RD, DEPT_ROOT, "0," + DEPT_ROOT, "研发中心");
            insertDept(TENANT_ID, DEPT_BACKEND, DEPT_RD, "0," + DEPT_ROOT + "," + DEPT_RD, "后端开发组");
            insertDept(TENANT_ID, DEPT_SALES, DEPT_ROOT, "0," + DEPT_ROOT, "市场销售部");

            SysPost devPost = new SysPost();
            devPost.setId(POST_DEV);
            devPost.setTenantId(TENANT_ID);
            devPost.setPostCode("JAVA_DEV");
            devPost.setPostName("Java研发");
            devPost.setSort(1);
            devPost.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysPostMapper.insert(devPost);

            List<Long> userMenus = List.of(1000L, 1100L, 1101L, 1102L, 1103L, 1104L);
            insertRole(TENANT_ID, ROLE_FULL_USER_MGR, "租户全权用户管理员", "tenant_user_mgr",
                    DataScopeType.ALL, userMenus);
            insertRole(TENANT_ID, ROLE_DEPT_AND_CHILD, "部门及下级主管", "dept_child_mgr",
                    DataScopeType.DEPT_AND_CHILD, List.of(1000L, 1100L, 1101L));
            insertRole(TENANT_ID, ROLE_CUSTOM_SALES, "跨部门销售观察员", "custom_sales_view",
                    DataScopeType.CUSTOM, List.of(1000L, 1100L, 1101L));
            insertRole(TENANT_ID, ROLE_SELF_ONLY, "仅本人观察员", "self_view",
                    DataScopeType.SELF, List.of(1000L, 1100L, 1101L));
            insertRole(TENANT_ID, ROLE_DEPT_ONLY, "仅本部门观察员", "dept_only_view",
                    DataScopeType.DEPT, List.of(1000L, 1100L, 1101L));

            SysRoleDept rd = new SysRoleDept();
            rd.setTenantId(TENANT_ID);
            rd.setRoleId(ROLE_CUSTOM_SALES);
            rd.setDeptId(DEPT_SALES);
            sysRoleDeptMapper.insert(rd);

            insertUser(TENANT_ID, USER_ROOT_OP, DEPT_ROOT, "root_admin", "总部用户管理员",
                    "Ateng@31404", "13931404000", "root.admin@example.com", ROLE_FULL_USER_MGR);
            insertUser(TENANT_ID, USER_RD_LEAD, DEPT_RD, "rd_lead", "研发总监",
                    "Ateng@31401", "13931401000", "rd.lead@example.com", ROLE_DEPT_AND_CHILD);
            insertUser(TENANT_ID, USER_BACKEND_DEV, DEPT_BACKEND, "backend_dev", "后端工程师",
                    "Ateng@31402", "13931402000", "backend.dev@example.com", ROLE_SELF_ONLY);
            insertUser(TENANT_ID, USER_SALES_REP, DEPT_SALES, "sales_rep", "销售专员",
                    "Ateng@31403", "13931403000", "sales.rep@example.com", ROLE_DEPT_ONLY);
        });

        TenantContextHolder.runWithTenant(QUOTA_TENANT_ID, () -> {
            insertDept(QUOTA_TENANT_ID, 31900L, 0L, "0", "配额测试总部");
            insertUser(QUOTA_TENANT_ID, 31901L, 31900L, "quota_seed_1", "配额种子用户",
                    "Ateng@Quota1", null, null, null);
        });
    }

    @AfterEach
    void cleanContexts() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        DataPermissionContextHolder.clear();
        MDC.clear();
        loginFailureLockService.clearLocalMemory();
    }

    @Test
    @DisplayName("并发新增校验 TenantQuotaGuard 悲观行锁防超卖，且停用转启用 delta=0 在满配额时允许恢复")
    void shouldPreventQuotaOversellConcurrentlyAndAllowEnableWhenAtLimit() throws Exception {
        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger quotaExceededCount = new AtomicInteger();
        List<Long> createdIds = java.util.Collections.synchronizedList(new ArrayList<>());

        try {
            List<Future<?>> futures = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    startGate.await();
                    try {
                        Long id = TenantContextHolder.runWithTenant(QUOTA_TENANT_ID, () -> {
                            UserCreateRequest req = new UserCreateRequest();
                            req.setUsername("quota_conc_" + idx);
                            req.setNickname("并发配额" + idx);
                            req.setPassword("Ateng@Conc9");
                            req.setDeptId(31900L);
                            return sysUserService.createUser(req);
                        });
                        createdIds.add(id);
                        successCount.incrementAndGet();
                    } catch (BusinessException ex) {
                        if (SystemResultCode.TENANT_QUOTA_OR_SCOPE_EXCEEDED.getCode().equals(ex.getCode())) {
                            quotaExceededCount.incrementAndGet();
                        } else {
                            throw ex;
                        }
                    } finally {
                        TenantContextHolder.clear();
                        UserContextHolder.clear();
                        DataPermissionContextHolder.clear();
                    }
                    return null;
                }));
            }
            startGate.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // account_count = 3，已有 1 个种子用户，并发 5 线程最多只能成功 2 个
        assertThat(successCount.get()).isEqualTo(2);
        assertThat(quotaExceededCount.get()).isEqualTo(3);

        Long totalActiveUsers = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_user WHERE tenant_id = ? AND deleted = 0",
                Long.class, QUOTA_TENANT_ID
        );
        assertThat(totalActiveUsers).isEqualTo(3L);

        // 满配额态（3/3）下将存量用户停用后再次启用（delta=0）必须放行，但继续新增（delta=1）仍须抛 102006
        Long targetId = createdIds.get(0);
        TenantContextHolder.runWithTenant(QUOTA_TENANT_ID, () -> {
            sysUserService.updateUserStatus(targetId, CommonStatusEnum.DISABLED.getCode());
            sysUserService.updateUserStatus(targetId, CommonStatusEnum.NORMAL.getCode());

            UserCreateRequest overflow = new UserCreateRequest();
            overflow.setUsername("quota_overflow");
            overflow.setNickname("超限用户");
            overflow.setPassword("Ateng@Over9");
            assertThatThrownBy(() -> sysUserService.createUser(overflow))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(SystemResultCode.TENANT_QUOTA_OR_SCOPE_EXCEEDED.getCode()));
        });
    }

    @Test
    @DisplayName("5 级 @DataScope 列表/详情、部门子树、确定性手机号精确查询与 /options?keyword= 契约验证")
    void shouldEnforceFiveLevelDataScopeAndExactEncryptedPhoneSearch() throws Exception {
        // 1. DEPT_AND_CHILD（rd_lead，部门 31101 -> 可见 31101 研发总监 与 31102 后端工程师，不可见 31103 与 31404）
        String rdToken = loginAndGetToken("T31002", "rd_lead", "Ateng@31401");
        MvcResult rdPageRes = mockMvc.perform(get("/admin/system/user/page")
                        .header("satoken-admin", rdToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode rdRecords = objectMapper.readTree(rdPageRes.getResponse().getContentAsString())
                .path("data").path("records");
        List<String> rdVisibleUsernames = rdRecords.findValuesAsText("username");
        assertThat(rdVisibleUsernames).containsExactlyInAnyOrder("rd_lead", "backend_dev");
        JsonNode backendRow = null;
        for (JsonNode row : rdRecords) {
            if ("backend_dev".equals(row.path("username").asText())) {
                backendRow = row;
                break;
            }
        }
        assertThat(backendRow).isNotNull();
        assertThat(backendRow.path("phone").asText()).isEqualTo("139****2000");
        assertThat(backendRow.path("email").asText()).isEqualTo("b*********v@example.com");

        // 越权查看非可见部门成员详情返回 102001
        mockMvc.perform(get("/admin/system/user/{id}", USER_SALES_REP)
                        .header("satoken-admin", rdToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(SystemResultCode.RECORD_NOT_FOUND.getCode()));

        // 可见成员详情返回完整内存真明文
        mockMvc.perform(get("/admin/system/user/{id}", USER_BACKEND_DEV)
                        .header("satoken-admin", rdToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.phone").value("13931402000"))
                .andExpect(jsonPath("$.data.email").value("backend.dev@example.com"))
                .andExpect(jsonPath("$.data.deptName").value("后端开发组"));

        // 2. 确定性手机号精确检索：完整手机号命中，截断子串不命中
        mockMvc.perform(get("/admin/system/user/page")
                        .header("satoken-admin", rdToken)
                        .param("phone", "13931402000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].username").value("backend_dev"));

        mockMvc.perform(get("/admin/system/user/page")
                        .header("satoken-admin", rdToken)
                        .param("phone", "1393140"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        // 3. 部门子树筛选（ALL 角色按 DEPT_RD 过滤包含子组 DEPT_BACKEND）
        String rootMgrToken = loginAndGetToken("T31002", "root_admin", "Ateng@31404");
        MvcResult deptSubtreeRes = mockMvc.perform(get("/admin/system/user/page")
                        .header("satoken-admin", rootMgrToken)
                        .param("deptId", String.valueOf(DEPT_RD)))
                .andExpect(status().isOk())
                .andReturn();
        List<String> subtreeUsernames = objectMapper.readTree(deptSubtreeRes.getResponse().getContentAsString())
                .path("data").path("records").findValuesAsText("username");
        assertThat(subtreeUsernames).containsExactlyInAnyOrder("rd_lead", "backend_dev");

        // 4. /options?keyword= 强制受 @DataScope 与功能权限约束（按昵称或账号检索，对齐前端 user.ts 契约）
        MvcResult scopedEmptyOptRes = mockMvc.perform(get("/admin/system/user/options")
                        .header("satoken-admin", rdToken)
                        .param("keyword", "销售"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        assertThat(objectMapper.readTree(scopedEmptyOptRes.getResponse().getContentAsString()).path("data")).isEmpty();

        MvcResult scopedMatchOptRes = mockMvc.perform(get("/admin/system/user/options")
                        .header("satoken-admin", rdToken)
                        .param("keyword", "后端"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode scopedMatchData = objectMapper.readTree(scopedMatchOptRes.getResponse().getContentAsString()).path("data");
        assertThat(scopedMatchData).hasSize(1);
        assertThat(scopedMatchData.get(0).path("username").asText()).isEqualTo("backend_dev");

        MvcResult optRes = mockMvc.perform(get("/admin/system/user/options")
                        .header("satoken-admin", rootMgrToken)
                        .param("keyword", "销售"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode optData = objectMapper.readTree(optRes.getResponse().getContentAsString()).path("data");
        assertThat(optData).hasSize(1);
        assertThat(optData.get(0).path("userId").asText()).isEqualTo(String.valueOf(USER_SALES_REP));
        assertThat(optData.get(0).path("username").asText()).isEqualTo("sales_rep");

        // 无 system:dept:create|update|user:list 权限的账号访问 /options 应当返回 403
        long noOptionPermRoleId = 31398L;
        long noOptionPermUserId = 31498L;
        TenantContextHolder.runWithTenant(TENANT_ID, () -> {
            insertRole(TENANT_ID, noOptionPermRoleId, "无用户选项权限", "no_opt_role",
                    DataScopeType.SELF, List.of(1000L));
            insertUser(TENANT_ID, noOptionPermUserId, DEPT_BACKEND, "no_opt_user", "无权限者",
                    "Ateng@31498", null, null, noOptionPermRoleId);
        });
        try {
            String noOptToken = loginAndGetToken("T31002", "no_opt_user", "Ateng@31498");
            mockMvc.perform(get("/admin/system/user/options")
                            .header("satoken-admin", noOptToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));
        } finally {
            TenantContextHolder.runWithTenant(TENANT_ID, () -> {
                sysUserRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, noOptionPermUserId));
                sysUserMapper.deleteById(noOptionPermUserId);
                sysRoleMenuMapper.delete(Wrappers.<SysRoleMenu>lambdaQuery().eq(SysRoleMenu::getRoleId, noOptionPermRoleId));
                sysRoleMapper.deleteById(noOptionPermRoleId);
            });
        }

        // 5. Service 层验证 SELF、DEPT、CUSTOM + SELF 多角色并集
        TenantContextHolder.runWithTenant(TENANT_ID, () -> {
            UserContextHolder.runWithUser(USER_BACKEND_DEV, () -> {
                PageResult<UserVO> selfPage = sysUserQueryService.pageUsers(new UserQueryRequest());
                assertThat(selfPage.getRecords()).extracting(UserVO::getUsername).containsExactly("backend_dev");
            });

            UserContextHolder.runWithUser(USER_SALES_REP, () -> {
                PageResult<UserVO> deptPage = sysUserQueryService.pageUsers(new UserQueryRequest());
                assertThat(deptPage.getRecords()).extracting(UserVO::getUsername).containsExactly("sales_rep");
            });

            // 为 backend_dev 追加 CUSTOM(DEPT_SALES) 角色，形成 CUSTOM + SELF 并集且不穿透租户或软删防线
            SysUserRole customBind = new SysUserRole();
            customBind.setTenantId(TENANT_ID);
            customBind.setUserId(USER_BACKEND_DEV);
            customBind.setRoleId(ROLE_CUSTOM_SALES);
            sysUserRoleMapper.insert(customBind);
            try {
                UserContextHolder.runWithUser(USER_BACKEND_DEV, () -> {
                    PageResult<UserVO> unionPage = sysUserQueryService.pageUsers(new UserQueryRequest());
                    assertThat(unionPage.getRecords()).extracting(UserVO::getUsername)
                            .containsExactlyInAnyOrder("backend_dev", "sales_rep");
                });
            } finally {
                sysUserRoleMapper.deleteById(customBind.getId());
            }
        });
    }

    @Test
    @DisplayName("102007 护栏（内置超管与登录用户自身）、102002 冲突、pwd_update_time=null 与操作日志脱敏")
    void shouldEnforceBuiltinAndSelfProtectionsAndHidePasswordsInOperateLog() throws Exception {
        String superToken = loginAndGetToken("000000", "superadmin", "Ateng@Test2026");
        Long superUserId = TenantContextHolder.runWithTenant(SolidConstants.PLATFORM_TENANT_ID, () ->
                sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getTenantId, SolidConstants.PLATFORM_TENANT_ID)
                        .eq(SysUser::getUsername, "superadmin")).getId()
        );

        // 1. 内置超管禁停用、禁删除、禁剥离 super_admin 角色
        mockMvc.perform(put("/admin/system/user/{id}/status", superUserId)
                        .header("satoken-admin", superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(SystemResultCode.SYSTEM_BUILTIN_PROTECTED.getCode()));

        mockMvc.perform(delete("/admin/system/user/{ids}", superUserId)
                        .header("satoken-admin", superToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(SystemResultCode.SYSTEM_BUILTIN_PROTECTED.getCode()));

        mockMvc.perform(put("/admin/system/user")
                        .header("satoken-admin", superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "id", superUserId,
                                "nickname", "超级管理员",
                                "roleIds", List.of(PLATFORM_NORMAL_ROLE)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(SystemResultCode.SYSTEM_BUILTIN_PROTECTED.getCode()));

        // 2. 平台非超管管理员禁止修改超管、禁止非本人重置超管密码、禁止分配 super_admin 角色
        String platSubToken = loginAndGetToken("000000", "plat_sub_mgr", "Ateng@Plat1");
        mockMvc.perform(put("/admin/system/user/{id}/reset-password", superUserId)
                        .header("satoken-admin", platSubToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"Ateng@New999\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(SystemResultCode.SYSTEM_BUILTIN_PROTECTED.getCode()));

        mockMvc.perform(put("/admin/system/user")
                        .header("satoken-admin", platSubToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "id", superUserId,
                                "nickname", "越权改超管",
                                "roleIds", List.of(SolidConstants.SUPER_ADMIN_ROLE_ID)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(SystemResultCode.SYSTEM_BUILTIN_PROTECTED.getCode()));

        mockMvc.perform(post("/admin/system/user")
                        .header("satoken-admin", platSubToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "illegal_sa",
                                "nickname", "越权建超管",
                                "password", "Ateng@Pass1",
                                "roleIds", List.of(SolidConstants.SUPER_ADMIN_ROLE_ID)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(SystemResultCode.SYSTEM_BUILTIN_PROTECTED.getCode()));

        // 3. 当前登录用户自身（root_admin）禁停用自己、禁删除自己、禁自篡改角色
        String rootMgrToken = loginAndGetToken("T31002", "root_admin", "Ateng@31404");
        mockMvc.perform(put("/admin/system/user/{id}/status", USER_ROOT_OP)
                        .header("satoken-admin", rootMgrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(SystemResultCode.SYSTEM_BUILTIN_PROTECTED.getCode()));

        mockMvc.perform(delete("/admin/system/user/{ids}", USER_ROOT_OP)
                        .header("satoken-admin", rootMgrToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(SystemResultCode.SYSTEM_BUILTIN_PROTECTED.getCode()));

        mockMvc.perform(put("/admin/system/user")
                        .header("satoken-admin", rootMgrToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "id", USER_ROOT_OP,
                                "nickname", "自改角色拦截",
                                "deptId", DEPT_ROOT,
                                "roleIds", List.of(ROLE_SELF_ONLY)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(SystemResultCode.SYSTEM_BUILTIN_PROTECTED.getCode()));

        // 4. 合法新增用户（留空密码回退 sys.user.initPassword，pwd_update_time 为 null），且审计日志不含明文密码
        MvcResult createRes = mockMvc.perform(post("/admin/system/user")
                        .header("satoken-admin", rootMgrToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "new_hire_1",
                                "nickname", "新员工一号",
                                "password", "",
                                "deptId", DEPT_BACKEND,
                                "postIds", List.of(POST_DEV),
                                "roleIds", List.of(ROLE_SELF_ONLY),
                                "phone", "13931499001",
                                "email", "New.Hire@Example.COM",
                                "gender", "0"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        long newUserId = Long.parseLong(objectMapper.readTree(createRes.getResponse().getContentAsString()).path("data").asText());

        try {
            UserDetailVO detail = TenantContextHolder.runWithTenant(TENANT_ID, () ->
                    UserContextHolder.runWithUser(USER_ROOT_OP, () -> sysUserQueryService.getUserDetail(newUserId))
            );
            assertThat(detail.getPwdUpdateTime()).isNull();
            assertThat(detail.getEmail()).isEqualTo("new.hire@example.com");
            assertThat(detail.getPhone()).isEqualTo("13931499001");
            assertThat(detail.getRoleIds()).containsExactly(ROLE_SELF_ONLY);
            assertThat(detail.getPostIds()).containsExactly(POST_DEV);

            // 带首尾空格口令禁止静默 trim 放行
            mockMvc.perform(put("/admin/system/user/{id}/reset-password", newUserId)
                            .header("satoken-admin", rootMgrToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\" Ateng@2026 \"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(100001));

            // 5. 同租户重复手机号 / 大写邮箱触发 102002；裁切后不足 2 字符昵称（" a "）触发 100001
            mockMvc.perform(post("/admin/system/user")
                            .header("satoken-admin", rootMgrToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", "trimmed_bad_nick",
                                    "nickname", " a ",
                                    "password", "Ateng@Pass1"
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(100001));

            mockMvc.perform(put("/admin/system/user")
                            .header("satoken-admin", rootMgrToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "id", newUserId,
                                    "nickname", " b ",
                                    "deptId", DEPT_BACKEND
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(100001));

            mockMvc.perform(post("/admin/system/user")
                            .header("satoken-admin", rootMgrToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", "new_hire_2",
                                    "nickname", "冲突测试",
                                    "phone", " 13931499001 "
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(SystemResultCode.DUPLICATE_RESOURCE.getCode()));

            // 6. 重置密码后校验 @OperateLog(saveParams=false) 未记录新口令到 sys_oper_log
            mockMvc.perform(put("/admin/system/user/{id}/reset-password", newUserId)
                            .header("satoken-admin", rootMgrToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"SecretReset#99\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            SysOperLog createOperLog = awaitOperateLog(TENANT_ID, "用户管理", OperateTypeEnum.INSERT, "新增用户");
            assertThat(createOperLog.getRequestParams()).isNullOrEmpty();

            SysOperLog resetOperLog = awaitOperateLog(TENANT_ID, "用户管理", OperateTypeEnum.UPDATE, "重置用户密码");
            assertThat(resetOperLog.getRequestParams()).isNullOrEmpty();
        } finally {
            TenantContextHolder.runWithTenant(TENANT_ID, () -> sysUserService.deleteUsers(String.valueOf(newUserId)));
        }
    }

    @Test
    @DisplayName("提交后失效 PermissionCacheService 与 LoginHelper 强退，且事务回滚时不误清缓存和会话")
    void shouldEvictCacheAndLogoutOnlyAfterCommitAndPreserveOnRollback() throws Exception {
        // 先创建临时隔离用户用于缓存、会话强退与回滚测试
        Long victimId = TenantContextHolder.runWithTenant(TENANT_ID, () -> {
            UserCreateRequest req = new UserCreateRequest();
            req.setUsername("tx_victim");
            req.setNickname("事务测试用户");
            req.setPassword("Ateng@Tx123");
            req.setDeptId(DEPT_SALES);
            req.setRoleIds(List.of(ROLE_DEPT_ONLY));
            return sysUserService.createUser(req);
        });

        try {
            // 模拟在线登录会话与预热权限缓存
            String victimToken = loginAndGetToken("T31002", "tx_victim", "Ateng@Tx123");
            String userRolesKey = PermissionCacheService.buildUserRolesKey(
                    String.valueOf(victimId), SolidConstants.ADMIN_LOGIN_TYPE, String.valueOf(TENANT_ID)
            );
            permissionCacheService.set(userRolesKey, "[\"dept_only_view\"]", 60L);

            // 1. 在 updateUser 中注入非法部门导致事务回滚：缓存与在线会话必须保持完好！
            TenantContextHolder.runWithTenant(TENANT_ID, () -> {
                UserUpdateRequest badUpdate = new UserUpdateRequest();
                badUpdate.setId(victimId);
                badUpdate.setNickname("回滚保护");
                badUpdate.setDeptId(99999999L);
                badUpdate.setRoleIds(List.of(ROLE_SELF_ONLY));
                badUpdate.setStatus(CommonStatusEnum.DISABLED.getCode());
                assertThatThrownBy(() -> sysUserService.updateUser(badUpdate))
                        .isInstanceOf(BusinessException.class);
            });
            assertThat(permissionCacheService.get(userRolesKey)).isEqualTo("[\"dept_only_view\"]");
            mockMvc.perform(get("/admin/auth/info").header("satoken-admin", victimToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // 2. 合法修改角色提交后清理缓存，但不强退会话
            TenantContextHolder.runWithTenant(TENANT_ID, () -> {
                UserUpdateRequest roleUpdate = new UserUpdateRequest();
                roleUpdate.setId(victimId);
                roleUpdate.setNickname("已改角色");
                roleUpdate.setDeptId(DEPT_SALES);
                roleUpdate.setRoleIds(List.of(ROLE_SELF_ONLY));
                sysUserService.updateUser(roleUpdate);
            });
            assertThat(permissionCacheService.get(userRolesKey)).isNull();
            mockMvc.perform(get("/admin/auth/info").header("satoken-admin", victimToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // 3. 管理员重置密码：置空 pwd_update_time 且提交后强退在线会话（原 token 变 401）
            TenantContextHolder.runWithTenant(TENANT_ID, () -> {
                // 先把 pwd_update_time 设为非空以验证被置 null
                sysUserMapper.update(new SysUser(), Wrappers.<SysUser>lambdaUpdate()
                        .eq(SysUser::getTenantId, TENANT_ID)
                        .eq(SysUser::getId, victimId)
                        .set(SysUser::getPwdUpdateTime, LocalDateTime.now()));
                UserResetPasswordRequest resetReq = new UserResetPasswordRequest();
                resetReq.setPassword("Ateng@Reset9");
                sysUserService.resetPassword(victimId, resetReq);
            });
            UserDetailVO afterReset = TenantContextHolder.runWithTenant(TENANT_ID, () ->
                    UserContextHolder.runWithUser(USER_ROOT_OP, () -> sysUserQueryService.getUserDetail(victimId))
            );
            assertThat(afterReset.getPwdUpdateTime()).isNull();
            mockMvc.perform(get("/admin/auth/info").header("satoken-admin", victimToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(401));

            // 4. 重新登录并验证停用提交后同步清理缓存与强退会话
            String reloginToken = loginAndGetToken("T31002", "tx_victim", "Ateng@Reset9");
            permissionCacheService.set(userRolesKey, "[\"self_view\"]", 60L);
            TenantContextHolder.runWithTenant(TENANT_ID, () ->
                    sysUserService.updateUserStatus(victimId, CommonStatusEnum.DISABLED.getCode())
            );
            assertThat(permissionCacheService.get(userRolesKey)).isNull();
            mockMvc.perform(get("/admin/auth/info").header("satoken-admin", reloginToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(401));
        } finally {
            TenantContextHolder.runWithTenant(TENANT_ID, () -> sysUserService.deleteUsers(String.valueOf(victimId)));
        }
    }

    @Test
    @DisplayName("DEPT_AND_CHILD 写权限用户禁跨部门越权修改/停用/改密/删除，范围内部门用户可正常变更与删除")
    void shouldEnforceDataScopeOnWriteOperations() throws Exception {
        long tempRoleId = 31388L;
        long scopedWriterId = 31488L;
        TenantContextHolder.runWithTenant(TENANT_ID, () -> {
            insertRole(TENANT_ID, tempRoleId, "研发域写管理员", "rd_child_writer",
                    DataScopeType.DEPT_AND_CHILD, List.of(1000L, 1100L, 1101L, 1102L, 1103L, 1104L));
            insertUser(TENANT_ID, scopedWriterId, DEPT_RD, "rd_writer", "研发写管理员",
                    "Ateng@31488", null, null, tempRoleId);
        });

        try {
            String writerToken = loginAndGetToken("T31002", "rd_writer", "Ateng@31488");

            // 1. 越权修改/停用/重置密码/删除销售部（DEPT_SALES）成员全部被 @DataScope 拦截返回 102001
            mockMvc.perform(put("/admin/system/user")
                            .header("satoken-admin", writerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "id", USER_SALES_REP,
                                    "nickname", "越权改销售",
                                    "deptId", DEPT_SALES
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(SystemResultCode.RECORD_NOT_FOUND.getCode()));

            mockMvc.perform(put("/admin/system/user/{id}/status", USER_SALES_REP)
                            .header("satoken-admin", writerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":1}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(SystemResultCode.RECORD_NOT_FOUND.getCode()));

            mockMvc.perform(put("/admin/system/user/{id}/reset-password", USER_SALES_REP)
                            .header("satoken-admin", writerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"Ateng@Idor9\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(SystemResultCode.RECORD_NOT_FOUND.getCode()));

            mockMvc.perform(delete("/admin/system/user/{ids}", USER_SALES_REP)
                            .header("satoken-admin", writerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(SystemResultCode.RECORD_NOT_FOUND.getCode()));

            // 2. 越权新增到管辖外部门（DEPT_SALES）或置空部门均被 @DataScope 写路径防线拒绝（100003）
            mockMvc.perform(post("/admin/system/user")
                            .header("satoken-admin", writerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", "out_scope_create",
                                    "nickname", "越权建销售",
                                    "password", "Ateng@Sub1",
                                    "deptId", DEPT_SALES
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CommonBizResultCode.DATA_SCOPE_ERROR.getCode()));

            mockMvc.perform(post("/admin/system/user")
                            .header("satoken-admin", writerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", "null_dept_create",
                                    "nickname", "无部门越权建",
                                    "password", "Ateng@Sub1"
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CommonBizResultCode.DATA_SCOPE_ERROR.getCode()));

            // 3. 在管辖子部门 DEPT_BACKEND 下通过 HTTP 新建测试成员，验证越权迁移部门被拒、管辖内更新与审计脱敏、以及混删拦截
            MvcResult createdSubRes = mockMvc.perform(post("/admin/system/user")
                            .header("satoken-admin", writerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", "rd_scope_sub",
                                    "nickname", "子部门成员",
                                    "password", "Ateng@Sub1",
                                    "deptId", DEPT_BACKEND
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andReturn();
            Long subMemberId = Long.parseLong(objectMapper.readTree(createdSubRes.getResponse().getContentAsString())
                    .path("data").asText());

            // 禁止将下属用户迁移到管辖范围外部门（DEPT_SALES）或置空归属部门
            mockMvc.perform(put("/admin/system/user")
                            .header("satoken-admin", writerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "id", subMemberId,
                                    "nickname", "越权迁部门",
                                    "deptId", DEPT_SALES
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CommonBizResultCode.DATA_SCOPE_ERROR.getCode()));

            mockMvc.perform(put("/admin/system/user")
                            .header("satoken-admin", writerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "id", subMemberId,
                                    "nickname", "置空部门越权"
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CommonBizResultCode.DATA_SCOPE_ERROR.getCode()));

            mockMvc.perform(put("/admin/system/user")
                            .header("satoken-admin", writerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "id", subMemberId,
                                    "nickname", "子部门成员已更新",
                                    "deptId", DEPT_BACKEND,
                                    "phone", "13931488777",
                                    "email", "sub.secret@example.com"
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            SysOperLog updateOperLog = awaitOperateLog(TENANT_ID, "用户管理", OperateTypeEnum.UPDATE, "修改用户");
            assertThat(updateOperLog.getRequestParams())
                    .contains("[EXCLUDED]")
                    .doesNotContain("13931488777", "sub.secret@example.com");

            mockMvc.perform(put("/admin/system/user/{id}/status", subMemberId)
                            .header("satoken-admin", writerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":1}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            mockMvc.perform(put("/admin/system/user/{id}/reset-password", subMemberId)
                            .header("satoken-admin", writerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"Ateng@Sub2\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // 混合越权批量删除（含可见与不可见 ID）整批拒绝
            mockMvc.perform(delete("/admin/system/user/{ids}", subMemberId + "," + USER_SALES_REP)
                            .header("satoken-admin", writerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(SystemResultCode.RECORD_NOT_FOUND.getCode()));

            // 仅删除范围内子部门成员成功
            mockMvc.perform(delete("/admin/system/user/{ids}", subMemberId)
                            .header("satoken-admin", writerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        } finally {
            TenantContextHolder.runWithTenant(TENANT_ID, () -> {
                sysUserRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, scopedWriterId));
                sysUserMapper.deleteById(scopedWriterId);
                sysRoleMenuMapper.delete(Wrappers.<SysRoleMenu>lambdaQuery().eq(SysRoleMenu::getRoleId, tempRoleId));
                sysRoleMapper.deleteById(tempRoleId);
            });
        }
    }

    private SysOperLog awaitOperateLog(
            Long tenantId,
            String module,
            OperateTypeEnum operateType,
            String description
    ) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(5).toMillis();
        while (System.currentTimeMillis() < deadline) {
            List<SysOperLog> logs = TenantContextHolder.runWithTenant(tenantId, () -> sysOperLogMapper.selectList(
                    Wrappers.<SysOperLog>lambdaQuery()
                            .eq(SysOperLog::getTenantId, tenantId)
                            .eq(SysOperLog::getModule, module)
                            .eq(SysOperLog::getOperateType, operateType.getCode())
                            .eq(SysOperLog::getDescription, description)
            ));
            if (logs != null && !logs.isEmpty()) {
                return logs.get(0);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待操作审计日志异步落库超时: " + description);
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
        JsonNode root = objectMapper.readTree(res.getResponse().getContentAsString());
        return root.path("data").path("tokenValue").asText();
    }

    private void insertTenant(Long id, String code, String name, Long packageId, int accountCount) {
        SysTenant tenant = new SysTenant();
        tenant.setId(id);
        tenant.setTenantCode(code);
        tenant.setTenantName(name);
        tenant.setPackageId(packageId);
        tenant.setAccountCount(accountCount);
        tenant.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysTenantMapper.insert(tenant);
    }

    private void insertDept(Long tenantId, Long id, Long parentId, String ancestors, String name) {
        SysDept dept = new SysDept();
        dept.setId(id);
        dept.setTenantId(tenantId);
        dept.setParentId(parentId);
        dept.setAncestors(ancestors);
        dept.setDeptName(name);
        dept.setSort(1);
        dept.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysDeptMapper.insert(dept);
    }

    private void insertRole(
            Long tenantId,
            Long id,
            String name,
            String key,
            DataScopeType scope,
            List<Long> menuIds
    ) {
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

    private void insertUser(
            Long tenantId,
            Long id,
            Long deptId,
            String username,
            String nickname,
            String rawPassword,
            String phone,
            String email,
            Long roleId
    ) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setTenantId(tenantId);
        user.setDeptId(deptId);
        user.setUsername(username);
        user.setNickname(nickname);
        user.setPassword(PasswordUtil.hash(rawPassword));
        user.setPhone(phone);
        user.setEmail(email);
        user.setGender("2");
        user.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysUserMapper.insert(user);

        if (roleId != null) {
            SysUserRole ur = new SysUserRole();
            ur.setTenantId(tenantId);
            ur.setUserId(id);
            ur.setRoleId(roleId);
            sysUserRoleMapper.insert(ur);
        }
    }
}

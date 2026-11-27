package io.github.atengk.solid;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.satoken.cache.PermissionCacheService;
import io.github.atengk.boot.satoken.defense.LoginFailureLockService;
import io.github.atengk.solid.TenantAndPackageTestFixtures.FailOnRollbackTrigger;
import io.github.atengk.solid.TenantAndPackageTestFixtures.TenantSeedSpec;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.system.api.dto.TenantAuthDTO;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysMenu;
import io.github.atengk.solid.system.entity.SysOperLog;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysRoleMenu;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysTenantPackage;
import io.github.atengk.solid.system.entity.SysTenantPackageMenu;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysMenuMapper;
import io.github.atengk.solid.system.mapper.SysOperLogMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysRoleMenuMapper;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMenuMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import io.github.atengk.solid.system.request.TenantCreateRequest;
import io.github.atengk.solid.system.service.SysTenantService;
import io.github.atengk.solid.system.support.TenantStatusCache;
import io.github.atengk.solid.system.vo.CreateTenantVO;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.atengk.solid.TenantAndPackageTestFixtures.awaitLatestOperateLog;
import static io.github.atengk.solid.TenantAndPackageTestFixtures.createPackageViaHttp;
import static io.github.atengk.solid.TenantAndPackageTestFixtures.createTenantViaHttp;
import static io.github.atengk.solid.TenantAndPackageTestFixtures.loadTenantRoleMenuIds;
import static io.github.atengk.solid.TenantAndPackageTestFixtures.loginAndGetToken;
import static io.github.atengk.solid.TenantAndPackageTestFixtures.postJsonAndExpectCode;
import static io.github.atengk.solid.TenantAndPackageTestFixtures.putJsonAndExpectCode;
import static io.github.atengk.solid.TenantAndPackageTestFixtures.seedTenantWithRole;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 可开关多租户（4 步原子初始化与回滚、租户 0 保护、{@code TenantStatusCache} 驱逐与全员强退）
 * 及租户套餐（{@code platform_only} 拦截、{@code shrink-preview}、半选防误删与跨租户裁剪）集成测试。
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
                "spring.datasource.url=jdbc:h2:mem:tenant_and_package_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        }
)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantAndPackageIntegrationTest {

    private static final long EXISTING_PKG_ID = 34001L;
    private static final long TENANT_ALPHA_ID = 34101L;
    private static final long TENANT_BETA_ID = 34102L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private LoginFailureLockService loginFailureLockService;
    @Autowired
    private PermissionCacheService permissionCacheService;
    @Autowired
    private TenantStatusCache tenantStatusCache;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private SysMenuMapper sysMenuMapper;
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
    private SysOperLogMapper sysOperLogMapper;
    @Autowired
    private SysTenantService sysTenantService;

    @BeforeAll
    void setUpFixtures() {
        loginFailureLockService.clearLocalMemory();

        SysTenantPackage pkg = new SysTenantPackage();
        pkg.setId(EXISTING_PKG_ID);
        pkg.setPackageName("初始标准版套餐");
        pkg.setMenuCheckStrictly(true);
        pkg.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysTenantPackageMapper.insert(pkg);

        for (Long menuId : List.of(1000L, 1100L, 1101L, 1200L, 1201L)) {
            SysTenantPackageMenu pm = new SysTenantPackageMenu();
            pm.setPackageId(EXISTING_PKG_ID);
            pm.setMenuId(menuId);
            sysTenantPackageMenuMapper.insert(pm);
        }

        TenantAndPackageTestFixtures.seedTenantWithRole(
                new TenantSeedSpec(TENANT_ALPHA_ID, "T34101", "阿尔法租户", EXISTING_PKG_ID,
                        34201L, 34301L, 34401L, "alpha_admin", List.of(1000L, 1100L, 1101L, 1200L, 1201L)),
                sysTenantMapper, sysDeptMapper, sysRoleMapper, sysRoleMenuMapper, sysUserMapper, sysUserRoleMapper
        );
        TenantAndPackageTestFixtures.seedTenantWithRole(
                new TenantSeedSpec(TENANT_BETA_ID, "T34102", "贝塔租户", EXISTING_PKG_ID,
                        34202L, 34302L, 34402L, "beta_admin", List.of(1000L, 1100L, 1101L, 1200L, 1201L)),
                sysTenantMapper, sysDeptMapper, sysRoleMapper, sysRoleMenuMapper, sysUserMapper, sysUserRoleMapper
        );
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
    @DisplayName("套餐勾选 platform_only=1 或停用菜单均抛 102006，同名冲突抛 102002，停用套餐从 /options 隐藏且允许删除未绑定套餐")
    void shouldRejectInvalidPackageMenusDuplicateNamesAndHandlePackageLifecycle() throws Exception {
        String superToken = loginAndGetToken(mockMvc, objectMapper, "000000", "superadmin", "Ateng@Test2026");

        // 1. 勾选 platform_only=1（3100 租户管理）抛出 102006
        postJsonAndExpectCode(mockMvc, objectMapper, superToken, "/admin/system/tenant-package",
                Map.of("packageName", "越权平台套餐", "menuIds", List.of(1000L, 1100L, 3100L)),
                SystemResultCode.TENANT_QUOTA_OR_SCOPE_EXCEEDED.getCode());

        // 2. 勾选已停用菜单同样抛出 102006
        TenantContextHolder.runWithoutTenant(() -> sysMenuMapper.update(new SysMenu(),
                Wrappers.<SysMenu>lambdaUpdate().eq(SysMenu::getId, 1500L).set(SysMenu::getStatus, CommonStatusEnum.DISABLED.getCode())));
        try {
            postJsonAndExpectCode(mockMvc, objectMapper, superToken, "/admin/system/tenant-package",
                    Map.of("packageName", "停用菜单套餐", "menuIds", List.of(1000L, 1500L)),
                    SystemResultCode.TENANT_QUOTA_OR_SCOPE_EXCEEDED.getCode());
        } finally {
            TenantContextHolder.runWithoutTenant(() -> sysMenuMapper.update(new SysMenu(),
                    Wrappers.<SysMenu>lambdaUpdate().eq(SysMenu::getId, 1500L).set(SysMenu::getStatus, CommonStatusEnum.NORMAL.getCode())));
        }

        // 3. 重名套餐抛 102002；新建/停用/选项过滤/删除闭环通过
        postJsonAndExpectCode(mockMvc, objectMapper, superToken, "/admin/system/tenant-package",
                Map.of("packageName", "初始标准版套餐"), SystemResultCode.DUPLICATE_RESOURCE.getCode());
        long tempPkgId = createPackageViaHttp(mockMvc, objectMapper, superToken, "临时轻量版", true, List.of(1000L, 1100L, 1101L));
        putJsonAndExpectCode(mockMvc, objectMapper, superToken, "/admin/system/tenant-package/" + tempPkgId + "/status", Map.of("status", 1), 200);

        MvcResult optionsRes = mockMvc.perform(get("/admin/system/tenant-package/options").header("satoken-admin", superToken))
                .andExpect(status().isOk()).andReturn();
        List<String> optionNames = objectMapper.readTree(optionsRes.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").findValuesAsText("packageName");
        assertThat(optionNames).contains("初始标准版套餐").doesNotContain("临时轻量版");

        mockMvc.perform(delete("/admin/system/tenant-package/{ids}", tempPkgId).header("satoken-admin", superToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("shrink-preview 与跨租户裁剪验证（含半选父目录保留、扩展不自动授权、清空 menuIds 与 102005 禁删）")
    void shouldShrinkPreviewPruneCrossTenantPreserveHalfCheckedDirAndBlockDeletingBoundPackage() throws Exception {
        String superToken = loginAndGetToken(mockMvc, objectMapper, "000000", "superadmin", "Ateng@Test2026");

        // 1. 构造半选独立套餐与测试租户（套餐仅存 1100,1101,1200，角色含半选祖先目录 1000）
        long halfPkgId = createPackageViaHttp(mockMvc, objectMapper, superToken, "半选缩权测试套餐", false, List.of(1100L, 1101L, 1200L));
        long halfTenantId = 34188L;
        seedTenantWithRole(
                new TenantSeedSpec(halfTenantId, "HALF34", "半选测试租户", halfPkgId, 34288L, 34388L, 34488L,
                        "half_admin", List.of(1000L, 1100L, 1101L, 1200L)),
                sysTenantMapper, sysDeptMapper, sysRoleMapper, sysRoleMenuMapper, sysUserMapper, sysUserRoleMapper);

        // 2. shrink-preview 精确返回移除标题与受影响租户；PUT 移除 1200 -> 祖先目录 1000 不得误删
        MvcResult previewRes = mockMvc.perform(post("/admin/system/tenant-package/shrink-preview")
                        .header("satoken-admin", superToken).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("packageId", halfPkgId, "newMenuIds", List.of(1100L, 1101L)))))
                .andExpect(status().isOk()).andReturn();
        JsonNode previewData = objectMapper.readTree(previewRes.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        assertThat(previewData.path("affectedTenantCount").asInt()).isEqualTo(1);
        assertThat(previewData.path("removedMenuTitles").toString()).contains("角色管理");

        String cacheKey = PermissionCacheService.buildRolePermsKey("admin", String.valueOf(halfTenantId), "tenant_admin");
        permissionCacheService.set(cacheKey, "[\"cached\"]", 60L);
        putJsonAndExpectCode(mockMvc, objectMapper, superToken, "/admin/system/tenant-package",
                Map.of("id", halfPkgId, "packageName", "半选缩权测试套餐", "menuCheckStrictly", false, "menuIds", List.of(1100L, 1101L)), 200);
        assertThat(permissionCacheService.get(cacheKey)).isNull();
        assertThat(loadTenantRoleMenuIds(sysRoleMenuMapper, halfTenantId)).containsExactlyInAnyOrder(1000L, 1100L, 1101L);

        // 3. 扩展不自动授权；清空 menuIds: [] 清空该绑定租户全部 sys_role_menu
        putJsonAndExpectCode(mockMvc, objectMapper, superToken, "/admin/system/tenant-package",
                Map.of("id", halfPkgId, "packageName", "半选缩权测试套餐", "menuCheckStrictly", false, "menuIds", List.of(1100L, 1101L, 1400L)), 200);
        assertThat(loadTenantRoleMenuIds(sysRoleMenuMapper, halfTenantId)).containsExactlyInAnyOrder(1000L, 1100L, 1101L);
        putJsonAndExpectCode(mockMvc, objectMapper, superToken, "/admin/system/tenant-package",
                Map.of("id", halfPkgId, "packageName", "半选缩权测试套餐", "menuCheckStrictly", false, "menuIds", List.of()), 200);
        assertThat(loadTenantRoleMenuIds(sysRoleMenuMapper, halfTenantId)).isEmpty();

        // 4. 被绑定套餐禁删（102005）；切至普通租户 403
        mockMvc.perform(delete("/admin/system/tenant-package/{ids}", EXISTING_PKG_ID).header("satoken-admin", superToken))
                .andExpect(jsonPath("$.code").value(SystemResultCode.RESOURCE_IN_USE.getCode()));
        mockMvc.perform(get("/admin/system/tenant-package/page").header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ALPHA_ID))).andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("新建租户 4 步初始化校验、密码零审计泄漏与触发器模拟异常整体原子回滚")
    void shouldProvisionAtomicallyHideAdminPasswordInOperateLogAndRollbackOnStep4Failure() throws Exception {
        String superToken = loginAndGetToken(mockMvc, objectMapper, "000000", "superadmin", "Ateng@Test2026");

        // 1. 合法创建租户：验证 4 张表落库、pwd_update_time=null 及日志不存明文密码
        long newTenantId = createTenantViaHttp(
                mockMvc, objectMapper, superToken, "STAR3401", "星辰智造集团", EXISTING_PKG_ID, "star_admin", "StarAdmin@2026");
        TenantContextHolder.runWithTenant(newTenantId, () -> {
            List<SysUser> users = sysUserMapper.selectList(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getTenantId, newTenantId));
            assertThat(users).hasSize(1);
            assertThat(users.get(0).getPwdUpdateTime()).isNull();
            assertThat(PasswordUtil.matches("StarAdmin@2026", users.get(0).getPassword())).isTrue();
            assertThat(sysRoleMapper.selectList(Wrappers.<SysRole>lambdaQuery().eq(SysRole::getTenantId, newTenantId)))
                    .extracting(SysRole::getRoleKey).containsExactly("tenant_admin");
        });
        assertThat(awaitLatestOperateLog(sysOperLogMapper, 0L, "租户管理", OperateTypeEnum.INSERT, "新增租户")
                .getRequestParams()).isNullOrEmpty();

        // 2. 触发 Step 4 插入异常：校验 6 张关联表全量回滚零孤儿
        jdbcTemplate.execute("CREATE TRIGGER trg_fail_user_step4 BEFORE INSERT ON sys_user FOR EACH ROW CALL \""
                + FailOnRollbackTrigger.class.getName() + "\"");
        try {
            postJsonAndExpectCode(mockMvc, objectMapper, superToken, "/admin/system/tenant",
                    Map.of("tenantCode", "ROLL3499", "tenantName", "回滚验证租户", "packageId", EXISTING_PKG_ID,
                            "accountCount", 5, "adminUsername", "rollback_bomb", "adminPassword", "RollBack@2026"),
                    500);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM sys_tenant WHERE tenant_code = 'ROLL3499'", Integer.class)).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM sys_dept WHERE dept_name = '回滚验证租户'", Integer.class)).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM sys_user WHERE username = 'rollback_bomb'", Integer.class)).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(1) FROM sys_role WHERE tenant_id NOT IN (0, ?, ?, ?, ?) AND role_key = 'tenant_admin'",
                    Integer.class, TENANT_ALPHA_ID, TENANT_BETA_ID, newTenantId, 34188L)).isZero();
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_fail_user_step4");
        }
    }

    @Test
    @DisplayName("0号租户102003护栏、停用套餐老租户保持运行、换套餐裁剪、TenantStatusCache真实驱逐及停删强退")
    void shouldProtectTenantZeroKeepDisabledPkgRunningEvictCacheAndKickoutSessions() throws Exception {
        String superToken = loginAndGetToken(mockMvc, objectMapper, "000000", "superadmin", "Ateng@Test2026");
        long fullPkgId = createPackageViaHttp(mockMvc, objectMapper, superToken, "全权切换套餐A", true, List.of(1000L, 1100L, 1101L, 1200L, 1201L));
        long slimPkgId = createPackageViaHttp(mockMvc, objectMapper, superToken, "缩权切换套餐B", true, List.of(1000L, 1100L, 1101L));

        long tId = createTenantViaHttp(mockMvc, objectMapper, superToken, "LIFE3402", "生命周期租户", fullPkgId, "life_admin", "LifeAdmin@2026");
        String lifeToken = loginAndGetToken(mockMvc, objectMapper, "LIFE3402", "life_admin", "LifeAdmin@2026");

        // 1. /tenant/options：超管切至非 0 租户视角依然可拉取，普通租户 403
        mockMvc.perform(get("/admin/system/tenant/options").header("satoken-admin", lifeToken)).andExpect(jsonPath("$.code").value(403));
        mockMvc.perform(get("/admin/system/tenant/options").header("satoken-admin", superToken).header("X-Tenant-Id", String.valueOf(tId)))
                .andExpect(jsonPath("$.code").value(200));

        // 2. 停用 fullPkgId 后老租户依然正常运行（/routes 与编辑保留旧套餐通过），然后换套餐并实证 TenantStatusCache 驱逐
        putJsonAndExpectCode(mockMvc, objectMapper, superToken, "/admin/system/tenant-package/" + fullPkgId + "/status", Map.of("status", 1), 200);
        mockMvc.perform(get("/admin/auth/routes").header("satoken-admin", lifeToken)).andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/admin/system/tenant/{id}", tId).header("satoken-admin", superToken)).andExpect(jsonPath("$.data.packageDisabled").value(true));
        putJsonAndExpectCode(mockMvc, objectMapper, superToken, "/admin/system/tenant",
                Map.of("id", tId, "tenantName", "生命周期租户改名", "packageId", fullPkgId, "accountCount", 1), 200);

        // 3. 0 号租户允许改名称/备注，禁止停用/删除（102003）
        putJsonAndExpectCode(mockMvc, objectMapper, superToken, "/admin/system/tenant", Map.of("id", 0L, "tenantName", "平台默认租户"), 200);
        putJsonAndExpectCode(mockMvc, objectMapper, superToken, "/admin/system/tenant/0/status", Map.of("status", 1), 102003);
        mockMvc.perform(delete("/admin/system/tenant/{ids}", "0").header("satoken-admin", superToken)).andExpect(jsonPath("$.code").value(102003));

        // 4. 换套餐 slimPkgId 实证 TenantStatusCache 驱逐；停用再重登后 DELETE 租户强退全员会话
        AtomicInteger cacheLoads = new AtomicInteger();
        tenantStatusCache.getOrLoad(tId, id -> { cacheLoads.incrementAndGet(); return TenantAuthDTO.builder().tenantId(id).build(); });
        tenantStatusCache.getOrLoad(tId, id -> { cacheLoads.incrementAndGet(); return TenantAuthDTO.builder().tenantId(id).build(); });
        assertThat(cacheLoads.get()).isEqualTo(1);

        putJsonAndExpectCode(mockMvc, objectMapper, superToken, "/admin/system/tenant",
                Map.of("id", tId, "tenantName", "生命周期租户改名", "packageId", slimPkgId, "accountCount", 1), 200);
        tenantStatusCache.getOrLoad(tId, id -> { cacheLoads.incrementAndGet(); return TenantAuthDTO.builder().tenantId(id).build(); });
        assertThat(cacheLoads.get()).isEqualTo(2);

        putJsonAndExpectCode(mockMvc, objectMapper, superToken, "/admin/system/tenant/" + tId + "/status", Map.of("status", 1), 200);
        mockMvc.perform(get("/admin/auth/info").header("satoken-admin", lifeToken)).andExpect(jsonPath("$.code").value(401));
        putJsonAndExpectCode(mockMvc, objectMapper, superToken, "/admin/system/tenant/" + tId + "/status", Map.of("status", 0), 200);
        String reloginToken = loginAndGetToken(mockMvc, objectMapper, "LIFE3402", "life_admin", "LifeAdmin@2026");
        mockMvc.perform(delete("/admin/system/tenant/{ids}", String.valueOf(tId)).header("satoken-admin", superToken))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/admin/auth/info").header("satoken-admin", reloginToken)).andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("并发以同一 tenantCode 新增租户时仅 1 次成功落库，其余统一收敛为 102002 且零孤儿脏数据")
    void shouldHandleConcurrentDuplicateTenantCodeVia102002WithoutOrphans() throws Exception {
        int threads = 4;
        String raceCode = "CONC3401";
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger duplicateCount = new AtomicInteger();
        List<Long> createdTenantIds = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        try {
            List<Future<?>> futures = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    try {
                        TenantCreateRequest req = new TenantCreateRequest();
                        req.setTenantCode(raceCode);
                        req.setTenantName("并发同码测试租户");
                        req.setPackageId(EXISTING_PKG_ID);
                        req.setAccountCount(5);
                        req.setAdminUsername("conc_admin");
                        req.setAdminPassword("Ateng@Conc2026");
                        CreateTenantVO created = TenantContextHolder.runWithTenant(
                                SolidConstants.PLATFORM_TENANT_ID,
                                () -> sysTenantService.createTenant(req)
                        );
                        createdTenantIds.add(created.getTenantId());
                        successCount.incrementAndGet();
                    } catch (BusinessException ex) {
                        if (SystemResultCode.DUPLICATE_RESOURCE.getCode().equals(ex.getCode())) {
                            duplicateCount.incrementAndGet();
                        } else {
                            unexpectedErrors.add(ex);
                        }
                    } catch (Throwable ex) {
                        unexpectedErrors.add(ex);
                    } finally {
                        TenantContextHolder.clear();
                    }
                    return null;
                }));
            }
            startGate.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }

            assertThat(unexpectedErrors).isEmpty();
            assertThat(successCount.get()).isEqualTo(1);
            assertThat(duplicateCount.get()).isEqualTo(threads - 1);
            assertThat(createdTenantIds).hasSize(1);
            Long winnerId = createdTenantIds.get(0);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM sys_tenant WHERE tenant_code = ?", Integer.class, raceCode)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM sys_user WHERE tenant_id = ?", Integer.class, winnerId)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            for (Long createdId : createdTenantIds) {
                jdbcTemplate.update("DELETE FROM sys_user_role WHERE tenant_id = ?", createdId);
                jdbcTemplate.update("DELETE FROM sys_user WHERE tenant_id = ?", createdId);
                jdbcTemplate.update("DELETE FROM sys_role_menu WHERE tenant_id = ?", createdId);
                jdbcTemplate.update("DELETE FROM sys_role WHERE tenant_id = ?", createdId);
                jdbcTemplate.update("DELETE FROM sys_dept WHERE tenant_id = ?", createdId);
                jdbcTemplate.update("DELETE FROM sys_tenant WHERE id = ?", createdId);
            }
        }
    }

    @Test
    @DisplayName("已删除租户遗留角色权限不阻塞平台删除菜单且顺带清理 sys_role_menu，活跃租户仍拦截 102005")
    void shouldAllowDeletingMenuReferencedOnlyByDeletedTenantWhileBlockingActiveTenantRef() throws Exception {
        String superToken = loginAndGetToken(mockMvc, objectMapper, "000000", "superadmin", "Ateng@Test2026");

        Long menuId = null;
        Long platformRoleId = null;
        Long pkgId = null;
        Long deletedTenantId = null;

        try {
            // 1. 新增自定义菜单 M
            MvcResult menuRes = mockMvc.perform(post("/admin/system/menu")
                            .header("satoken-admin", superToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.ofEntries(
                                    Map.entry("parentId", 1000L),
                                    Map.entry("menuType", "C"),
                                    Map.entry("title", "已删租户菜单验证"),
                                    Map.entry("name", "SysDeletedTenantMenu"),
                                    Map.entry("path", "/system/deleted-tenant-menu"),
                                    Map.entry("component", "system/deleted-tenant-menu/index"),
                                    Map.entry("sort", 96),
                                    Map.entry("visible", 0),
                                    Map.entry("status", 0),
                                    Map.entry("keepAlive", true),
                                    Map.entry("isExternal", false),
                                    Map.entry("platformOnly", false)
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andReturn();
            menuId = Long.parseLong(
                    objectMapper.readTree(menuRes.getResponse().getContentAsString(StandardCharsets.UTF_8))
                            .path("data").asText()
            );

            // 2. 负向守卫：平台租户 0 下活跃角色引用 M 时，删除菜单继续返回 102005
            MvcResult roleRes = mockMvc.perform(post("/admin/system/role")
                            .header("satoken-admin", superToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "roleName", "平台菜单引用测试角色",
                                    "roleKey", "menu_ref_guard_role",
                                    "sort", 90,
                                    "status", 0,
                                    "menuCheckStrictly", true,
                                    "menuIds", List.of(1000L, menuId)
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andReturn();
            platformRoleId = Long.parseLong(
                    objectMapper.readTree(roleRes.getResponse().getContentAsString(StandardCharsets.UTF_8))
                            .path("data").asText()
            );

            mockMvc.perform(delete("/admin/system/menu/{id}", menuId).header("satoken-admin", superToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(SystemResultCode.RESOURCE_IN_USE.getCode()));

            mockMvc.perform(delete("/admin/system/role/{ids}", platformRoleId).header("satoken-admin", superToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // 3. 建套餐 P -> 建租户 T（自动创建 tenant_admin 并写入 sys_role_menu）-> 删租户 T -> 删套餐 P
            pkgId = createPackageViaHttp(
                    mockMvc, objectMapper, superToken, "删租户菜单清理测试套餐", true, List.of(1000L, menuId));
            deletedTenantId = createTenantViaHttp(
                    mockMvc, objectMapper, superToken, "DEL3499", "删租户菜单验证", pkgId, "del_menu_admin", "DelMenu@2026");

            final Long activeMenuId = menuId;
            final Long targetTenantId = deletedTenantId;
            assertThat(TenantContextHolder.runWithoutTenant(
                    () -> sysRoleMenuMapper.countActiveRoleReferencesByMenuId(activeMenuId)
            )).isEqualTo(1L);

            mockMvc.perform(delete("/admin/system/tenant/{ids}", targetTenantId).header("satoken-admin", superToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // 按逻辑隔离归档边界，被删租户名下 sys_role 仍维持 deleted = 0 且 sys_role_menu 仍暂存引用
            Long archivedRoleCount = TenantContextHolder.runWithTenant(targetTenantId, () -> sysRoleMapper.selectCount(
                    Wrappers.<SysRole>lambdaQuery().eq(SysRole::getTenantId, targetTenantId)
            ));
            assertThat(archivedRoleCount).isEqualTo(1L);
            assertThat(loadTenantRoleMenuIds(sysRoleMenuMapper, targetTenantId)).contains(activeMenuId);

            mockMvc.perform(delete("/admin/system/tenant-package/{ids}", pkgId).header("satoken-admin", superToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // 4. 删除菜单 M 应成功解除死锁并清理已删租户下该菜单残留的 sys_role_menu 关联
            mockMvc.perform(delete("/admin/system/menu/{id}", activeMenuId).header("satoken-admin", superToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            assertThat(TenantContextHolder.runWithoutTenant(() -> sysMenuMapper.selectById(activeMenuId))).isNull();
            assertThat(loadTenantRoleMenuIds(sysRoleMenuMapper, targetTenantId)).doesNotContain(activeMenuId);
        } finally {
            if (deletedTenantId != null) {
                jdbcTemplate.update("DELETE FROM sys_user_role WHERE tenant_id = ?", deletedTenantId);
                jdbcTemplate.update("DELETE FROM sys_user WHERE tenant_id = ?", deletedTenantId);
                jdbcTemplate.update("DELETE FROM sys_role_menu WHERE tenant_id = ?", deletedTenantId);
                jdbcTemplate.update("DELETE FROM sys_role WHERE tenant_id = ?", deletedTenantId);
                jdbcTemplate.update("DELETE FROM sys_dept WHERE tenant_id = ?", deletedTenantId);
                jdbcTemplate.update("DELETE FROM sys_tenant WHERE id = ?", deletedTenantId);
            }
            if (pkgId != null) {
                jdbcTemplate.update("DELETE FROM sys_tenant_package_menu WHERE package_id = ?", pkgId);
                jdbcTemplate.update("DELETE FROM sys_tenant_package WHERE id = ?", pkgId);
            }
            if (platformRoleId != null) {
                jdbcTemplate.update("DELETE FROM sys_role_menu WHERE role_id = ?", platformRoleId);
                jdbcTemplate.update("DELETE FROM sys_role WHERE id = ?", platformRoleId);
            }
            if (menuId != null) {
                jdbcTemplate.update("DELETE FROM sys_menu WHERE id = ?", menuId);
            }
        }
    }
}

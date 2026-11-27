package io.github.atengk.solid;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.satoken.defense.LoginFailureLockService;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.system.api.dto.TenantAuthDTO;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysTenantPackage;
import io.github.atengk.solid.system.entity.SysTenantPackageMenu;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysRoleMenuMapper;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMenuMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import io.github.atengk.solid.system.service.SysTenantService;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.atengk.solid.TenantAndPackageTestFixtures.createTenantViaHttp;
import static io.github.atengk.solid.TenantAndPackageTestFixtures.loginAndGetToken;
import static io.github.atengk.solid.TenantAndPackageTestFixtures.putJsonAndExpectCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 租户过期时间缩短强退与跨批次 DB 游标分页全员会话强退（突破 2000 扫描上限）集成测试。
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
                "spring.datasource.url=jdbc:h2:mem:tenant_kickout_expire_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        }
)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantKickoutAndExpireIntegrationTest {

    private static final long PKG_ID = 36001L;
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private LoginFailureLockService loginFailureLockService;
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
    private SysUserMapper sysUserMapper;
    @Autowired
    private SysTenantService sysTenantService;

    @BeforeAll
    void setUpPackage() {
        loginFailureLockService.clearLocalMemory();
        SysTenantPackage pkg = new SysTenantPackage();
        pkg.setId(PKG_ID);
        pkg.setPackageName("强退验证套餐");
        pkg.setMenuCheckStrictly(true);
        pkg.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysTenantPackageMapper.insert(pkg);

        for (Long menuId : List.of(1000L, 1100L, 1101L)) {
            SysTenantPackageMenu pm = new SysTenantPackageMenu();
            pm.setPackageId(PKG_ID);
            pm.setMenuId(menuId);
            sysTenantPackageMenuMapper.insert(pm);
        }
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
    @DisplayName("修改租户 expireTime 为当前或过去时间时应当同步驱逐缓存并强退全部在线会话")
    void shouldEvictCacheAndKickoutSessionsWhenExpireTimeUpdatedToPast() throws Exception {
        String superToken = loginAndGetToken(mockMvc, objectMapper, "000000", "superadmin", "Ateng@Test2026");
        long tenantId = createTenantViaHttp(
                mockMvc, objectMapper, superToken, "EXP3601", "过期强退测试企业", PKG_ID, "exp_admin", "Expire@2026"
        );
        String tenantToken = loginAndGetToken(mockMvc, objectMapper, "EXP3601", "exp_admin", "Expire@2026");

        // 预热缓存
        AtomicInteger loads = new AtomicInteger();
        tenantStatusCache.getOrLoad(tenantId, id -> {
            loads.incrementAndGet();
            return TenantAuthDTO.builder().tenantId(id).status(0).build();
        });
        assertThat(loads.get()).isEqualTo(1);

        // 修改 expireTime 至 5 分钟前
        String pastExpire = LocalDateTime.now().minusMinutes(5).format(DATE_TIME_FMT);
        putJsonAndExpectCode(
                mockMvc, objectMapper, superToken, "/admin/system/tenant",
                Map.of(
                        "id", tenantId,
                        "tenantName", "过期强退测试企业",
                        "packageId", PKG_ID,
                        "accountCount", -1,
                        "expireTime", pastExpire,
                        "status", 0
                ),
                200
        );

        // 缓存已驱逐 + 已登录会话立即被踢下线 401
        tenantStatusCache.getOrLoad(tenantId, id -> {
            loads.incrementAndGet();
            return TenantAuthDTO.builder().tenantId(id).status(0).build();
        });
        assertThat(loads.get()).isEqualTo(2);
        mockMvc.perform(get("/admin/auth/info").header("satoken-admin", tenantToken))
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("租户成员数超过单批 500 上限时停用租户仍应当分批全部强退尾批登录会话")
    void shouldKickoutUsersAcrossMultipleDbBatchesWhenExceedingBatchSize() throws Exception {
        String superToken = loginAndGetToken(mockMvc, objectMapper, "000000", "superadmin", "Ateng@Test2026");
        long tenantId = createTenantViaHttp(
                mockMvc, objectMapper, superToken, "BAT3602", "分批强退企业", PKG_ID, "bat_first", "Batch@2026"
        );
        Long rootDeptId = TenantContextHolder.runWithTenant(tenantId, () -> sysDeptMapper.selectList(null).get(0).getId());
        String passwordHash = PasswordUtil.hash("Batch@2026");

        // 插入 515 名额外用户，确保跨越第 1 批（500）进入第 2 批，且最后一名处于第 2 批尾部
        TenantContextHolder.runWithTenant(tenantId, () -> {
            for (int i = 1; i <= 515; i++) {
                SysUser user = new SysUser();
                user.setTenantId(tenantId);
                user.setDeptId(rootDeptId);
                user.setUsername("bat_user_" + i);
                user.setNickname("批测用户" + i);
                user.setPassword(passwordHash);
                user.setStatus(CommonStatusEnum.NORMAL.getCode());
                sysUserMapper.insert(user);
            }
        });

        String firstToken = loginAndGetToken(mockMvc, objectMapper, "BAT3602", "bat_first", "Batch@2026");
        String tailToken = loginAndGetToken(mockMvc, objectMapper, "BAT3602", "bat_user_515", "Batch@2026");

        TenantContextHolder.runWithTenant(0L, () -> sysTenantService.updateTenantStatus(tenantId, CommonStatusEnum.DISABLED.getCode()));

        mockMvc.perform(get("/admin/auth/info").header("satoken-admin", firstToken))
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(get("/admin/auth/info").header("satoken-admin", tailToken))
                .andExpect(jsonPath("$.code").value(401));
    }
}

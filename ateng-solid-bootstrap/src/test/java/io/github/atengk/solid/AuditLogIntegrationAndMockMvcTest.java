package io.github.atengk.solid;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.satoken.defense.LoginFailureLockService;
import io.github.atengk.solid.system.entity.SysLoginLog;
import io.github.atengk.solid.system.entity.SysOperLog;
import io.github.atengk.solid.system.mapper.SysLoginLogMapper;
import io.github.atengk.solid.system.mapper.SysOperLogMapper;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 操作审计异步落库与只读操作/登录日志端点（{@code /admin/system/log/**}）MockMvc 集成测试。
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
                "spring.datasource.url=jdbc:h2:mem:audit_log_mockmvc_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        }
)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditLogIntegrationAndMockMvcTest {

    private static final long TENANT_ID = 9101L;

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
    private SysLoginLogMapper sysLoginLogMapper;

    @BeforeAll
    void setUpFixtures() {
        loginFailureLockService.clearLocalMemory();
        Spec4TestFixtures.TenantMappers mappers = new Spec4TestFixtures.TenantMappers(
                sysTenantPackageMapper, sysTenantPackageMenuMapper, sysTenantMapper,
                sysRoleMapper, sysRoleMenuMapper, sysUserMapper, sysUserRoleMapper
        );
        Spec4TestFixtures.seedTenantWithUser(mappers, new Spec4TestFixtures.TenantSeed(
                9100L, "审计测试套餐", TENANT_ID, "T9101", "审计测试租户",
                9120L, "只读审计员", "auditor_only", 9130L, "auditor9101", "审计员", "Ateng@9101",
                List.of(4000L, 4100L, 4101L)
        ));
        TenantContextHolder.runWithTenant(TENANT_ID, this::seedTenantLogs);
    }

    private void seedTenantLogs() {
        sysOperLogMapper.insert(operLog(91401L, "trace-oper-1", "岗位管理", OperateTypeEnum.INSERT,
                "新增岗位", "POST", "/admin/system/post", 0, null, LocalDateTime.of(2026, 11, 25, 10, 0)));
        sysOperLogMapper.insert(operLog(91402L, "trace-oper-2", "角色管理", OperateTypeEnum.DELETE,
                "删除角色", "DELETE", "/admin/system/role/1", 1, "禁止删除系统内置角色",
                LocalDateTime.of(2026, 11, 25, 11, 0)));

        SysLoginLog login1 = new SysLoginLog();
        login1.setId(91501L);
        login1.setTenantId(TENANT_ID);
        login1.setTraceId("trace-login-1");
        login1.setUsername("auditor9101");
        login1.setLoginType("admin");
        login1.setClientIp("10.0.0.20");
        login1.setBrowser("Edge 130");
        login1.setOs("Windows 11");
        login1.setStatus(1);
        login1.setMsg("用户名或密码错误");
        login1.setLoginTime(LocalDateTime.of(2026, 11, 25, 9, 30));
        sysLoginLogMapper.insert(login1);

        SysLoginLog login2 = new SysLoginLog();
        login2.setId(91502L);
        login2.setTenantId(TENANT_ID);
        login2.setTraceId("trace-login-2");
        login2.setUsername("auditor9101");
        login2.setLoginType("admin");
        login2.setClientIp("10.0.0.21");
        login2.setBrowser("Chrome 131");
        login2.setOs("macOS");
        login2.setStatus(0);
        login2.setMsg("登录成功");
        login2.setLoginTime(LocalDateTime.of(2026, 11, 25, 10, 30));
        sysLoginLogMapper.insert(login2);
    }

    @AfterEach
    void clearContexts() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        DataPermissionContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("操作审计切面应当异步补全真实 userName 落库并支持跨租户隔离详情检索")
    void shouldPersistAsyncOperateLogAndEnforceTenantIsolationOnDetail() throws Exception {
        String superToken = Spec4TestFixtures.loginAsSuperAdmin(mockMvc, objectMapper);
        String postId = Spec4TestFixtures.postAndExtractId(mockMvc, objectMapper, superToken, "/admin/system/post",
                Map.of("postCode", "AUDIT_P", "postName", "审计验证岗", "sort", 1, "status", 0));

        SysOperLog persistedOper = awaitPlatformPostOperateLog();
        assertThat(persistedOper.getUserName()).isEqualTo("superadmin");
        assertThat(persistedOper.getOperateType()).isEqualTo(OperateTypeEnum.INSERT.getCode());

        mockMvc.perform(get("/admin/system/log/oper/page")
                        .header("satoken-admin", superToken)
                        .param("module", "岗位"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].userName").value("superadmin"));

        mockMvc.perform(get("/admin/system/log/oper/{id}", persistedOper.getId()).header("satoken-admin", superToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.description").value("新增岗位"))
                .andExpect(jsonPath("$.data.requestMethod").value("POST"))
                .andExpect(jsonPath("$.data.requestUrl").value("/admin/system/post"));

        // 校验起止时间逆序筛选被 100001 拦截
        mockMvc.perform(get("/admin/system/log/oper/page")
                        .header("satoken-admin", superToken)
                        .param("beginTime", "2026-11-25T12:00:00")
                        .param("endTime", "2026-11-25T10:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(100001));

        // 跨租户详情访问应当隔离拒绝（平台租户 0 不能读到 TENANT_ID 的 91401L）
        mockMvc.perform(get("/admin/system/log/oper/{id}", 91401L).header("satoken-admin", superToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(102001));

        mockMvc.perform(delete("/admin/system/post/{ids}", postId).header("satoken-admin", superToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("只读操作日志端点应当支持租户内倒序分页、状态筛选并拦截越权与写请求")
    void shouldQueryTenantOperateLogsDescAndEnforceReadOnlyPermissions() throws Exception {
        String auditorToken = Spec4TestFixtures.loginAndGetToken(mockMvc, objectMapper, "T9101", "auditor9101", "Ateng@9101");

        mockMvc.perform(get("/admin/system/log/oper/page").header("satoken-admin", auditorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records[0].id").value("91402"))
                .andExpect(jsonPath("$.data.records[1].id").value("91401"));

        mockMvc.perform(get("/admin/system/log/oper/page")
                        .header("satoken-admin", auditorToken)
                        .param("operateType", "3")
                        .param("status", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].errorMsg").value("禁止删除系统内置角色"));

        mockMvc.perform(get("/admin/system/log/login/page").header("satoken-admin", auditorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(delete("/admin/system/log/oper/{id}", 91401L).header("satoken-admin", auditorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(405));
    }

    @Test
    @DisplayName("超管切租户后应当能够按条件与时间倒序分页检索目标租户登录日志")
    void shouldQueryTenantLoginLogsWhenSuperAdminSwitchesTenant() throws Exception {
        String superToken = Spec4TestFixtures.loginAsSuperAdmin(mockMvc, objectMapper);
        mockMvc.perform(get("/admin/system/log/login/page")
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                        .param("beginTime", "2026-11-25T09:00:00")
                        .param("endTime", "2026-11-25T11:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records[0].id").value("91502"))
                .andExpect(jsonPath("$.data.records[1].id").value("91501"));

        mockMvc.perform(get("/admin/system/log/login/page")
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ID))
                        .param("username", "auditor")
                        .param("status", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].browser").value("Edge 130"))
                .andExpect(jsonPath("$.data.records[0].os").value("Windows 11"));
    }

    private SysOperLog awaitPlatformPostOperateLog() throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(5).toMillis();
        while (System.currentTimeMillis() < deadline) {
            List<SysOperLog> list = TenantContextHolder.runWithTenant(0L, () -> sysOperLogMapper.selectList(
                    Wrappers.<SysOperLog>lambdaQuery()
                            .eq(SysOperLog::getTenantId, 0L)
                            .eq(SysOperLog::getModule, "岗位管理")
                            .eq(SysOperLog::getOperateType, OperateTypeEnum.INSERT.getCode())
            ));
            if (list != null && !list.isEmpty()) {
                return list.get(0);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待异步操作审计落库超时");
    }

    private static SysOperLog operLog(
            Long id,
            String traceId,
            String module,
            OperateTypeEnum operateType,
            String description,
            String method,
            String url,
            int status,
            String errorMsg,
            LocalDateTime operateTime
    ) {
        SysOperLog entity = new SysOperLog();
        entity.setId(id);
        entity.setTenantId(TENANT_ID);
        entity.setTraceId(traceId);
        entity.setUserId(9130L);
        entity.setUserName("auditor9101");
        entity.setModule(module);
        entity.setOperateType(operateType.getCode());
        entity.setDescription(description);
        entity.setRequestMethod(method);
        entity.setRequestUrl(url);
        entity.setClientIp("10.0.0.10");
        entity.setBrowser("Chrome");
        entity.setOs("Windows 10");
        entity.setStatus(status);
        entity.setErrorMsg(errorMsg);
        entity.setCostTime(12L);
        entity.setOperateTime(operateTime);
        return entity;
    }
}

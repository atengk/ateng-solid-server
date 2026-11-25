package io.github.atengk.solid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.satoken.defense.LoginFailureLockService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 静态枚举自主接管端点（{@code /admin/system/enums/**}）MockMvc 安全与契约集成测试。
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
                "spring.datasource.url=jdbc:h2:mem:sys_enum_mockmvc_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        }
)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SysEnumControllerMockMvcTest {

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

    @BeforeAll
    void clearDefense() {
        loginFailureLockService.clearLocalMemory();
        Spec4TestFixtures.TenantMappers mappers = new Spec4TestFixtures.TenantMappers(
                sysTenantPackageMapper, sysTenantPackageMenuMapper, sysTenantMapper,
                sysRoleMapper, sysRoleMenuMapper, sysUserMapper, sysUserRoleMapper
        );
        Spec4TestFixtures.seedTenantWithUser(mappers, new Spec4TestFixtures.TenantSeed(
                9300L, "枚举测试套餐", 9301L, "T9301", "枚举测试租户",
                9320L, "普通枚举消费角色", "enum_reader", 9330L, "enumuser9301", "枚举测试用户", "Ateng@9301",
                List.of()
        ));
    }

    @AfterEach
    void clearContexts() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        DataPermissionContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("未登录访问 /admin/system/enums 应当返回 401，登录后底座 /api/system/enums 已彻底关闭")
    void shouldRequireLoginAndDisableUpstreamEnumEndpoint() throws Exception {
        mockMvc.perform(get("/admin/system/enums"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(get("/admin/system/enums/commonStatus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(get("/api/system/enums"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));

        String token = Spec4TestFixtures.loginAsSuperAdmin(mockMvc, objectMapper);
        mockMvc.perform(get("/api/system/enums").header("satoken-admin", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("登录后应当返回已注册静态枚举全量、单个别名、404 未知别名及 GET/POST 批量结果")
    void shouldReturnRegisteredStaticEnumsAndSupportSingleAndBatchQueries() throws Exception {
        String token = Spec4TestFixtures.loginAsSuperAdmin(mockMvc, objectMapper);

        // 校验全部 7 个首期静态枚举（含上游 ateng-boot#1175 修复后的 operateType）均已自动扫描注册
        MvcResult allRes = mockMvc.perform(get("/admin/system/enums").header("satoken-admin", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode allData = objectMapper.readTree(allRes.getResponse().getContentAsString()).path("data");
        assertThat(allData.size()).isEqualTo(7);
        assertThat(allData.has("commonStatus")).isTrue();
        assertThat(allData.has("menuType")).isTrue();
        assertThat(allData.has("dataScopeType")).isTrue();
        assertThat(allData.has("operateType")).isTrue();
        assertThat(allData.has("logStatus")).isTrue();
        assertThat(allData.has("configType")).isTrue();
        assertThat(allData.has("visibleStatus")).isTrue();

        // 普通租户账号登录态同样可消费静态枚举只读接口
        String tenantToken = Spec4TestFixtures.loginAndGetToken(mockMvc, objectMapper, "T9301", "enumuser9301", "Ateng@9301");
        mockMvc.perform(get("/admin/system/enums/operateType").header("satoken-admin", tenantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(11));

        mockMvc.perform(get("/admin/system/enums/dataScopeType").header("satoken-admin", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[0].code").value(1))
                .andExpect(jsonPath("$.data[0].label").value("全部数据权限"));
        mockMvc.perform(get("/admin/system/enums/operateType").header("satoken-admin", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(11))
                .andExpect(jsonPath("$.data[0].code").value(0))
                .andExpect(jsonPath("$.data[0].label").value("其他"));
        mockMvc.perform(get("/admin/system/enums/nonExistentEnum").header("satoken-admin", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(get("/admin/system/enums/batch")
                        .header("satoken-admin", token)
                        .param("names", "commonStatus", "operateType", "configType", "missingEnum"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commonStatus.length()").value(2))
                .andExpect(jsonPath("$.data.operateType.length()").value(11))
                .andExpect(jsonPath("$.data.configType.length()").value(2))
                .andExpect(jsonPath("$.data.missingEnum").doesNotExist());

        mockMvc.perform(post("/admin/system/enums/batch")
                        .header("satoken-admin", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of("menuType", "visibleStatus", "logStatus", "operateType"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.menuType.length()").value(3))
                .andExpect(jsonPath("$.data.visibleStatus.length()").value(2))
                .andExpect(jsonPath("$.data.logStatus.length()").value(2))
                .andExpect(jsonPath("$.data.operateType.length()").value(11));
    }
}

package io.github.atengk.solid;

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
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 动态数据字典（{@code /admin/system/dict/**}）与系统参数（{@code /admin/system/config/**}）集成与 MockMvc 契约测试。
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
                "spring.datasource.url=jdbc:h2:mem:dict_config_integration_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        }
)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SysDictAndConfigIntegrationTest {

    private static final long TENANT_ID = 9201L;

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
    void setUpFixtures() {
        loginFailureLockService.clearLocalMemory();
        Spec4TestFixtures.TenantMappers mappers = new Spec4TestFixtures.TenantMappers(
                sysTenantPackageMapper, sysTenantPackageMenuMapper, sysTenantMapper,
                sysRoleMapper, sysRoleMenuMapper, sysUserMapper, sysUserRoleMapper
        );
        Spec4TestFixtures.seedTenantWithUser(mappers, new Spec4TestFixtures.TenantSeed(
                9200L, "字典与参数测试套餐", TENANT_ID, "T9201", "参数测试租户",
                9220L, "参数管理员", "config_admin", 9230L, "cfguser9201", "租户参数员", "Ateng@9201",
                List.of(2000L, 2200L, 2201L, 2202L, 2203L, 2204L)
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
    @DisplayName("动态字典管理端应当仅限平台租户操作，全租户支持只读消费及下拉选项与类型详情查询")
    void shouldEnforcePlatformScopeAndExposeTypeOptionsAndDetail() throws Exception {
        String superToken = Spec4TestFixtures.loginAsSuperAdmin(mockMvc, objectMapper);
        String tenantToken = Spec4TestFixtures.loginAndGetToken(mockMvc, objectMapper, "T9201", "cfguser9201", "Ateng@9201");

        mockMvc.perform(get("/admin/system/dict/type/page").header("satoken-admin", tenantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(get("/admin/system/dict/data/type/sys_user_gender").header("satoken-admin", tenantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));

        mockMvc.perform(get("/admin/system/dict/type/options").header("satoken-admin", superToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].dictType").isNotEmpty());

        MvcResult pageRes = mockMvc.perform(get("/admin/system/dict/type/page")
                        .header("satoken-admin", superToken)
                        .param("dictType", "sys_user_gender"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andReturn();
        String genderTypeId = objectMapper.readTree(pageRes.getResponse().getContentAsString())
                .path("data").path("records").path(0).path("id").asText();

        mockMvc.perform(get("/admin/system/dict/type/{id}", genderTypeId).header("satoken-admin", superToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dictType").value("sys_user_gender"));
    }

    @Test
    @DisplayName("修改字典类型应锁定编码，新增与编辑明细应保障默认值互斥并支持明细分页检索")
    void shouldLockDictTypeMaintainDefaultMutualExclusionAndUpdateData() throws Exception {
        String superToken = Spec4TestFixtures.loginAsSuperAdmin(mockMvc, objectMapper);
        String dictType = "sys_ticket_priority_flow";
        String typeId = Spec4TestFixtures.postAndExtractId(mockMvc, objectMapper, superToken, "/admin/system/dict/type",
                Map.of("dictName", "工单优先级", "dictType", dictType, "status", 0, "remark", "测试优先级"));

        mockMvc.perform(put("/admin/system/dict/type")
                        .header("satoken-admin", superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "id", Long.parseLong(typeId), "dictName", "改名", "dictType", dictType + "_x", "status", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(100001));

        String data1Id = Spec4TestFixtures.postAndExtractId(mockMvc, objectMapper, superToken, "/admin/system/dict/data",
                Spec4TestFixtures.dictDataBody(dictType, "普通", "normal", "info", 1, 1));
        String data2Id = Spec4TestFixtures.postAndExtractId(mockMvc, objectMapper, superToken, "/admin/system/dict/data",
                Spec4TestFixtures.dictDataBody(dictType, "紧急", "urgent", "danger", 2, 1));

        mockMvc.perform(get("/admin/system/dict/data/{id}", data1Id).header("satoken-admin", superToken))
                .andExpect(jsonPath("$.data.isDefault").value(0));
        mockMvc.perform(get("/admin/system/dict/data/{id}", data2Id).header("satoken-admin", superToken))
                .andExpect(jsonPath("$.data.isDefault").value(1));

        mockMvc.perform(get("/admin/system/dict/data/page").header("satoken-admin", superToken).param("dictType", dictType))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));

        mockMvc.perform(put("/admin/system/dict/data")
                        .header("satoken-admin", superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "id", Long.parseLong(data1Id), "dictType", dictType,
                                "dictLabel", "普通-更新", "dictValue", "normal", "sort", 1, "isDefault", 0, "status", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/admin/system/dict/data/{id}", data1Id).header("satoken-admin", superToken))
                .andExpect(jsonPath("$.data.dictLabel").value("普通-更新"))
                .andExpect(jsonPath("$.data.tagType").value("info"));

        // 通过 PUT /data 将另一条明细切换为 isDefault=1 时同样应互斥清零其余默认值
        mockMvc.perform(put("/admin/system/dict/data")
                        .header("satoken-admin", superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "id", Long.parseLong(data1Id), "dictType", dictType,
                                "dictLabel", "普通-更新", "dictValue", "normal", "sort", 1, "isDefault", 1, "status", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/admin/system/dict/data/{id}", data2Id).header("satoken-admin", superToken))
                .andExpect(jsonPath("$.data.isDefault").value(0));
    }

    @Test
    @DisplayName("停用字典类型与数据应当触发缓存精确失效并拦截含子项删除（102005）")
    void shouldEvictDictCacheOnDisableAndGuardTypeDeleteWithChildren() throws Exception {
        String superToken = Spec4TestFixtures.loginAsSuperAdmin(mockMvc, objectMapper);
        String tenantToken = Spec4TestFixtures.loginAndGetToken(mockMvc, objectMapper, "T9201", "cfguser9201", "Ateng@9201");
        String dictType = "sys_ticket_lifecycle";

        String typeId = Spec4TestFixtures.postAndExtractId(mockMvc, objectMapper, superToken, "/admin/system/dict/type",
                Map.of("dictName", "生命周期字典", "dictType", dictType, "status", 0));
        String d1Id = Spec4TestFixtures.postAndExtractId(mockMvc, objectMapper, superToken, "/admin/system/dict/data",
                Spec4TestFixtures.dictDataBody(dictType, "待处理", "pending", "info", 1, 0));
        String d2Id = Spec4TestFixtures.postAndExtractId(mockMvc, objectMapper, superToken, "/admin/system/dict/data",
                Spec4TestFixtures.dictDataBody(dictType, "已完成", "done", "success", 2, 0));

        mockMvc.perform(get("/admin/system/dict/data/type/" + dictType).header("satoken-admin", tenantToken))
                .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(put("/admin/system/dict/data/{id}/status", d2Id).header("satoken-admin", superToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":1}"))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/admin/system/dict/data/type/" + dictType).header("satoken-admin", tenantToken))
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(put("/admin/system/dict/type/{id}/status", typeId).header("satoken-admin", superToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":1}"))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/admin/system/dict/data/type/" + dictType).header("satoken-admin", tenantToken))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(delete("/admin/system/dict/type/{ids}", typeId).header("satoken-admin", superToken))
                .andExpect(jsonPath("$.code").value(102005));
        mockMvc.perform(delete("/admin/system/dict/data/{ids}", d1Id + "," + d2Id).header("satoken-admin", superToken))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(delete("/admin/system/dict/type/{ids}", typeId).header("satoken-admin", superToken))
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("系统参数应当保护内置参数（102007）、禁止新建 Y、并按原始未裁剪密码校验 initPassword")
    void shouldGuardBuiltinConfigRejectCreateYAndValidateRawInitPassword() throws Exception {
        String superToken = Spec4TestFixtures.loginAsSuperAdmin(mockMvc, objectMapper);

        mockMvc.perform(delete("/admin/system/config/{ids}", "20001").header("satoken-admin", superToken))
                .andExpect(jsonPath("$.code").value(102007));

        mockMvc.perform(put("/admin/system/config").header("satoken-admin", superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Spec4TestFixtures.configUpdateBody(
                                20001L, "初始密码", "sys.user.hacked", "Ateng@2026", "Y"))))
                .andExpect(jsonPath("$.code").value(102007));

        mockMvc.perform(put("/admin/system/config").header("satoken-admin", superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Spec4TestFixtures.configUpdateBody(
                                20001L, "初始密码", "sys.user.initPassword", "Ateng@2026", "N"))))
                .andExpect(jsonPath("$.code").value(102007));

        mockMvc.perform(post("/admin/system/config").header("satoken-admin", superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Spec4TestFixtures.configCreateBody(
                                "伪造内置", "custom.builtin", "val", "Y"))))
                .andExpect(jsonPath("$.code").value(100001));

        // 弱口令与首尾带空白字符的密码均必须在裁剪前被 PasswordPolicyValidator 拦截
        mockMvc.perform(put("/admin/system/config").header("satoken-admin", superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Spec4TestFixtures.configUpdateBody(
                                20001L, "初始密码", "sys.user.initPassword", "123456", "Y"))))
                .andExpect(jsonPath("$.code").value(100001));

        mockMvc.perform(put("/admin/system/config").header("satoken-admin", superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Spec4TestFixtures.configUpdateBody(
                                20001L, "初始密码", "sys.user.initPassword", " Ateng@2026 ", "Y"))))
                .andExpect(jsonPath("$.code").value(100001));
    }

    @Test
    @DisplayName("非 0 租户未覆盖时应当自动兜底平台参数，自建覆盖及删除恢复互不串租且支持分页与详情查询")
    void shouldSupportMultiTenantFallbackOverridePageDetailAndDeleteRestore() throws Exception {
        String superToken = Spec4TestFixtures.loginAsSuperAdmin(mockMvc, objectMapper);
        String tenantToken = Spec4TestFixtures.loginAndGetToken(mockMvc, objectMapper, "T9201", "cfguser9201", "Ateng@9201");

        mockMvc.perform(get("/admin/system/config/page").header("satoken-admin", superToken)
                        .param("configKey", "sys.user.initPassword"))
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/admin/system/config/{id}", 20001L).header("satoken-admin", superToken))
                .andExpect(jsonPath("$.data.configKey").value("sys.user.initPassword"));

        mockMvc.perform(put("/admin/system/config").header("satoken-admin", superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Spec4TestFixtures.configUpdateBody(
                                20001L, "用户管理-初始默认密码", "sys.user.initPassword", "Platform@2026", "Y"))))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/admin/system/config/key/sys.user.initPassword").header("satoken-admin", tenantToken))
                .andExpect(jsonPath("$.data").value("Platform@2026"));

        String tenantCfgId = Spec4TestFixtures.postAndExtractId(mockMvc, objectMapper, tenantToken, "/admin/system/config",
                Spec4TestFixtures.configCreateBody("租户初始密码", "sys.user.initPassword", "Tenant9201@Pwd", "N"));
        mockMvc.perform(get("/admin/system/config/{id}", tenantCfgId).header("satoken-admin", tenantToken))
                .andExpect(jsonPath("$.data.configValue").value("Tenant9201@Pwd"));
        mockMvc.perform(get("/admin/system/config/key/sys.user.initPassword").header("satoken-admin", tenantToken))
                .andExpect(jsonPath("$.data").value("Tenant9201@Pwd"));
        mockMvc.perform(get("/admin/system/config/key/sys.user.initPassword").header("satoken-admin", superToken))
                .andExpect(jsonPath("$.data").value("Platform@2026"));

        mockMvc.perform(post("/admin/system/config").header("satoken-admin", tenantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Spec4TestFixtures.configCreateBody(
                                "重复租户参数", "sys.user.initPassword", "Tenant9201@Pwd2", "N"))))
                .andExpect(jsonPath("$.code").value(102002));
        // 禁止将已存在的业务自建参数 N 在更新时升格为系统内置 Y
        mockMvc.perform(put("/admin/system/config").header("satoken-admin", tenantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Spec4TestFixtures.configUpdateBody(
                                Long.parseLong(tenantCfgId), "尝试提权内置", "sys.user.initPassword", "Tenant9201@Pwd", "Y"))))
                .andExpect(jsonPath("$.code").value(100001));

        mockMvc.perform(delete("/admin/system/config/{ids}", tenantCfgId).header("satoken-admin", tenantToken))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(get("/admin/system/config/key/sys.user.initPassword").header("satoken-admin", tenantToken))
                .andExpect(jsonPath("$.data").value("Platform@2026"));

        mockMvc.perform(put("/admin/system/config").header("satoken-admin", superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Spec4TestFixtures.configUpdateBody(
                                20001L, "用户管理-初始默认密码", "sys.user.initPassword", "Ateng@2026", "Y"))))
                .andExpect(jsonPath("$.code").value(200));
    }
}

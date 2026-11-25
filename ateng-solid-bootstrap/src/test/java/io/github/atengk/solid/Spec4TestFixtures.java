package io.github.atengk.solid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysRoleMenu;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysTenantPackage;
import io.github.atengk.solid.system.entity.SysTenantPackageMenu;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.entity.SysUserRole;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysRoleMenuMapper;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMenuMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec 4（枚举、字典、参数、审计日志）MockMvc 集成测试共享夹具与请求体构造工具。
 *
 * @author Ateng
 * @since 2026-11-25
 */
final class Spec4TestFixtures {

    private Spec4TestFixtures() {
    }

    /**
     * 租户与用户夹具持久化 Mapper 聚合载体。
     */
    record TenantMappers(
            SysTenantPackageMapper packageMapper,
            SysTenantPackageMenuMapper packageMenuMapper,
            SysTenantMapper tenantMapper,
            SysRoleMapper roleMapper,
            SysRoleMenuMapper roleMenuMapper,
            SysUserMapper userMapper,
            SysUserRoleMapper userRoleMapper
    ) {
    }

    /**
     * 租户测试种子描述。
     */
    record TenantSeed(
            long packageId,
            String packageName,
            long tenantId,
            String tenantCode,
            String tenantName,
            long roleId,
            String roleName,
            String roleKey,
            long userId,
            String username,
            String nickname,
            String rawPassword,
            List<Long> menuIds
    ) {
    }

    static void seedTenantWithUser(TenantMappers mappers, TenantSeed seed) {
        SysTenantPackage pkg = new SysTenantPackage();
        pkg.setId(seed.packageId());
        pkg.setPackageName(seed.packageName());
        pkg.setMenuCheckStrictly(true);
        pkg.setStatus(CommonStatusEnum.NORMAL.getCode());
        mappers.packageMapper().insert(pkg);

        for (Long menuId : seed.menuIds()) {
            SysTenantPackageMenu pm = new SysTenantPackageMenu();
            pm.setPackageId(seed.packageId());
            pm.setMenuId(menuId);
            mappers.packageMenuMapper().insert(pm);
        }

        SysTenant tenant = new SysTenant();
        tenant.setId(seed.tenantId());
        tenant.setTenantCode(seed.tenantCode());
        tenant.setTenantName(seed.tenantName());
        tenant.setPackageId(seed.packageId());
        tenant.setAccountCount(-1);
        tenant.setStatus(CommonStatusEnum.NORMAL.getCode());
        mappers.tenantMapper().insert(tenant);

        TenantContextHolder.runWithTenant(seed.tenantId(), () -> insertRoleAndUser(mappers, seed));
    }

    private static void insertRoleAndUser(TenantMappers mappers, TenantSeed seed) {
        SysRole role = new SysRole();
        role.setId(seed.roleId());
        role.setTenantId(seed.tenantId());
        role.setRoleName(seed.roleName());
        role.setRoleKey(seed.roleKey());
        role.setDataScope(1);
        role.setMenuCheckStrictly(true);
        role.setDeptCheckStrictly(false);
        role.setSort(1);
        role.setStatus(CommonStatusEnum.NORMAL.getCode());
        mappers.roleMapper().insert(role);

        for (Long menuId : seed.menuIds()) {
            SysRoleMenu rm = new SysRoleMenu();
            rm.setTenantId(seed.tenantId());
            rm.setRoleId(seed.roleId());
            rm.setMenuId(menuId);
            mappers.roleMenuMapper().insert(rm);
        }

        SysUser user = new SysUser();
        user.setId(seed.userId());
        user.setTenantId(seed.tenantId());
        user.setUsername(seed.username());
        user.setNickname(seed.nickname());
        user.setPassword(PasswordUtil.hash(seed.rawPassword()));
        user.setStatus(CommonStatusEnum.NORMAL.getCode());
        mappers.userMapper().insert(user);

        SysUserRole ur = new SysUserRole();
        ur.setTenantId(seed.tenantId());
        ur.setUserId(seed.userId());
        ur.setRoleId(seed.roleId());
        mappers.userRoleMapper().insert(ur);
    }

    static String loginAsSuperAdmin(MockMvc mockMvc, ObjectMapper objectMapper) throws Exception {
        return loginAndGetToken(mockMvc, objectMapper, "000000", "superadmin", "Ateng@Test2026");
    }

    static String loginAndGetToken(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            String tenantCode,
            String username,
            String password
    ) throws Exception {
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

    static String postAndExtractId(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            String token,
            String url,
            Map<String, Object> body
    ) throws Exception {
        MvcResult res = mockMvc.perform(post(url)
                        .header("satoken-admin", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).path("data").asText();
    }

    static Map<String, Object> dictDataBody(
            String dictType,
            String label,
            String value,
            String tagType,
            int sort,
            int isDefault
    ) {
        return Map.of(
                "dictType", dictType,
                "dictLabel", label,
                "dictValue", value,
                "tagType", tagType,
                "sort", sort,
                "isDefault", isDefault,
                "status", 0
        );
    }

    static Map<String, Object> configUpdateBody(Long id, String name, String key, String value, String type) {
        return Map.of(
                "id", id,
                "configName", name,
                "configKey", key,
                "configValue", value,
                "configType", type
        );
    }

    static Map<String, Object> configCreateBody(String name, String key, String value, String type) {
        return Map.of(
                "configName", name,
                "configKey", key,
                "configValue", value,
                "configType", type
        );
    }
}

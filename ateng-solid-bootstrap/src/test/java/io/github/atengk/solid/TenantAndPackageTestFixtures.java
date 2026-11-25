package io.github.atengk.solid;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysOperLog;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysRoleMenu;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.entity.SysUserRole;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysOperLogMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysRoleMenuMapper;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import org.h2.api.Trigger;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 多租户与租户套餐集成测试数据夹具与 H2 回滚触发器支撑（消除长参数列表及冗长重复构造代码）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
final class TenantAndPackageTestFixtures {

    private TenantAndPackageTestFixtures() {
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
        return objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("tokenValue").asText();
    }

    static long createPackageViaHttp(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            String token,
            String packageName,
            boolean menuCheckStrictly,
            List<Long> menuIds
    ) throws Exception {
        MvcResult res = mockMvc.perform(post("/admin/system/tenant-package")
                        .header("satoken-admin", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "packageName", packageName,
                                "menuCheckStrictly", menuCheckStrictly,
                                "menuIds", menuIds
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return Long.parseLong(
                objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .path("data").asText()
        );
    }

    static long createTenantViaHttp(
            MockMvc mockMvc, ObjectMapper objectMapper, String token,
            String code, String name, long pkgId, String username, String password) throws Exception {
        MvcResult res = mockMvc.perform(post("/admin/system/tenant")
                        .header("satoken-admin", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", code, "tenantName", name, "packageId", pkgId,
                                "accountCount", 5, "adminUsername", username, "adminPassword", password
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return Long.parseLong(
                objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .path("data").path("tenantId").asText()
        );
    }

    static void putJsonAndExpectCode(
            MockMvc mockMvc, ObjectMapper objectMapper, String token,
            String url, Object body, int expectedCode) throws Exception {
        mockMvc.perform(put(url)
                        .header("satoken-admin", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(expectedCode));
    }

    static void postJsonAndExpectCode(
            MockMvc mockMvc, ObjectMapper objectMapper, String token,
            String url, Object body, int expectedCode) throws Exception {
        mockMvc.perform(post(url)
                        .header("satoken-admin", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(expectedCode));
    }

    static List<Long> loadTenantRoleMenuIds(SysRoleMenuMapper roleMenuMapper, long tenantId) {
        return TenantContextHolder.runWithoutTenant(() -> roleMenuMapper
                .selectList(Wrappers.<SysRoleMenu>lambdaQuery().eq(SysRoleMenu::getTenantId, tenantId))
                .stream().map(SysRoleMenu::getMenuId).toList());
    }

    static void seedTenantWithRole(
            TenantSeedSpec spec,
            SysTenantMapper tenantMapper,
            SysDeptMapper deptMapper,
            SysRoleMapper roleMapper,
            SysRoleMenuMapper roleMenuMapper,
            SysUserMapper userMapper,
            SysUserRoleMapper userRoleMapper
    ) {
        SysTenant tenant = new SysTenant();
        tenant.setId(spec.tenantId());
        tenant.setTenantCode(spec.tenantCode());
        tenant.setTenantName(spec.tenantName());
        tenant.setPackageId(spec.packageId());
        tenant.setAccountCount(-1);
        tenant.setStatus(CommonStatusEnum.NORMAL.getCode());
        tenantMapper.insert(tenant);

        TenantContextHolder.runWithTenant(spec.tenantId(), () -> {
            SysDept dept = new SysDept();
            dept.setId(spec.deptId());
            dept.setTenantId(spec.tenantId());
            dept.setParentId(0L);
            dept.setAncestors("0");
            dept.setDeptName(spec.tenantName());
            dept.setSort(1);
            dept.setStatus(CommonStatusEnum.NORMAL.getCode());
            deptMapper.insert(dept);

            SysRole role = new SysRole();
            role.setId(spec.roleId());
            role.setTenantId(spec.tenantId());
            role.setRoleName("租户管理员");
            role.setRoleKey("tenant_admin");
            role.setDataScope(1);
            role.setMenuCheckStrictly(true);
            role.setDeptCheckStrictly(false);
            role.setSort(1);
            role.setStatus(CommonStatusEnum.NORMAL.getCode());
            roleMapper.insert(role);

            for (Long menuId : spec.menuIds()) {
                SysRoleMenu rm = new SysRoleMenu();
                rm.setTenantId(spec.tenantId());
                rm.setRoleId(spec.roleId());
                rm.setMenuId(menuId);
                roleMenuMapper.insert(rm);
            }

            SysUser user = new SysUser();
            user.setId(spec.userId());
            user.setTenantId(spec.tenantId());
            user.setDeptId(spec.deptId());
            user.setUsername(spec.adminUsername());
            user.setNickname(spec.tenantName() + "管理员");
            user.setPassword(PasswordUtil.hash("Ateng@34001"));
            user.setGender("2");
            user.setStatus(CommonStatusEnum.NORMAL.getCode());
            userMapper.insert(user);

            SysUserRole ur = new SysUserRole();
            ur.setTenantId(spec.tenantId());
            ur.setUserId(spec.userId());
            ur.setRoleId(spec.roleId());
            userRoleMapper.insert(ur);
        });
    }

    static SysOperLog awaitLatestOperateLog(
            SysOperLogMapper sysOperLogMapper,
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
                            .orderByDesc(SysOperLog::getId)
            ));
            if (logs != null && !logs.isEmpty()) {
                return logs.get(0);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待异步操作日志超时: " + description);
    }

    record TenantSeedSpec(
            Long tenantId,
            String tenantCode,
            String tenantName,
            Long packageId,
            Long deptId,
            Long roleId,
            Long userId,
            String adminUsername,
            List<Long> menuIds
    ) {
    }

    /**
     * H2 行级触发器桩，在插入特定触发账号时抛出异常以验证 4 步初始化事务全量回滚。
     */
    public static class FailOnRollbackTrigger implements Trigger {

        @Override
        public void init(Connection conn, String schemaName, String triggerName, String tableName, boolean before, int type) {
        }

        @Override
        public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
            if (newRow != null && newRow.length > 3 && "rollback_bomb".equals(newRow[3])) {
                throw new SQLException("Simulated failure at step 4");
            }
        }

        @Override
        public void close() {
        }

        @Override
        public void remove() {
        }
    }
}

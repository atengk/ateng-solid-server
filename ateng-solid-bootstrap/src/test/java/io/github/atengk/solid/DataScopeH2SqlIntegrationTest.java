package io.github.atengk.solid;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.datascope.DataScopeVerifyMapper;
import io.github.atengk.solid.system.api.enums.DataScopeType;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysRoleDept;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.entity.SysUserRole;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysRoleDeptMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import io.github.atengk.solid.system.properties.SolidSystemProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 基于真实 H2 与 {@code AtengDataPermissionInnerInterceptor} 的 5 级 {@code @DataScope} 端到端 SQL 执行集成测试。
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
                "spring.datasource.url=jdbc:h2:mem:datascope_h2_sql_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        }
)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataScopeH2SqlIntegrationTest {

    private static final long TENANT_ID = 7001L;
    private static final long OTHER_TENANT_ID = 7002L;
    private static final long DEPT_ROOT = 7100L;
    private static final long DEPT_CHILD = 7101L;
    private static final long DEPT_GRANDCHILD = 7102L;
    private static final long DEPT_SIBLING = 7103L;

    private static final long USER_ROOT = 7201L;
    private static final long USER_CHILD = 7202L;
    private static final long USER_GRANDCHILD = 7203L;
    private static final long USER_SIBLING = 7204L;
    private static final long USER_DEPTLESS = 7205L;
    private static final long USER_DELETED = 7206L;

    @Autowired
    private DataScopeVerifyMapper dataScopeVerifyMapper;
    @Autowired
    private SolidSystemProperties solidSystemProperties;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private SysTenantMapper sysTenantMapper;
    @Autowired
    private SysDeptMapper sysDeptMapper;
    @Autowired
    private SysRoleMapper sysRoleMapper;
    @Autowired
    private SysRoleDeptMapper sysRoleDeptMapper;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @BeforeAll
    void setUpFixtures() {
        insertTenant(TENANT_ID, "T7001", "数据权限H2实测租户");
        insertTenant(OTHER_TENANT_ID, "T7002", "他租户隔离对照");

        TenantContextHolder.runWithTenant(OTHER_TENANT_ID, () -> {
            insertDept(OTHER_TENANT_ID, 7900L, 0L, "0", "他租户总部");
            insertUser(OTHER_TENANT_ID, 7901L, 7900L, "other_tenant_user", "7901");
        });

        TenantContextHolder.runWithTenant(TENANT_ID, () -> {
            insertDept(TENANT_ID, DEPT_ROOT, 0L, "0", "集团总部");
            insertDept(TENANT_ID, DEPT_CHILD, DEPT_ROOT, "0," + DEPT_ROOT, "研发中心");
            insertDept(TENANT_ID, DEPT_GRANDCHILD, DEPT_CHILD, "0," + DEPT_ROOT + "," + DEPT_CHILD, "平台架构组");
            insertDept(TENANT_ID, DEPT_SIBLING, DEPT_ROOT, "0," + DEPT_ROOT, "市场部");

            insertUser(TENANT_ID, USER_ROOT, DEPT_ROOT, "ds_root", String.valueOf(USER_ROOT));
            insertUser(TENANT_ID, USER_CHILD, DEPT_CHILD, "ds_child", String.valueOf(USER_CHILD));
            insertUser(TENANT_ID, USER_GRANDCHILD, DEPT_GRANDCHILD, "ds_grand", String.valueOf(USER_GRANDCHILD));
            insertUser(TENANT_ID, USER_SIBLING, DEPT_SIBLING, "ds_sibling", String.valueOf(USER_SIBLING));
            insertUser(TENANT_ID, USER_DEPTLESS, null, "ds_deptless", String.valueOf(USER_DEPTLESS));
            insertUser(TENANT_ID, USER_DELETED, DEPT_CHILD, "ds_deleted", String.valueOf(USER_DELETED));
            sysUserMapper.deleteById(USER_DELETED);

            insertRole(7301L, "r_all", DataScopeType.ALL, CommonStatusEnum.NORMAL);
            insertRole(7302L, "r_custom", DataScopeType.CUSTOM, CommonStatusEnum.NORMAL);
            insertRole(7303L, "r_dept", DataScopeType.DEPT, CommonStatusEnum.NORMAL);
            insertRole(7304L, "r_dept_child", DataScopeType.DEPT_AND_CHILD, CommonStatusEnum.NORMAL);
            insertRole(7305L, "r_self", DataScopeType.SELF, CommonStatusEnum.NORMAL);
            insertRole(7306L, "r_custom_empty", DataScopeType.CUSTOM, CommonStatusEnum.NORMAL);
            insertRole(7307L, "r_disabled", DataScopeType.ALL, CommonStatusEnum.DISABLED);

            SysRoleDept rd = new SysRoleDept();
            rd.setTenantId(TENANT_ID);
            rd.setRoleId(7302L);
            rd.setDeptId(DEPT_SIBLING);
            sysRoleDeptMapper.insert(rd);
        });
    }

    @AfterEach
    void clearContexts() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        DataPermissionContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("H2 真实 SQL：5 级 @DataScope、DEPT+SELF 括号防穿透、空 CUSTOM / 停用角色 Fail-Closed、100003 透传与租户隔离")
    void shouldFilterRealSqlRowsAcrossAllFiveDataScopeLevels() {
        // 1. ALL 放行同租户有效行，严格不泄漏已删用户及他租户行
        assertVisibleIdsForRoles(USER_CHILD, List.of(7301L),
                USER_ROOT, USER_CHILD, USER_GRANDCHILD, USER_SIBLING, USER_DEPTLESS);

        // 2. CUSTOM 仅见配置部门 7103
        assertVisibleIdsForRoles(USER_CHILD, List.of(7302L), USER_SIBLING);

        // 3. DEPT 仅见本人实时部门 7101
        assertVisibleIdsForRoles(USER_CHILD, List.of(7303L), USER_CHILD);

        // 4. DEPT_AND_CHILD 可见 7101 及其下级 7102
        assertVisibleIdsForRoles(USER_CHILD, List.of(7304L), USER_CHILD, USER_GRANDCHILD);

        // 5. SELF 仅见本人 id
        assertVisibleIdsForRoles(USER_CHILD, List.of(7305L), USER_CHILD);

        // 6. CUSTOM(7103) + SELF(USER_CHILD=7202) OR 括号防穿透，不泄漏已删行 7206 或他租户行 7901
        assertVisibleIdsForRoles(USER_CHILD, List.of(7302L, 7305L), USER_CHILD, USER_SIBLING);

        // 7. 无部门用户授予 DEPT / CUSTOM 未绑定任何部门 / 仅停用角色 / 无角色：均 Fail-Closed 返回空集合
        assertVisibleIdsForRoles(USER_DEPTLESS, List.of(7303L));
        assertVisibleIdsForRoles(USER_CHILD, List.of(7306L));
        assertVisibleIdsForRoles(USER_CHILD, List.of(7307L));
        assertVisibleIdsForRoles(USER_ROOT, List.of());

        // 8. 触发 DEPT_AND_CHILD 超过 maxDeptInSize 上限时，经 AtengDataPermissionInnerInterceptor 原样透传 100003
        rebindUserRoles(USER_CHILD, List.of(7304L));
        int originalLimit = solidSystemProperties.getDataScope().getMaxDeptInSize();
        try {
            solidSystemProperties.getDataScope().setMaxDeptInSize(1);
            TenantContextHolder.runWithTenant(TENANT_ID, () ->
                    UserContextHolder.runWithUser(USER_CHILD, () ->
                            assertThatThrownBy(() -> dataScopeVerifyMapper.selectVisibleUserIds())
                                    .hasRootCauseInstanceOf(BusinessException.class)
                                    .satisfies(ex -> {
                                        Throwable cause = ex instanceof BusinessException ? ex : ex.getCause();
                                        while (cause != null && !(cause instanceof BusinessException)) {
                                            cause = cause.getCause();
                                        }
                                        assertThat(cause).isInstanceOf(BusinessException.class);
                                        assertThat(((BusinessException) cause).getCode())
                                                .isEqualTo(CommonBizResultCode.DATA_SCOPE_ERROR.getCode());
                                    })
                    )
            );
        } finally {
            solidSystemProperties.getDataScope().setMaxDeptInSize(originalLimit);
        }

        // 9. userColumn = "create_by" 走 StringValue 匹配分支；超管短路放行
        rebindUserRoles(USER_DEPTLESS, List.of(7305L));
        TenantContextHolder.runWithTenant(TENANT_ID, () ->
                UserContextHolder.runWithUser(USER_DEPTLESS, () ->
                        assertThat(dataScopeVerifyMapper.selectVisibleUserIdsByCreateBy()).containsExactly(USER_DEPTLESS)
                )
        );

        rebindUserRoles(USER_ROOT, List.of());
        TenantContextHolder.runWithTenant(TENANT_ID, () ->
                UserContextHolder.runWithUser(USER_ROOT, () ->
                        DataPermissionContextHolder.runAsSuperAdmin(() ->
                                assertThat(dataScopeVerifyMapper.selectVisibleUserIds()).containsExactly(
                                        USER_ROOT, USER_CHILD, USER_GRANDCHILD, USER_SIBLING, USER_DEPTLESS
                                )
                        )
                )
        );
        Long otherVisibleCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sys_user WHERE tenant_id = ? AND deleted = 0", Long.class, OTHER_TENANT_ID);
        assertThat(otherVisibleCount).isEqualTo(1L);
    }

    private void assertVisibleIdsForRoles(Long userId, List<Long> roleIds, Long... expectedIds) {
        rebindUserRoles(userId, roleIds);
        TenantContextHolder.runWithTenant(TENANT_ID, () ->
                UserContextHolder.runWithUser(userId, () ->
                        assertThat(dataScopeVerifyMapper.selectVisibleUserIds()).containsExactly(expectedIds)
                )
        );
    }

    private void rebindUserRoles(Long userId, List<Long> roleIds) {
        TenantContextHolder.runWithTenant(TENANT_ID, () -> {
            sysUserRoleMapper.delete(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysUserRole>lambdaQuery()
                            .eq(SysUserRole::getTenantId, TENANT_ID)
                            .eq(SysUserRole::getUserId, userId)
            );
            for (Long roleId : roleIds) {
                SysUserRole ur = new SysUserRole();
                ur.setTenantId(TENANT_ID);
                ur.setUserId(userId);
                ur.setRoleId(roleId);
                sysUserRoleMapper.insert(ur);
            }
        });
    }

    private void insertTenant(Long id, String code, String name) {
        SysTenant tenant = new SysTenant();
        tenant.setId(id);
        tenant.setTenantCode(code);
        tenant.setTenantName(name);
        tenant.setAccountCount(-1);
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

    private void insertUser(Long tenantId, Long id, Long deptId, String username, String createBy) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setTenantId(tenantId);
        user.setDeptId(deptId);
        user.setUsername(username);
        user.setNickname(username);
        user.setPassword(PasswordUtil.hash("Ateng@7001"));
        user.setStatus(CommonStatusEnum.NORMAL.getCode());
        user.setCreateBy(createBy);
        sysUserMapper.insert(user);
    }

    private void insertRole(Long id, String roleKey, DataScopeType scope, CommonStatusEnum status) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setTenantId(TENANT_ID);
        role.setRoleName(roleKey);
        role.setRoleKey(roleKey);
        role.setDataScope(scope.getCode());
        role.setMenuCheckStrictly(true);
        role.setDeptCheckStrictly(false);
        role.setSort(1);
        role.setStatus(status.getCode());
        sysRoleMapper.insert(role);
    }
}

package io.github.atengk.solid.system.security;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.mybatisplus.annotation.DataScope;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.enums.DataScopeType;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysRoleDept;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysRoleDeptMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import io.github.atengk.solid.system.properties.SolidSystemProperties;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Table;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SolidDataPermissionHandler} 5 级数据范围、并集括号保护与 Fail-Closed 边界单元测试。
 *
 * @author Ateng
 * @since 2026-11-25
 */
class SolidDataPermissionHandlerTest {

    private static final long TENANT_ID = 2001L;
    private static final long USER_ID = 9001L;

    private SysUserMapper sysUserMapper;
    private SysUserRoleMapper sysUserRoleMapper;
    private SysRoleDeptMapper sysRoleDeptMapper;
    private SysDeptMapper sysDeptMapper;
    private SolidSystemProperties systemProperties;
    private SolidDataPermissionHandler handler;

    @BeforeAll
    static void initMyBatisPlusTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, SysUser.class);
        TableInfoHelper.initTableInfo(assistant, SysDept.class);
        TableInfoHelper.initTableInfo(assistant, SysRoleDept.class);
        TableInfoHelper.initTableInfo(assistant, SysRole.class);
    }

    @BeforeEach
    void setUp() {
        sysUserMapper = Mockito.mock(SysUserMapper.class);
        sysUserRoleMapper = Mockito.mock(SysUserRoleMapper.class);
        sysRoleDeptMapper = Mockito.mock(SysRoleDeptMapper.class);
        sysDeptMapper = Mockito.mock(SysDeptMapper.class);
        systemProperties = new SolidSystemProperties();
        handler = new SolidDataPermissionHandler(
                providerOf(sysUserMapper),
                providerOf(sysUserRoleMapper),
                providerOf(sysRoleDeptMapper),
                providerOf(sysDeptMapper),
                systemProperties
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T instance) {
        ObjectProvider<T> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        DataPermissionContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("无有效角色或缺少上下文时 Fail-Closed 返回 1 = 0")
    void shouldFailClosedWhenNoRolesOrMissingContext() {
        assertThat(handler.getSqlSegment(new Table("sys_user"), null, "m", dataScope("", "dept_id", "id")))
                .hasToString("1 = 0");

        when(sysUserRoleMapper.selectActiveRolesByUserId(TENANT_ID, USER_ID))
                .thenReturn(Collections.emptyList());

        Expression expr = TenantContextHolder.runWithTenant(TENANT_ID, () ->
                UserContextHolder.runWithUser(String.valueOf(USER_ID), () ->
                        handler.getSqlSegment(new Table("sys_user"), null, "m", dataScope("", "dept_id", "id"))));
        assertThat(expr).hasToString("1 = 0");
    }

    @Test
    @DisplayName("任一有效角色具备 ALL 数据权限时短路返回 null（且内部查询在 runWithoutPermission 中运行）")
    void shouldReturnNullForAllScopeInsideRunWithoutPermission() {
        AtomicBoolean ignoreInsideQuery = new AtomicBoolean(false);
        when(sysUserRoleMapper.selectActiveRolesByUserId(eq(TENANT_ID), eq(USER_ID))).thenAnswer(inv -> {
            ignoreInsideQuery.set(DataPermissionContextHolder.isIgnore());
            return List.of(
                    role(10L, DataScopeType.DEPT),
                    role(11L, DataScopeType.ALL)
            );
        });

        Expression expr = TenantContextHolder.runWithTenant(TENANT_ID, () ->
                UserContextHolder.runWithUser(String.valueOf(USER_ID), () ->
                        handler.getSqlSegment(new Table("sys_user"), null, "m", dataScope("", "dept_id", "id"))));

        assertThat(expr).isNull();
        assertThat(ignoreInsideQuery.get()).isTrue();
        assertThat(DataPermissionContextHolder.isIgnore()).isFalse();
        verify(sysUserMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("CUSTOM 角色聚合关联部门；无部门用户仅配 DEPT 时 Fail-Closed 返回 1 = 0")
    void shouldHandleCustomScopeAndNoDeptFailClosed() {
        when(sysUserRoleMapper.selectActiveRolesByUserId(TENANT_ID, USER_ID))
                .thenReturn(List.of(role(20L, DataScopeType.CUSTOM)));
        SysRoleDept rd1 = new SysRoleDept();
        rd1.setDeptId(101L);
        SysRoleDept rd2 = new SysRoleDept();
        rd2.setDeptId(102L);
        when(sysRoleDeptMapper.selectList(any())).thenReturn(List.of(rd1, rd2));

        Expression customExpr = TenantContextHolder.runWithTenant(TENANT_ID, () ->
                UserContextHolder.runWithUser(String.valueOf(USER_ID), () ->
                        handler.getSqlSegment(new Table("sys_user"), null, "m", dataScope("", "dept_id", "id"))));
        assertThat(customExpr).hasToString("dept_id IN (101, 102)");

        when(sysUserRoleMapper.selectActiveRolesByUserId(TENANT_ID, USER_ID))
                .thenReturn(List.of(role(21L, DataScopeType.DEPT)));
        SysUser userWithoutDept = new SysUser();
        userWithoutDept.setId(USER_ID);
        userWithoutDept.setDeptId(null);
        when(sysUserMapper.selectOne(any())).thenReturn(userWithoutDept);

        Expression noDeptExpr = TenantContextHolder.runWithTenant(TENANT_ID, () ->
                UserContextHolder.runWithUser(String.valueOf(USER_ID), () ->
                        handler.getSqlSegment(new Table("sys_user"), null, "m", dataScope("", "dept_id", "id"))));
        assertThat(noDeptExpr).hasToString("1 = 0");
    }

    @Test
    @DisplayName("DEPT_AND_CHILD 预解析子树超过 maxDeptInSize 抛出 100003")
    void shouldResolveDeptAndChildAndEnforceMaxDeptInSize() {
        systemProperties.getDataScope().setMaxDeptInSize(2);
        when(sysUserRoleMapper.selectActiveRolesByUserId(TENANT_ID, USER_ID))
                .thenReturn(List.of(role(30L, DataScopeType.DEPT_AND_CHILD)));

        SysUser user = new SysUser();
        user.setId(USER_ID);
        user.setDeptId(100L);
        when(sysUserMapper.selectOne(any())).thenReturn(user);

        SysDept currentDept = dept(100L, "0");
        SysDept child1 = dept(101L, "0,100");
        SysDept child2 = dept(102L, "0,100,101");
        when(sysDeptMapper.selectOne(any())).thenReturn(currentDept);
        when(sysDeptMapper.selectList(any())).thenReturn(List.of(currentDept, child1, child2));

        assertThatThrownBy(() -> TenantContextHolder.runWithTenant(TENANT_ID, () ->
                UserContextHolder.runWithUser(String.valueOf(USER_ID), () ->
                        handler.getSqlSegment(new Table("sys_user"), null, "m", dataScope("", "dept_id", "id")))))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(CommonBizResultCode.DATA_SCOPE_ERROR.getCode()));

        systemProperties.getDataScope().setMaxDeptInSize(1000);
        Expression childExpr = TenantContextHolder.runWithTenant(TENANT_ID, () ->
                UserContextHolder.runWithUser(String.valueOf(USER_ID), () ->
                        handler.getSqlSegment(new Table("sys_user"), null, "m", dataScope("", "dept_id", "id"))));
        assertThat(childExpr).hasToString("dept_id IN (100, 101, 102)");
    }

    @Test
    @DisplayName("DEPT + SELF 并集包裹 ParenthesedExpressionList(OrExpression) 并正确处理表别名与 create_by")
    void shouldWrapDeptOrSelfInParenthesesAndSupportAliasAndCreateBy() {
        when(sysUserRoleMapper.selectActiveRolesByUserId(TENANT_ID, USER_ID))
                .thenReturn(List.of(role(40L, DataScopeType.DEPT), role(41L, DataScopeType.SELF)));

        SysUser user = new SysUser();
        user.setId(USER_ID);
        user.setDeptId(100L);
        when(sysUserMapper.selectOne(any())).thenReturn(user);

        Table aliasedUserTable = new Table("sys_user").withAlias(new Alias("u"));
        Expression unionExpr = TenantContextHolder.runWithTenant(TENANT_ID, () ->
                UserContextHolder.runWithUser(String.valueOf(USER_ID), () ->
                        handler.getSqlSegment(aliasedUserTable, null, "m", dataScope("u", "dept_id", "id"))));

        assertThat(unionExpr).isInstanceOf(ParenthesedExpressionList.class);
        ParenthesedExpressionList<?> parenthesed = (ParenthesedExpressionList<?>) unionExpr;
        assertThat(parenthesed).hasSize(1);
        assertThat(parenthesed.get(0)).isInstanceOf(OrExpression.class);
        assertThat(unionExpr).hasToString("(u.dept_id = 100 OR u.id = 9001)");

        when(sysUserRoleMapper.selectActiveRolesByUserId(TENANT_ID, USER_ID))
                .thenReturn(List.of(role(42L, DataScopeType.SELF)));
        Expression selfCreateByExpr = TenantContextHolder.runWithTenant(TENANT_ID, () ->
                UserContextHolder.runWithUser(String.valueOf(USER_ID), () ->
                        handler.getSqlSegment(new Table("biz_order"), null, "m", dataScope("", "dept_id", "create_by"))));
        assertThat(selfCreateByExpr).hasToString("create_by = '9001'");
    }

    private static SysRole role(Long id, DataScopeType scopeType) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setDataScope(scopeType.getCode());
        return role;
    }

    private static SysDept dept(Long id, String ancestors) {
        SysDept dept = new SysDept();
        dept.setId(id);
        dept.setAncestors(ancestors);
        return dept;
    }

    private static DataScope dataScope(String alias, String deptColumn, String userColumn) {
        return new DataScope() {
            @Override
            public String alias() {
                return alias;
            }

            @Override
            public String deptColumn() {
                return deptColumn;
            }

            @Override
            public String userColumn() {
                return userColumn;
            }

            @Override
            public boolean ignore() {
                return false;
            }

            @Override
            public String type() {
                return "";
            }

            @Override
            public Class<? extends Annotation> annotationType() {
                return DataScope.class;
            }
        };
    }
}

package io.github.atengk.solid.system.support;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.enums.DataScopeType;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysTenantPackage;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.entity.SysUserRole;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysRoleMenuMapper;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import io.github.atengk.solid.system.support.TenantInitializationSupport.ProvisionTenantCommand;
import io.github.atengk.solid.system.vo.CreateTenantVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link TenantInitializationSupport} 新建租户原子初始化与并发唯一键冲突转译（{@code 102002}）单元测试。
 *
 * @author Ateng
 * @since 2026-11-27
 */
class TenantInitializationSupportTest {

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    private static SysTenantPackage activePackage(Long id) {
        SysTenantPackage pkg = new SysTenantPackage();
        pkg.setId(id);
        pkg.setStatus(CommonStatusEnum.NORMAL.getCode());
        return pkg;
    }

    @Test
    @DisplayName("并发建租户主表插入命中 DuplicateKeyException 时应当转译为 102002 并立即阻断后续初始化插入")
    void shouldTranslateDuplicateKeyExceptionTo102002WithoutDownstreamInserts() {
        SysTenantMapper tenantMapper = mock(SysTenantMapper.class);
        SysTenantPackageMapper packageMapper = mock(SysTenantPackageMapper.class);
        when(packageMapper.selectOne(any())).thenReturn(activePackage(1L));
        SysDeptMapper deptMapper = mock(SysDeptMapper.class);
        SysRoleMapper roleMapper = mock(SysRoleMapper.class);
        SysRoleMenuMapper roleMenuMapper = mock(SysRoleMenuMapper.class);
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);
        TenantPackageMenuSyncSupport menuSyncSupport = mock(TenantPackageMenuSyncSupport.class);
        when(menuSyncSupport.loadEligiblePackageMenuIds(1L)).thenReturn(Collections.emptyList());

        TenantInitializationSupport support = new TenantInitializationSupport(
                tenantMapper, packageMapper, deptMapper, roleMapper, roleMenuMapper, userMapper, userRoleMapper, menuSyncSupport
        );

        doThrow(new DuplicateKeyException("Duplicate entry 'RACE01-0' for key 'uk_tenant_code_deleted'"))
                .when(tenantMapper).insert(any(SysTenant.class));

        ProvisionTenantCommand cmd = new ProvisionTenantCommand(
                "RACE01",
                "并发竞争租户",
                1L,
                10,
                null,
                CommonStatusEnum.NORMAL.getCode(),
                null,
                "race_admin",
                "encoded-hash-stub"
        );

        assertThatThrownBy(() -> support.provisionNewTenantAtomically(cmd))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException bizEx = (BusinessException) ex;
                    assertThat(bizEx.getCode()).isEqualTo(SystemResultCode.DUPLICATE_RESOURCE.getCode());
                    assertThat(bizEx.getMsg()).contains("租户编码已被占用");
                    assertThat(bizEx.getCause()).isInstanceOf(DuplicateKeyException.class);
                });

        verifyNoInteractions(deptMapper, roleMapper, roleMenuMapper, userMapper, userRoleMapper);
    }

    @Test
    @DisplayName("事务内加锁复核租户套餐若已被停用或删除应当直接抛出 100001 并中止建租户写库")
    void shouldRejectWhenLockedPackageDisabledOrMissingInTx() {
        SysTenantMapper tenantMapper = mock(SysTenantMapper.class);
        SysTenantPackageMapper packageMapper = mock(SysTenantPackageMapper.class);
        SysTenantPackage disabledPkg = activePackage(3L);
        disabledPkg.setStatus(CommonStatusEnum.DISABLED.getCode());
        when(packageMapper.selectOne(any())).thenReturn(disabledPkg);

        SysDeptMapper deptMapper = mock(SysDeptMapper.class);
        SysRoleMapper roleMapper = mock(SysRoleMapper.class);
        SysRoleMenuMapper roleMenuMapper = mock(SysRoleMenuMapper.class);
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);
        TenantPackageMenuSyncSupport menuSyncSupport = mock(TenantPackageMenuSyncSupport.class);

        TenantInitializationSupport support = new TenantInitializationSupport(
                tenantMapper, packageMapper, deptMapper, roleMapper, roleMenuMapper, userMapper, userRoleMapper, menuSyncSupport
        );
        ProvisionTenantCommand cmd = new ProvisionTenantCommand(
                "PKGDISABLED", "停用套餐测试租户", 3L, 10, null,
                CommonStatusEnum.NORMAL.getCode(), null, "pkg_admin", "hash"
        );

        assertThatThrownBy(() -> support.provisionNewTenantAtomically(cmd))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(CommonBizResultCode.PARAM_INVALID.getCode()));

        verifyNoInteractions(menuSyncSupport, tenantMapper, deptMapper, roleMapper, roleMenuMapper, userMapper, userRoleMapper);
    }

    @Test
    @DisplayName("主表落库成功时依次初始化根部门、tenant_admin 角色与未改密管理员，并在超长租户名时回退默认昵称")
    void shouldProvisionTenantHierarchyAndFallbackLongNickname() {
        SysTenantMapper tenantMapper = mock(SysTenantMapper.class);
        SysTenantPackageMapper packageMapper = mock(SysTenantPackageMapper.class);
        when(packageMapper.selectOne(any())).thenReturn(activePackage(2L));
        SysDeptMapper deptMapper = mock(SysDeptMapper.class);
        SysRoleMapper roleMapper = mock(SysRoleMapper.class);
        SysRoleMenuMapper roleMenuMapper = mock(SysRoleMenuMapper.class);
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);
        TenantPackageMenuSyncSupport menuSyncSupport = mock(TenantPackageMenuSyncSupport.class);
        when(menuSyncSupport.loadEligiblePackageMenuIds(2L)).thenReturn(Collections.emptyList());

        TenantInitializationSupport support = new TenantInitializationSupport(
                tenantMapper, packageMapper, deptMapper, roleMapper, roleMenuMapper, userMapper, userRoleMapper, menuSyncSupport
        );

        doAnswer(invocation -> {
            SysTenant entity = invocation.getArgument(0);
            entity.setId(9001L);
            return 1;
        }).when(tenantMapper).insert(any(SysTenant.class));

        doAnswer(invocation -> {
            SysDept entity = invocation.getArgument(0);
            entity.setId(9101L);
            return 1;
        }).when(deptMapper).insert(any(SysDept.class));

        doAnswer(invocation -> {
            SysRole entity = invocation.getArgument(0);
            entity.setId(9201L);
            return 1;
        }).when(roleMapper).insert(any(SysRole.class));

        doAnswer(invocation -> {
            SysUser entity = invocation.getArgument(0);
            entity.setId(9301L);
            return 1;
        }).when(userMapper).insert(any(SysUser.class));

        String longTenantName = "这是一个超过三十个字符的超长企业租户名称用于验证昵称回退";
        ProvisionTenantCommand cmd = new ProvisionTenantCommand(
                "T9001",
                longTenantName,
                2L,
                20,
                null,
                CommonStatusEnum.NORMAL.getCode(),
                "单元测试租户",
                "t9001_admin",
                "encoded-hash-stub"
        );

        CreateTenantVO result = support.provisionNewTenantAtomically(cmd);

        InOrder lockThenLoadOrder = inOrder(packageMapper, menuSyncSupport);
        lockThenLoadOrder.verify(packageMapper).selectOne(any());
        lockThenLoadOrder.verify(menuSyncSupport).loadEligiblePackageMenuIds(2L);

        assertThat(result.getTenantId()).isEqualTo(9001L);
        assertThat(result.getTenantCode()).isEqualTo("T9001");
        assertThat(result.getTenantName()).isEqualTo(longTenantName);
        assertThat(result.getAdminUsername()).isEqualTo("t9001_admin");

        ArgumentCaptor<SysDept> deptCaptor = ArgumentCaptor.forClass(SysDept.class);
        verify(deptMapper).insert(deptCaptor.capture());
        assertThat(deptCaptor.getValue().getTenantId()).isEqualTo(9001L);
        assertThat(deptCaptor.getValue().getParentId()).isZero();
        assertThat(deptCaptor.getValue().getAncestors()).isEqualTo("0");
        assertThat(DeptTreeSupport.normalizeRootDeptName("华东/研发企业")).isEqualTo("华东-研发企业");

        ArgumentCaptor<SysRole> roleCaptor = ArgumentCaptor.forClass(SysRole.class);
        verify(roleMapper).insert(roleCaptor.capture());
        assertThat(roleCaptor.getValue().getRoleKey()).isEqualTo(TenantInitializationSupport.TENANT_ADMIN_ROLE_KEY);
        assertThat(roleCaptor.getValue().getDataScope()).isEqualTo(DataScopeType.ALL.getCode());

        ArgumentCaptor<SysUser> userCaptor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(userCaptor.capture());
        SysUser createdUser = userCaptor.getValue();
        assertThat(createdUser.getTenantId()).isEqualTo(9001L);
        assertThat(createdUser.getDeptId()).isEqualTo(9101L);
        assertThat(createdUser.getNickname()).isEqualTo("租户管理员");
        assertThat(createdUser.getPwdUpdateTime()).isNull();
        assertThat(createdUser.getPhone()).isNull();
        assertThat(createdUser.getEmail()).isNull();

        ArgumentCaptor<SysUserRole> userRoleCaptor = ArgumentCaptor.forClass(SysUserRole.class);
        verify(userRoleMapper).insert(userRoleCaptor.capture());
        assertThat(userRoleCaptor.getValue().getTenantId()).isEqualTo(9001L);
        assertThat(userRoleCaptor.getValue().getUserId()).isEqualTo(9301L);
        assertThat(userRoleCaptor.getValue().getRoleId()).isEqualTo(9201L);

        verifyNoInteractions(roleMenuMapper);
    }
}

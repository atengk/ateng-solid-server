package io.github.atengk.solid.system.support;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.properties.SolidTenantProperties;
import io.github.atengk.solid.system.mapper.SysMenuMapper;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMenuMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.request.TenantPackageQueryRequest;
import io.github.atengk.solid.system.request.TenantQueryRequest;
import io.github.atengk.solid.system.service.SysTenantPackageQueryService;
import io.github.atengk.solid.system.service.SysTenantPackageService;
import io.github.atengk.solid.system.service.SysTenantQueryService;
import io.github.atengk.solid.system.service.SysTenantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 单租户关闭态（{@code ateng.solid.tenant.enabled=false}）与切租户视角下租户及套餐服务 403 硬拒绝单元测试。
 *
 * @author Ateng
 * @since 2026-11-25
 */
class SysTenantToggleGuardTest {

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("ateng.solid.tenant.enabled=false 时，租户与租户套餐全部查询/写入口与切换选项统一抛出 403")
    void shouldRejectAllTenantAndPackageApisWith403WhenMultiTenantDisabled() {
        SolidTenantProperties disabledProps = new SolidTenantProperties();
        disabledProps.setEnabled(false);
        SystemContextSupport contextSupport = new SystemContextSupport(disabledProps);

        SysTenantMapper tenantMapper = mock(SysTenantMapper.class);
        SysTenantPackageMapper pkgMapper = mock(SysTenantPackageMapper.class);
        SysTenantPackageMenuMapper pkgMenuMapper = mock(SysTenantPackageMenuMapper.class);
        SysMenuMapper menuMapper = mock(SysMenuMapper.class);
        SysUserMapper userMapper = mock(SysUserMapper.class);
        TenantPackageMenuSyncSupport syncSupport = mock(TenantPackageMenuSyncSupport.class);
        TenantInitializationSupport initSupport = mock(TenantInitializationSupport.class);
        TenantStatusCache statusCache = mock(TenantStatusCache.class);

        SysTenantQueryService tenantQueryService = new SysTenantQueryService(contextSupport, tenantMapper, pkgMapper, userMapper);
        SysTenantService tenantService = new SysTenantService(contextSupport, tenantMapper, pkgMapper, syncSupport, initSupport, statusCache);
        SysTenantPackageQueryService pkgQueryService = new SysTenantPackageQueryService(
                contextSupport, pkgMapper, pkgMenuMapper, tenantMapper, menuMapper, syncSupport);
        SysTenantPackageService pkgService = new SysTenantPackageService(contextSupport, pkgMapper, pkgMenuMapper, tenantMapper, syncSupport);

        assertForbidden(() -> tenantQueryService.pageTenants(new TenantQueryRequest()));
        assertForbidden(tenantQueryService::listSwitchOptions);
        assertForbidden(() -> tenantQueryService.getDetail(0L));
        assertForbidden(() -> tenantService.updateTenantStatus(1L, 1));
        assertForbidden(() -> tenantService.deleteTenants("1"));
        assertForbidden(() -> pkgQueryService.pagePackages(new TenantPackageQueryRequest()));
        assertForbidden(pkgQueryService::listActiveOptions);
        assertForbidden(() -> pkgService.updatePackageStatus(1L, 1));
        assertForbidden(() -> pkgService.deletePackages("1"));
    }

    @Test
    @DisplayName("开启多租户后若切入非 0 普通租户视角，租户管理同样抛出 403")
    void shouldRejectWith403WhenSwitchedToNonPlatformTenant() {
        SolidTenantProperties enabledProps = new SolidTenantProperties();
        enabledProps.setEnabled(true);
        SystemContextSupport contextSupport = new SystemContextSupport(enabledProps);

        TenantContextHolder.runWithTenant(SolidConstants.PLATFORM_TENANT_ID + 5000L, () ->
                assertForbidden(contextSupport::requireMultiTenantPlatformAccess)
        );
    }

    private static void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(CommonResultCode.FORBIDDEN.getCode()));
    }
}

package io.github.atengk.solid.system.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.satoken.util.LoginHelper;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.properties.SolidTenantProperties;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.support.SystemContextSupport;
import io.github.atengk.solid.system.support.TenantInitializationSupport;
import io.github.atengk.solid.system.support.TenantPackageMenuSyncSupport;
import io.github.atengk.solid.system.support.TenantStatusCache;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * {@link SysTenantService} 基于 DB 游标分批全量强退（突破 2000 在线扫描上限）与单点失败隔离单元测试。
 *
 * @author Ateng
 * @since 2026-11-27
 */
class SysTenantServiceTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SysTenant.class);
        TableInfoHelper.initTableInfo(assistant, SysUser.class);
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("停用租户时应当按 500 条/批游标分页强退超过 2000 名全部用户，且单用户强退抛异常不阻断后续批次")
    @SuppressWarnings("unchecked")
    void shouldKickoutOverTwoThousandUsersAcrossBatchesAndIsolateSingleFailure() {
        SolidTenantProperties tenantProps = new SolidTenantProperties();
        tenantProps.setEnabled(true);
        SystemContextSupport contextSupport = new SystemContextSupport(tenantProps);

        SysTenantMapper tenantMapper = mock(SysTenantMapper.class);
        SysTenant existing = new SysTenant();
        existing.setId(8801L);
        existing.setStatus(CommonStatusEnum.NORMAL.getCode());
        when(tenantMapper.selectOne(any())).thenReturn(existing);

        SysUserMapper userMapper = mock(SysUserMapper.class);
        List<Page<SysUser>> batches = new ArrayList<>();
        long nextId = 1L;
        for (int b = 0; b < 5; b++) {
            List<SysUser> records = new ArrayList<>(500);
            for (int i = 0; i < 500; i++) {
                SysUser u = new SysUser();
                u.setId(nextId++);
                records.add(u);
            }
            Page<SysUser> page = new Page<>(1, 500, false);
            page.setRecords(records);
            batches.add(page);
        }
        List<SysUser> tail = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
            SysUser u = new SysUser();
            u.setId(nextId++);
            tail.add(u);
        }
        Page<SysUser> tailPage = new Page<>(1, 500, false);
        tailPage.setRecords(tail);
        batches.add(tailPage);

        AtomicInteger batchIndex = new AtomicInteger(0);
        when(userMapper.selectPage(any(Page.class), any())).thenAnswer(inv -> {
            int idx = batchIndex.getAndIncrement();
            return idx < batches.size() ? batches.get(idx) : new Page<SysUser>(1, 500, false);
        });

        TenantStatusCache statusCache = mock(TenantStatusCache.class);
        SysTenantService service = new SysTenantService(
                contextSupport,
                tenantMapper,
                mock(SysTenantPackageMapper.class),
                userMapper,
                mock(TenantPackageMenuSyncSupport.class),
                mock(TenantInitializationSupport.class),
                statusCache
        );

        try (MockedStatic<LoginHelper> loginHelperMock = mockStatic(LoginHelper.class)) {
            loginHelperMock.when(() -> LoginHelper.buildCompositeLoginId(anyString(), anyString()))
                    .thenAnswer(inv -> inv.getArgument(0) + ":" + inv.getArgument(1));
            loginHelperMock.when(() -> LoginHelper.kickoutByLoginType(eq(SolidConstants.ADMIN_LOGIN_TYPE), eq("8801:2")))
                    .thenThrow(new IllegalStateException("redis glitch on user 2"));

            TenantContextHolder.runWithTenant(SolidConstants.PLATFORM_TENANT_ID,
                    () -> service.updateTenantStatus(8801L, CommonStatusEnum.DISABLED.getCode()));

            loginHelperMock.verify(
                    () -> LoginHelper.kickoutByLoginType(eq(SolidConstants.ADMIN_LOGIN_TYPE), anyString()),
                    times(2510)
            );
            loginHelperMock.verify(
                    () -> LoginHelper.kickoutByLoginType(SolidConstants.ADMIN_LOGIN_TYPE, "8801:2510"),
                    times(1)
            );
        }
    }
}

package io.github.atengk.solid;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.satoken.cache.UserRolePermissionLoader;
import io.github.atengk.boot.satoken.spi.TenantHeaderSwitchValidator;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.guard.StorageGuard;
import io.github.atengk.boot.storage.model.PresignedDownloadRequest;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.model.StorageUploadRequest;
import io.github.atengk.boot.storage.properties.StorageGuardProperties;
import io.github.atengk.boot.storage.template.StorageOperations;
import io.github.atengk.boot.storage.template.StorageTemplate;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.enums.LogStatusEnum;
import io.github.atengk.solid.common.event.DomainEventPublisher;
import io.github.atengk.solid.common.event.LoginAuditEvent;
import io.github.atengk.solid.common.properties.SolidTenantProperties;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.security.TenantHeaderSwitchValidatorImpl;
import io.github.atengk.solid.system.api.command.LoginAuditCommand;
import io.github.atengk.solid.system.api.command.UpdatePersonalProfileCommand;
import io.github.atengk.solid.system.api.command.UploadAvatarCommand;
import io.github.atengk.solid.system.api.dto.AdminAuthUserDTO;
import io.github.atengk.solid.system.api.dto.AdminProfileDTO;
import io.github.atengk.solid.system.api.dto.AvatarUploadDTO;
import io.github.atengk.solid.system.api.dto.PersonalProfileDTO;
import io.github.atengk.solid.system.api.dto.RouteMenuDTO;
import io.github.atengk.solid.system.api.dto.TenantAuthDTO;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.api.service.RemoteAuditLogService;
import io.github.atengk.solid.system.api.service.RemotePermissionService;
import io.github.atengk.solid.system.api.service.RemoteTenantService;
import io.github.atengk.solid.system.api.service.RemoteUserService;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysLoginLog;
import io.github.atengk.solid.system.entity.SysPost;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysRoleMenu;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysTenantPackage;
import io.github.atengk.solid.system.entity.SysTenantPackageMenu;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.entity.SysUserPost;
import io.github.atengk.solid.system.entity.SysUserRole;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysLoginLogMapper;
import io.github.atengk.solid.system.mapper.SysPostMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysRoleMenuMapper;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMenuMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserPostMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import io.github.atengk.solid.system.support.ContactFieldCrypto;
import io.github.atengk.solid.system.support.TenantStatusCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RemoteTenantService}、{@link RemoteUserService}、{@link RemotePermissionService}、
 * {@link RemoteAuditLogService}、{@link TenantHeaderSwitchValidator}、
 * {@link UserRolePermissionLoader} 与异步登录审计监听器 Layer 2 全链路集成测试。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@SpringBootTest(
        classes = {SolidApplication.class, RemoteServicesIntegrationTest.MockStorageConfig.class},
        properties = {
                "ateng.solid.init.enabled=true",
                "ateng.solid.tenant.enabled=true",
                "ateng.boot.mybatis-plus.tenant.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:remote_services_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        }
)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteServicesIntegrationTest {

    private static final long TENANT_A_ID = 2001L;
    private static final long TENANT_DISABLED_ID = 2002L;
    private static final long TENANT_EXPIRED_ID = 2003L;
    private static final long PACKAGE_BASIC_ID = 3001L;
    private static final long DEPT_A_ID = 2100L;
    private static final long POST_A_ID = 2200L;
    private static final long ROLE_OPS_ID = 2301L;
    private static final long ROLE_DISABLED_ID = 2302L;
    private static final long USER_ALICE_ID = 2401L;
    private static final long USER_BOB_ID = 2402L;
    private static final long USER_CAROL_ID = 2403L;
    private static final long USER_PLATFORM_CLERK_ID = 2404L;

    private static final byte[] VALID_PNG_BYTES = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01
    };

    @TestConfiguration
    static class MockStorageConfig {

        @Bean
        StorageGuard avatarTestStorageGuard() {
            StorageGuardProperties props = new StorageGuardProperties();
            props.setEnabled(true);
            props.setStrictMagicCheck(true);
            props.setAllowedExtensions(Set.of("jpg", "jpeg", "png", "webp"));
            props.setMaxFileSize(DataSize.ofMegabytes(2));
            return new StorageGuard(props);
        }

        @Bean
        StorageDriver mockStorageDriver() {
            StorageDriver driver = Mockito.mock(StorageDriver.class);
            when(driver.upload(anyString(), any(InputStream.class), anyString(), anyLong()))
                    .thenAnswer(inv -> StorageObject.builder().path(inv.getArgument(0)).build());
            when(driver.upload(anyString(), any(InputStream.class), anyString(), anyLong(), nullable(java.util.Map.class)))
                    .thenAnswer(inv -> StorageObject.builder().path(inv.getArgument(0)).build());
            when(driver.upload(
                    anyString(), any(InputStream.class), anyString(), anyLong(), nullable(java.util.Map.class), anyBoolean()))
                    .thenAnswer(inv -> StorageObject.builder().path(inv.getArgument(0)).build());
            when(driver.uploadIfAbsent(
                    anyString(), any(InputStream.class), anyString(), anyLong(), nullable(java.util.Map.class), anyBoolean()))
                    .thenAnswer(inv -> StorageObject.builder().path(inv.getArgument(0)).build());
            when(driver.getPresignedUrl(any(PresignedDownloadRequest.class)))
                    .thenAnswer(inv -> {
                        PresignedDownloadRequest req = inv.getArgument(0);
                        return PresignedUrl.builder()
                                .url("https://mock-s3.local/" + req.getPath()
                                        + "?disposition=" + req.getResponseContentDisposition())
                                .build();
                    });
            when(driver.delete(anyString())).thenReturn(true);
            return driver;
        }

        @Bean
        StorageOperations testStorageOperations(StorageDriver driver, StorageGuard storageGuard) {
            StorageTemplate template = new StorageTemplate(driver);
            template.setStorageGuard(storageGuard);
            return template;
        }
    }

    @Autowired
    private RemoteTenantService remoteTenantService;
    @Autowired
    private RemoteUserService remoteUserService;
    @Autowired
    private RemotePermissionService remotePermissionService;
    @Autowired
    private RemoteAuditLogService remoteAuditLogService;
    @Autowired
    private TenantHeaderSwitchValidator tenantHeaderSwitchValidator;
    @Autowired
    private UserRolePermissionLoader userRolePermissionLoader;
    @Autowired
    private TenantStatusCache tenantStatusCache;
    @Autowired
    private DomainEventPublisher domainEventPublisher;
    @Autowired
    private StorageDriver mockStorageDriver;
    @Autowired
    private StorageOperations storageOperations;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SysTenantMapper sysTenantMapper;
    @Autowired
    private SysTenantPackageMapper sysTenantPackageMapper;
    @Autowired
    private SysTenantPackageMenuMapper sysTenantPackageMenuMapper;
    @Autowired
    private SysDeptMapper sysDeptMapper;
    @Autowired
    private SysPostMapper sysPostMapper;
    @Autowired
    private SysRoleMapper sysRoleMapper;
    @Autowired
    private SysRoleMenuMapper sysRoleMenuMapper;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;
    @Autowired
    private SysUserPostMapper sysUserPostMapper;
    @Autowired
    private SysLoginLogMapper sysLoginLogMapper;

    private Long superAdminUserId;

    @BeforeAll
    void setUpFixtures() {
        superAdminUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE tenant_id = 0 AND username = 'superadmin' AND deleted = 0",
                Long.class
        );

        SysTenantPackage basicPkg = new SysTenantPackage();
        basicPkg.setId(PACKAGE_BASIC_ID);
        basicPkg.setPackageName("基础企业套餐");
        basicPkg.setMenuCheckStrictly(true);
        basicPkg.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysTenantPackageMapper.insert(basicPkg);

        for (Long menuId : List.of(1000L, 1100L, 1101L, 1102L, 2000L, 2200L, 2201L)) {
            SysTenantPackageMenu pm = new SysTenantPackageMenu();
            pm.setPackageId(PACKAGE_BASIC_ID);
            pm.setMenuId(menuId);
            sysTenantPackageMenuMapper.insert(pm);
        }

        insertTenant(TENANT_A_ID, "T2001", "测试租户A", PACKAGE_BASIC_ID, null, CommonStatusEnum.NORMAL.getCode());
        insertTenant(TENANT_DISABLED_ID, "T2002", "停用租户B", PACKAGE_BASIC_ID, null, CommonStatusEnum.DISABLED.getCode());
        insertTenant(TENANT_EXPIRED_ID, "T2003", "过期租户C", PACKAGE_BASIC_ID,
                LocalDateTime.now().minusSeconds(10), CommonStatusEnum.NORMAL.getCode());

        TenantContextHolder.runWithTenant(TENANT_A_ID, () -> {
            SysDept dept = new SysDept();
            dept.setId(DEPT_A_ID);
            dept.setTenantId(TENANT_A_ID);
            dept.setParentId(0L);
            dept.setAncestors("0");
            dept.setDeptName("研发一部");
            dept.setSort(1);
            dept.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysDeptMapper.insert(dept);

            SysPost post = new SysPost();
            post.setId(POST_A_ID);
            post.setTenantId(TENANT_A_ID);
            post.setPostCode("ARCH");
            post.setPostName("架构师");
            post.setSort(1);
            post.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysPostMapper.insert(post);

            SysRole opsRole = new SysRole();
            opsRole.setId(ROLE_OPS_ID);
            opsRole.setTenantId(TENANT_A_ID);
            opsRole.setRoleName("运维主管");
            opsRole.setRoleKey("ops_mgr");
            opsRole.setDataScope(1);
            opsRole.setMenuCheckStrictly(true);
            opsRole.setDeptCheckStrictly(false);
            opsRole.setSort(1);
            opsRole.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysRoleMapper.insert(opsRole);

            SysRole disabledRole = new SysRole();
            disabledRole.setId(ROLE_DISABLED_ID);
            disabledRole.setTenantId(TENANT_A_ID);
            disabledRole.setRoleName("停用角色");
            disabledRole.setRoleKey("disabled_role");
            disabledRole.setDataScope(5);
            disabledRole.setMenuCheckStrictly(true);
            disabledRole.setDeptCheckStrictly(false);
            disabledRole.setSort(2);
            disabledRole.setStatus(CommonStatusEnum.DISABLED.getCode());
            sysRoleMapper.insert(disabledRole);

            // 故意混入平台专属 1300/1301 与未入套餐页面 1200/1201 验证后端纵深剪枝拦截
            for (Long menuId : List.of(1000L, 1100L, 1101L, 1102L, 1200L, 1201L, 1300L, 1301L)) {
                SysRoleMenu rm = new SysRoleMenu();
                rm.setTenantId(TENANT_A_ID);
                rm.setRoleId(ROLE_OPS_ID);
                rm.setMenuId(menuId);
                sysRoleMenuMapper.insert(rm);
            }

            SysUser alice = new SysUser();
            alice.setId(USER_ALICE_ID);
            alice.setTenantId(TENANT_A_ID);
            alice.setDeptId(DEPT_A_ID);
            alice.setUsername("alice");
            alice.setNickname("爱丽丝");
            alice.setPassword(PasswordUtil.hash("Ateng@Alice1"));
            alice.setPhone("13800138001");
            alice.setEmail("alice@example.com");
            alice.setGender("1");
            alice.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysUserMapper.insert(alice);

            SysUser bob = new SysUser();
            bob.setId(USER_BOB_ID);
            bob.setTenantId(TENANT_A_ID);
            bob.setDeptId(DEPT_A_ID);
            bob.setUsername("bob");
            bob.setNickname("鲍勃");
            bob.setPassword(PasswordUtil.hash("Ateng@Bob123"));
            bob.setPhone("13800138002");
            bob.setEmail("bob@example.com");
            bob.setGender("0");
            bob.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysUserMapper.insert(bob);

            bindUserRole(TENANT_A_ID, USER_ALICE_ID, ROLE_OPS_ID);
            bindUserRole(TENANT_A_ID, USER_ALICE_ID, ROLE_DISABLED_ID);

            SysUserPost up = new SysUserPost();
            up.setTenantId(TENANT_A_ID);
            up.setUserId(USER_ALICE_ID);
            up.setPostId(POST_A_ID);
            sysUserPostMapper.insert(up);
        });

        TenantContextHolder.runWithTenant(TENANT_DISABLED_ID, () -> {
            SysUser carol = new SysUser();
            carol.setId(USER_CAROL_ID);
            carol.setTenantId(TENANT_DISABLED_ID);
            carol.setUsername("carol");
            carol.setNickname("卡罗尔");
            carol.setPassword(PasswordUtil.hash("Ateng@Carol1"));
            carol.setPhone("13800138002");
            carol.setEmail("bob@example.com");
            carol.setGender("1");
            carol.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysUserMapper.insert(carol);
        });

        TenantContextHolder.runWithTenant(SolidConstants.PLATFORM_TENANT_ID, () -> {
            SysUser clerk = new SysUser();
            clerk.setId(USER_PLATFORM_CLERK_ID);
            clerk.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
            clerk.setDeptId(SolidConstants.DEFAULT_ROOT_DEPT_ID);
            clerk.setUsername("platform_clerk");
            clerk.setNickname("平台普通员工");
            clerk.setPassword(PasswordUtil.hash("Ateng@Clerk1"));
            clerk.setGender("2");
            clerk.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysUserMapper.insert(clerk);
        });
    }

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        DataPermissionContextHolder.clear();
        MDC.clear();
    }

    private void insertTenant(
            Long id,
            String code,
            String name,
            Long packageId,
            LocalDateTime expireTime,
            Integer status
    ) {
        SysTenant tenant = new SysTenant();
        tenant.setId(id);
        tenant.setTenantCode(code);
        tenant.setTenantName(name);
        tenant.setPackageId(packageId);
        tenant.setExpireTime(expireTime);
        tenant.setAccountCount(-1);
        tenant.setStatus(status);
        sysTenantMapper.insert(tenant);
    }

    private void bindUserRole(Long tenantId, Long userId, Long roleId) {
        SysUserRole ur = new SysUserRole();
        ur.setTenantId(tenantId);
        ur.setUserId(userId);
        ur.setRoleId(roleId);
        sysUserRoleMapper.insert(ur);
    }

    @Test
    @DisplayName("RemoteTenantService 与 TenantHeaderSwitchValidator 应当校验可用性并对异常切换抛出 100002")
    void shouldValidateTenantAvailabilityAndHeaderSwitch() {
        TenantAuthDTO platform = remoteTenantService.getTenantByCode("000000");
        assertThat(platform).isNotNull();
        assertThat(platform.getTenantId()).isZero();
        assertThat(platform.getTenantName()).isEqualTo("平台默认租户");
        assertThat(remoteTenantService.getTenantByCode("NOT_EXISTS")).isNull();

        assertThat(remoteTenantService.isTenantAvailable(0L)).isTrue();
        assertThat(remoteTenantService.isTenantAvailable(TENANT_A_ID)).isTrue();
        assertThat(remoteTenantService.isTenantAvailable(TENANT_DISABLED_ID)).isFalse();
        assertThat(remoteTenantService.isTenantAvailable(TENANT_EXPIRED_ID)).isFalse();
        assertThat(remoteTenantService.isTenantAvailable(999999L)).isFalse();

        assertThat(tenantHeaderSwitchValidator.validate(
                String.valueOf(TENANT_A_ID), "0", SolidConstants.ADMIN_LOGIN_TYPE, false
        )).isTrue();

        for (String invalidTarget : List.of(
                String.valueOf(TENANT_DISABLED_ID),
                String.valueOf(TENANT_EXPIRED_ID),
                "999999",
                "abc",
                ""
        )) {
            assertThatThrownBy(() -> tenantHeaderSwitchValidator.validate(
                    invalidTarget, "0", SolidConstants.ADMIN_LOGIN_TYPE, false
            ))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getCode())
                    .isEqualTo(CommonBizResultCode.INVALID_TENANT_SWITCH.getCode());
        }

        // 匿名头一律按非法切换拒绝 100002
        assertThatThrownBy(() -> tenantHeaderSwitchValidator.validate(
                "0", null, SolidConstants.ADMIN_LOGIN_TYPE, true
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(CommonBizResultCode.INVALID_TENANT_SWITCH.getCode());

        // 缓存主动失效应当立即反映最新状态
        jdbcTemplate.update("UPDATE sys_tenant SET status = 0 WHERE id = ?", TENANT_DISABLED_ID);
        assertThat(remoteTenantService.isTenantAvailable(TENANT_DISABLED_ID)).isFalse();
        tenantStatusCache.evict(TENANT_DISABLED_ID);
        assertThat(remoteTenantService.isTenantAvailable(TENANT_DISABLED_ID)).isTrue();
        jdbcTemplate.update("UPDATE sys_tenant SET status = 1 WHERE id = ?", TENANT_DISABLED_ID);
        tenantStatusCache.evict(TENANT_DISABLED_ID);

        // 单租户模式（tenant.enabled=false）仅允许平台租户 0，禁止跨租户切换 100002
        SolidTenantProperties singleProps = new SolidTenantProperties();
        singleProps.setEnabled(false);
        TenantHeaderSwitchValidatorImpl singleModeValidator = new TenantHeaderSwitchValidatorImpl(
                remoteTenantService, singleProps
        );
        assertThat(singleModeValidator.validate("0", "0", SolidConstants.ADMIN_LOGIN_TYPE, false)).isTrue();
        assertThatThrownBy(() -> singleModeValidator.validate(
                String.valueOf(TENANT_A_ID), "0", SolidConstants.ADMIN_LOGIN_TYPE, false
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(CommonBizResultCode.INVALID_TENANT_SWITCH.getCode());
    }

    @Test
    @DisplayName("RemotePermissionService 与 UserRolePermissionLoader 应当按租户隔离并完整剪枝路由菜单树")
    void shouldResolveRolesPermissionsAndPruneRouteMenusAcrossModes() {
        assertThat(remotePermissionService.getRoleKeysByUserId(0L, superAdminUserId))
                .containsExactly(SolidConstants.SUPER_ADMIN_ROLE);
        assertThat(remotePermissionService.getPermissionsByRoleKey(0L, SolidConstants.SUPER_ADMIN_ROLE))
                .containsExactly("*:*:*");

        assertThat(remotePermissionService.getRoleKeysByUserId(TENANT_A_ID, USER_ALICE_ID))
                .containsExactly("ops_mgr");
        assertThat(remotePermissionService.getPermissionsByRoleKey(TENANT_A_ID, "ops_mgr"))
                .containsExactlyInAnyOrder("system:user:list", "system:user:create");
        assertThat(remotePermissionService.getRoleKeysByUserId(TENANT_DISABLED_ID, USER_ALICE_ID)).isEmpty();
        assertThat(remotePermissionService.getDirectPermissionsByUserId(0L, superAdminUserId)).isEmpty();

        assertThat(userRolePermissionLoader.loadRolesByUserId(
                String.valueOf(superAdminUserId), SolidConstants.ADMIN_LOGIN_TYPE, "0"
        )).containsExactly(SolidConstants.SUPER_ADMIN_ROLE);
        assertThat(userRolePermissionLoader.loadPermissionsByRole(
                "ops_mgr", SolidConstants.ADMIN_LOGIN_TYPE, String.valueOf(TENANT_A_ID)
        )).containsExactlyInAnyOrder("system:user:list", "system:user:create");
        assertThat(userRolePermissionLoader.loadRolesByUserId(
                String.valueOf(superAdminUserId), "portal", "0"
        )).isEmpty();

        // A: superAdmin + effectiveTenant=0 + tenantEnabled=false -> 隐藏多租户目录 3000，可见 1300 SysMenu 与 2100 SysDict，无 F 按钮
        List<RouteMenuDTO> singleTenantRoutes = remotePermissionService.getRouteMenusByUserId(
                0L, 0L, superAdminUserId, true, false);
        assertThat(singleTenantRoutes).extracting(RouteMenuDTO::getId)
                .containsExactly(1000L, 2000L, 4000L);
        assertThat(flattenMenuIds(singleTenantRoutes)).contains(1300L, 2100L)
                .doesNotContain(3000L, 3100L, 3200L, 1101L);

        // B: superAdmin + effectiveTenant=0 + tenantEnabled=true -> 包含 3000 多租户治理目录
        List<RouteMenuDTO> multiTenantPlatformRoutes = remotePermissionService.getRouteMenusByUserId(
                0L, 0L, superAdminUserId, true, true);
        assertThat(multiTenantPlatformRoutes).extracting(RouteMenuDTO::getId)
                .containsExactly(1000L, 2000L, 3000L, 4000L);

        // C: superAdmin 跨租户切入 TENANT_A -> 隐藏 platform_only=1 (1300/2100/3000)，并裁剪空目录 4000
        List<RouteMenuDTO> switchedViewRoutes = remotePermissionService.getRouteMenusByUserId(
                0L, TENANT_A_ID, superAdminUserId, true, true);
        assertThat(switchedViewRoutes).extracting(RouteMenuDTO::getId).containsExactly(1000L, 2000L);
        assertThat(flattenMenuIds(switchedViewRoutes)).containsExactlyInAnyOrder(1000L, 1100L, 2000L, 2200L);

        // D: 普通租户用户 Alice -> 仅持有 1100 权限且 1200 超出套餐、1300 为 platform_only，2000 空目录被自动剪枝
        List<RouteMenuDTO> aliceRoutes = remotePermissionService.getRouteMenusByUserId(
                TENANT_A_ID, TENANT_A_ID, USER_ALICE_ID, false, true);
        assertThat(aliceRoutes).extracting(RouteMenuDTO::getId).containsExactly(1000L);
        assertThat(aliceRoutes.getFirst().getChildren()).extracting(RouteMenuDTO::getId).containsExactly(1100L);
        assertThat(remotePermissionService.getRouteMenusByUserId(null, TENANT_A_ID, USER_ALICE_ID, false, true))
                .isEmpty();

        // E: 父目录 1000 停用后，整棵子树级联剪枝且按钮权限一并失效
        jdbcTemplate.update("UPDATE sys_menu SET status = 1 WHERE id = 1000");
        try {
            assertThat(remotePermissionService.getRouteMenusByUserId(
                    TENANT_A_ID, TENANT_A_ID, USER_ALICE_ID, false, true)).isEmpty();
            assertThat(remotePermissionService.getPermissionsByRoleKey(TENANT_A_ID, "ops_mgr")).isEmpty();
        } finally {
            jdbcTemplate.update("UPDATE sys_menu SET status = 0 WHERE id = 1000");
        }
    }

    @Test
    @DisplayName("RemoteUserService 应当支持认证查询、超管切换摘要、个人中心资料维护、头像事务上传与改密")
    void shouldHandleAuthQueriesProfilesProfileUpdateAvatarUploadAndPasswordHash() {
        // 1. 认证查询与租户隔离
        AdminAuthUserDTO superAuth = remoteUserService.getAuthUserByUsername(0L, "superadmin");
        assertThat(superAuth).isNotNull();
        assertThat(superAuth.getUserId()).isEqualTo(superAdminUserId);
        assertThat(remoteUserService.getAuthUserById(0L, superAdminUserId)).isNotNull();
        assertThat(remoteUserService.getAuthUserByUsername(TENANT_A_ID, "superadmin")).isNull();

        // 2. getAdminProfile: 超管跨租户切换视角成功；非超管跨租户访问拒绝 102001
        AdminProfileDTO switchedProfile = remoteUserService.getAdminProfile(TENANT_A_ID, superAdminUserId);
        assertThat(switchedProfile.getTenantId()).isEqualTo(TENANT_A_ID);
        assertThat(switchedProfile.getTenantName()).isEqualTo("测试租户A");
        assertThatThrownBy(() -> remoteUserService.getAdminProfile(TENANT_A_ID, USER_PLATFORM_CLERK_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(SystemResultCode.RECORD_NOT_FOUND.getCode());

        // 3. 个人中心完整资料与归一化编辑 + 唯一性冲突 102002
        PersonalProfileDTO aliceProfile = remoteUserService.getPersonalProfile(TENANT_A_ID, USER_ALICE_ID);
        assertThat(aliceProfile.getTenantName()).isEqualTo("测试租户A");
        assertThat(aliceProfile.getDeptName()).isEqualTo("研发一部");
        assertThat(aliceProfile.getPostNames()).containsExactly("架构师");
        assertThat(aliceProfile.getRoles()).containsExactly("运维主管");
        assertThat(aliceProfile.getPhone()).isEqualTo("13800138001");
        assertThat(aliceProfile.getEmail()).isEqualTo("alice@example.com");

        // 同租户冲突手机（Bob 已占 13800138002）-> 102002
        assertThatThrownBy(() -> remoteUserService.updatePersonalProfile(
                TENANT_A_ID,
                USER_ALICE_ID,
                UpdatePersonalProfileCommand.builder()
                        .nickname("爱丽丝")
                        .phone("13800138002")
                        .email("alice@example.com")
                        .gender("1")
                        .build()
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(SystemResultCode.DUPLICATE_RESOURCE.getCode());

        // 同租户冲突邮箱（大小写归一化撞 Bob 邮箱）-> 102002
        assertThatThrownBy(() -> remoteUserService.updatePersonalProfile(
                TENANT_A_ID,
                USER_ALICE_ID,
                UpdatePersonalProfileCommand.builder()
                        .nickname("爱丽丝")
                        .phone("13800138001")
                        .email("  BOB@EXAMPLE.COM  ")
                        .gender("1")
                        .build()
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(SystemResultCode.DUPLICATE_RESOURCE.getCode());

        // 空白清空手机号与邮箱 -> DB 存 SQL NULL
        remoteUserService.updatePersonalProfile(
                TENANT_A_ID,
                USER_ALICE_ID,
                UpdatePersonalProfileCommand.builder()
                        .nickname("爱丽丝-已更新")
                        .phone("   ")
                        .email("")
                        .gender("0")
                        .build()
        );
        PersonalProfileDTO clearedProfile = remoteUserService.getPersonalProfile(TENANT_A_ID, USER_ALICE_ID);
        assertThat(clearedProfile.getNickname()).isEqualTo("爱丽丝-已更新");
        assertThat(clearedProfile.getPhone()).isNull();
        assertThat(clearedProfile.getEmail()).isNull();
        assertThat(clearedProfile.getGender()).isEqualTo("0");

        // 4. uploadAvatar 守卫拦截 100004（伪造 PNG 魔数 / 非法扩展名 / 空字节）
        assertThatThrownBy(() -> remoteUserService.uploadAvatar(
                TENANT_A_ID,
                USER_ALICE_ID,
                UploadAvatarCommand.builder()
                        .originalFilename("fake.png")
                        .contentType("image/png")
                        .content("not-a-real-png-binary".getBytes(StandardCharsets.UTF_8))
                        .build()
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(CommonBizResultCode.FILE_GUARD_REJECTED.getCode());

        assertThatThrownBy(() -> remoteUserService.uploadAvatar(
                TENANT_A_ID,
                USER_ALICE_ID,
                UploadAvatarCommand.builder()
                        .originalFilename("evil.sh")
                        .contentType("text/x-shellscript")
                        .content(VALID_PNG_BYTES)
                        .build()
        ))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(CommonBizResultCode.FILE_GUARD_REJECTED.getCode());

        // 合法上传 2 次 -> 第 1 次落库并签发 inline 预览地址；第 2 次提交后触发旧对象删减回调
        AvatarUploadDTO firstAvatar = remoteUserService.uploadAvatar(
                TENANT_A_ID,
                USER_ALICE_ID,
                UploadAvatarCommand.builder()
                        .originalFilename("head1.png")
                        .contentType("image/png")
                        .content(VALID_PNG_BYTES)
                        .build()
        );
        assertThat(firstAvatar.getObjectPath()).startsWith("avatar/" + TENANT_A_ID + "/" + USER_ALICE_ID + "/")
                .endsWith(".png");
        assertThat(firstAvatar.getAvatarUrl()).contains("disposition=inline");

        AvatarUploadDTO secondAvatar = remoteUserService.uploadAvatar(
                TENANT_A_ID,
                USER_ALICE_ID,
                UploadAvatarCommand.builder()
                        .originalFilename("head2.png")
                        .contentType("image/png")
                        .content(VALID_PNG_BYTES)
                        .build()
        );
        assertThat(secondAvatar.getObjectPath()).isNotEqualTo(firstAvatar.getObjectPath());
        verify(mockStorageDriver, atLeastOnce()).delete(firstAvatar.getObjectPath());

        // 事务回滚应当通过 uploadWithRollbackDelete 回调自动清理已上传孤儿对象
        String rollbackPath = "avatar/" + TENANT_A_ID + "/" + USER_ALICE_ID + "/rollback.png";
        transactionTemplate.executeWithoutResult(tx -> {
            storageOperations.uploadWithRollbackDelete(
                    StorageUploadRequest.builder()
                            .path(rollbackPath)
                            .inputStream(new java.io.ByteArrayInputStream(VALID_PNG_BYTES))
                            .contentType("image/png")
                            .size(VALID_PNG_BYTES.length)
                            .build()
            );
            tx.setRollbackOnly();
        });
        verify(mockStorageDriver, atLeastOnce()).delete(rollbackPath);

        // 5. updatePasswordHash 回写密文与时间戳
        String newHash = PasswordUtil.hash("Ateng@Bob2026");
        LocalDateTime pwdTime = LocalDateTime.now().withNano(0);
        remoteUserService.updatePasswordHash(TENANT_A_ID, USER_BOB_ID, newHash, pwdTime);
        AdminAuthUserDTO updatedBob = remoteUserService.getAuthUserById(TENANT_A_ID, USER_BOB_ID);
        assertThat(updatedBob.getPasswordHash()).isEqualTo(newHash);
        assertThat(updatedBob.getPwdUpdateTime()).isEqualTo(pwdTime);
    }

    @Test
    @DisplayName("RemoteAuditLogService 与异步 LoginAuditEventListener 应当幂等写库并回写最近登录 IP 与时间")
    void shouldRecordLoginAuditIdempotentlyAndHandleAsyncDomainEvent() throws Exception {
        String eventId = "evt-" + UUID.randomUUID();
        LocalDateTime loginTime = LocalDateTime.now().withNano(0);
        LoginAuditCommand successCmd = LoginAuditCommand.builder()
                .eventId(eventId)
                .occurredAt(loginTime)
                .tenantId(TENANT_A_ID)
                .traceId("trace-sync-1")
                .userId(USER_BOB_ID)
                .username("bob")
                .loginType(SolidConstants.ADMIN_LOGIN_TYPE)
                .clientIp("10.20.30.40")
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0 Safari/537.36")
                .status(LogStatusEnum.SUCCESS.getCode())
                .message("登录成功")
                .build();

        remoteAuditLogService.recordLoginAudit(successCmd);
        remoteAuditLogService.recordLoginAudit(successCmd);

        List<SysLoginLog> syncLogs = TenantContextHolder.runWithTenant(TENANT_A_ID, () -> sysLoginLogMapper.selectList(
                Wrappers.<SysLoginLog>lambdaQuery().eq(SysLoginLog::getTraceId, "trace-sync-1")
        ));
        assertThat(syncLogs).hasSize(1);
        assertThat(syncLogs.getFirst().getBrowser()).isNotBlank();
        assertThat(syncLogs.getFirst().getOs()).isNotBlank();

        AdminAuthUserDTO bobAfterLogin = remoteUserService.getAuthUserById(TENANT_A_ID, USER_BOB_ID);
        assertThat(bobAfterLogin.getLoginIp()).isEqualTo("10.20.30.40");
        assertThat(bobAfterLogin.getLoginDate()).isEqualTo(loginTime);

        // 失败事件不回写用户最近登录 IP
        remoteAuditLogService.recordLoginAudit(LoginAuditCommand.builder()
                .eventId("evt-" + UUID.randomUUID())
                .occurredAt(loginTime.plusSeconds(1))
                .tenantId(TENANT_A_ID)
                .traceId("trace-fail-1")
                .userId(USER_BOB_ID)
                .username("bob")
                .loginType(SolidConstants.ADMIN_LOGIN_TYPE)
                .clientIp("99.99.99.99")
                .userAgent("curl/8.5.0")
                .status(LogStatusEnum.FAIL.getCode())
                .message("密码错误")
                .build());
        AdminAuthUserDTO bobAfterFail = remoteUserService.getAuthUserById(TENANT_A_ID, USER_BOB_ID);
        assertThat(bobAfterFail.getLoginIp()).isEqualTo("10.20.30.40");

        // 通过 DomainEventPublisher 触发异步监听落库
        String asyncTraceId = "trace-async-" + UUID.randomUUID();
        domainEventPublisher.publish(LoginAuditEvent.builder()
                .tenantId(TENANT_A_ID)
                .traceId(asyncTraceId)
                .userId(null)
                .username("unknown_user")
                .loginType(SolidConstants.ADMIN_LOGIN_TYPE)
                .clientIp("192.168.1.9")
                .userAgent("Mozilla/5.0 Chrome/131.0")
                .status(LogStatusEnum.FAIL.getCode())
                .message("用户不存在")
                .build());

        long deadline = System.currentTimeMillis() + Duration.ofSeconds(5).toMillis();
        List<SysLoginLog> asyncLogs = List.of();
        while (System.currentTimeMillis() < deadline) {
            asyncLogs = TenantContextHolder.runWithTenant(TENANT_A_ID, () -> sysLoginLogMapper.selectList(
                    Wrappers.<SysLoginLog>lambdaQuery().eq(SysLoginLog::getTraceId, asyncTraceId)
            ));
            if (!asyncLogs.isEmpty()) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(asyncLogs).hasSize(1);
        assertThat(asyncLogs.getFirst().getUsername()).isEqualTo("unknown_user");
    }

    private static List<Long> flattenMenuIds(List<RouteMenuDTO> nodes) {
        List<Long> ids = new java.util.ArrayList<>();
        for (RouteMenuDTO node : nodes) {
            ids.add(node.getId());
            if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                ids.addAll(flattenMenuIds(node.getChildren()));
            }
        }
        return ids;
    }
}

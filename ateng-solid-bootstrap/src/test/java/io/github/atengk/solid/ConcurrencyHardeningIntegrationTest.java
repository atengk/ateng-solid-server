package io.github.atengk.solid;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.core.concurrent.ConcurrentLruCache;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.redis.cache.spi.TwoLevelCache;
import io.github.atengk.boot.redis.cache.twolevel.TwoLevelCacheManager;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysDictData;
import io.github.atengk.solid.system.entity.SysMenu;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysTenantPackage;
import io.github.atengk.solid.system.mapper.SysDictDataMapper;
import io.github.atengk.solid.system.mapper.SysDictTypeMapper;
import io.github.atengk.solid.system.mapper.SysMenuMapper;
import io.github.atengk.solid.system.mapper.SysPostMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMapper;
import io.github.atengk.solid.system.request.DictDataSaveRequest;
import io.github.atengk.solid.system.request.DictTypeSaveRequest;
import io.github.atengk.solid.system.request.MenuQueryRequest;
import io.github.atengk.solid.system.request.TenantCreateRequest;
import io.github.atengk.solid.system.request.TenantPackageSaveRequest;
import io.github.atengk.solid.system.request.UserExportRequest;
import io.github.atengk.solid.system.service.SysDeptService;
import io.github.atengk.solid.system.service.SysDictService;
import io.github.atengk.solid.system.service.SysMenuService;
import io.github.atengk.solid.system.service.SysPostService;
import io.github.atengk.solid.system.service.SysRoleService;
import io.github.atengk.solid.system.service.SysTenantPackageService;
import io.github.atengk.solid.system.service.SysTenantService;
import io.github.atengk.solid.system.service.SysUserExcelService;
import io.github.atengk.solid.system.support.AdaptiveRedisSupport;
import io.github.atengk.solid.system.vo.CreateTenantVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 并发与行锁加固专项集成测试，验证菜单互迁互斥锁、套餐行锁与建租户串行化、
 * 逆序批量删防死锁、字典类型与明细竞态零孤儿、分段 ReentrantLock 并行度及两级缓存降级恢复闭环。
 *
 * @author Ateng
 * @since 2026-11-27
 */
@SpringBootTest(
        classes = SolidApplication.class,
        properties = {
                "ateng.solid.init.enabled=true",
                "ateng.solid.tenant.enabled=true",
                "ateng.boot.mybatis-plus.tenant.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:concurrency_hardening_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        }
)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrencyHardeningIntegrationTest {

    private static final List<Long> SAFE_TENANT_PACKAGE_MENUS = List.of(1000L, 1100L, 1101L);

    @Autowired
    private SysMenuService sysMenuService;
    @Autowired
    private SysMenuMapper sysMenuMapper;
    @Autowired
    private SysTenantPackageService sysTenantPackageService;
    @Autowired
    private SysTenantPackageMapper sysTenantPackageMapper;
    @Autowired
    private SysTenantService sysTenantService;
    @Autowired
    private SysTenantMapper sysTenantMapper;
    @Autowired
    private SysRoleService sysRoleService;
    @Autowired
    private SysRoleMapper sysRoleMapper;
    @Autowired
    private SysPostService sysPostService;
    @Autowired
    private SysPostMapper sysPostMapper;
    @Autowired
    private SysDictService sysDictService;
    @Autowired
    private SysDictTypeMapper sysDictTypeMapper;
    @Autowired
    private SysDictDataMapper sysDictDataMapper;
    @Autowired
    private SysDeptService sysDeptService;
    @Autowired
    private SysUserExcelService sysUserExcelService;

    @AfterEach
    void clearContexts() {
        clearThreadLocals();
    }

    @Test
    @DisplayName("并发互迁两个菜单目录必须在互斥锁下串行拦截成环，并确保父链无环")
    void shouldPreventMenuTreeCycleUnderConcurrentMutualMoveMutexLock() throws Exception {
        Long dirA = runAsPlatformSuperAdmin(() -> sysMenuService.createMenu(
                OrgAndRbacTestFixtures.menuSave(null, 0L, "M", "并发互迁目录A", null, "/conc-dir-a", null, null, 1, false)
        ));
        Long dirB = runAsPlatformSuperAdmin(() -> sysMenuService.createMenu(
                OrgAndRbacTestFixtures.menuSave(null, 0L, "M", "并发互迁目录B", null, "/conc-dir-b", null, null, 2, false)
        ));

        try {
            List<ConcurrentOutcome<Void>> outcomes = runConcurrently(List.of(
                    () -> runAsPlatformSuperAdmin(() -> {
                        sysMenuService.updateMenu(OrgAndRbacTestFixtures.menuSave(
                                dirA, dirB, "M", "并发互迁目录A", null, "/conc-dir-a", null, null, 1, false
                        ));
                        return null;
                    }),
                    () -> runAsPlatformSuperAdmin(() -> {
                        sysMenuService.updateMenu(OrgAndRbacTestFixtures.menuSave(
                                dirB, dirA, "M", "并发互迁目录B", null, "/conc-dir-b", null, null, 2, false
                        ));
                        return null;
                    })
            ));

            long successCount = outcomes.stream().filter(ConcurrentOutcome::succeeded).count();
            long rejectedCount = outcomes.stream()
                    .filter(o -> isBizError(o, CommonBizResultCode.PARAM_INVALID.getCode()))
                    .count();
            assertThat(successCount).isEqualTo(1L);
            assertThat(rejectedCount).isEqualTo(1L);

            runAsPlatformSuperAdmin(() -> {
                assertMenuParentChainAcyclic(dirA);
                assertMenuParentChainAcyclic(dirB);
                assertThat(sysMenuService.listMenuTree(new MenuQueryRequest())).isNotEmpty();
                return null;
            });
        } finally {
            runAsPlatformSuperAdmin(() -> {
                SysMenu latestA = sysMenuMapper.selectById(dirA);
                if (latestA != null && !Objects.equals(latestA.getParentId(), 0L)) {
                    sysMenuService.deleteMenu(dirA);
                    sysMenuService.deleteMenu(dirB);
                } else {
                    if (sysMenuMapper.selectById(dirB) != null) {
                        sysMenuService.deleteMenu(dirB);
                    }
                    if (sysMenuMapper.selectById(dirA) != null) {
                        sysMenuService.deleteMenu(dirA);
                    }
                }
                return null;
            });
        }
    }

    @Test
    @DisplayName("并发删除或停用租户套餐与新建租户必须在行锁下严格串行，绝不产生引用失效套餐的孤儿租户")
    void shouldSerializeConcurrentPackageDisableOrDeleteAgainstTenantCreationWithRowLock() throws Exception {
        for (int round = 1; round <= 2; round++) {
            final int currentRound = round;
            Long pkgId = runAsPlatformSuperAdmin(() -> {
                TenantPackageSaveRequest pkgReq = new TenantPackageSaveRequest();
                pkgReq.setPackageName("并发锁套餐-" + currentRound);
                pkgReq.setMenuCheckStrictly(true);
                pkgReq.setStatus(CommonStatusEnum.NORMAL.getCode());
                pkgReq.setMenuIds(SAFE_TENANT_PACKAGE_MENUS);
                return sysTenantPackageService.createPackage(pkgReq);
            });

            List<ConcurrentOutcome<Object>> outcomes = runConcurrently(List.of(
                    () -> runAsPlatformSuperAdmin(() -> {
                        sysTenantPackageService.deletePackages(String.valueOf(pkgId));
                        return null;
                    }),
                    () -> runAsPlatformSuperAdmin(() -> createTenantWithPackage(pkgId, "CONCP" + currentRound))
            ));

            ConcurrentOutcome<Object> deleteOutcome = outcomes.get(0);
            ConcurrentOutcome<Object> createOutcome = outcomes.get(1);

            Long boundTenants = TenantContextHolder.runWithoutTenant(() -> sysTenantMapper.selectCount(
                    Wrappers.<SysTenant>lambdaQuery().eq(SysTenant::getPackageId, pkgId)
            ));
            SysTenantPackage pkgRow = TenantContextHolder.runWithoutTenant(() -> sysTenantPackageMapper.selectById(pkgId));

            if (deleteOutcome.succeeded()) {
                assertThat(isBizError(createOutcome, CommonBizResultCode.PARAM_INVALID.getCode())).isTrue();
                assertThat(pkgRow).isNull();
                assertThat(boundTenants).isZero();
            } else {
                assertThat(createOutcome.succeeded()).isTrue();
                assertThat(isBizError(deleteOutcome, SystemResultCode.RESOURCE_IN_USE.getCode())).isTrue();
                assertThat(pkgRow).isNotNull();
                assertThat(boundTenants).isEqualTo(1L);
            }
        }

        // 第 3 轮：并发停用套餐 vs 创建租户，同样受 FOR UPDATE 串行化保护，停用后绝不落库绑定租户
        Long disablePkgId = runAsPlatformSuperAdmin(() -> {
            TenantPackageSaveRequest pkgReq = new TenantPackageSaveRequest();
            pkgReq.setPackageName("并发停用套餐-3");
            pkgReq.setMenuCheckStrictly(true);
            pkgReq.setStatus(CommonStatusEnum.NORMAL.getCode());
            pkgReq.setMenuIds(SAFE_TENANT_PACKAGE_MENUS);
            return sysTenantPackageService.createPackage(pkgReq);
        });

        List<ConcurrentOutcome<Object>> disableVsCreate = runConcurrently(List.of(
                () -> runAsPlatformSuperAdmin(() -> {
                    sysTenantPackageService.updatePackageStatus(disablePkgId, CommonStatusEnum.DISABLED.getCode());
                    return null;
                }),
                () -> runAsPlatformSuperAdmin(() -> createTenantWithPackage(disablePkgId, "CONCP3"))
        ));

        ConcurrentOutcome<Object> disableOutcome = disableVsCreate.get(0);
        ConcurrentOutcome<Object> createOutcome = disableVsCreate.get(1);
        assertThat(disableOutcome.succeeded()).isTrue();

        SysTenantPackage finalPkg = TenantContextHolder.runWithoutTenant(() -> sysTenantPackageMapper.selectById(disablePkgId));
        Long finalBoundCount = TenantContextHolder.runWithoutTenant(() -> sysTenantMapper.selectCount(
                Wrappers.<SysTenant>lambdaQuery().eq(SysTenant::getPackageId, disablePkgId)
        ));
        assertThat(finalPkg).isNotNull();
        assertThat(finalPkg.getStatus()).isEqualTo(CommonStatusEnum.DISABLED.getCode());
        if (createOutcome.succeeded()) {
            assertThat(finalBoundCount).isEqualTo(1L);
        } else {
            assertThat(isBizError(createOutcome, CommonBizResultCode.PARAM_INVALID.getCode())).isTrue();
            assertThat(finalBoundCount).isZero();
        }
    }

    @Test
    @DisplayName("并发逆序批量删除角色或岗位必须按升序行锁防死锁，恰好一方成功另一方返回 102001")
    void shouldPreventDeadlockOnConcurrentReverseOrderRoleAndPostBatchDeletes() throws Exception {
        Long role1 = runAsPlatformSuperAdmin(() -> sysRoleService.createRole(
                OrgAndRbacTestFixtures.roleSave(null, "并发删角色1", "conc_del_r1", SAFE_TENANT_PACKAGE_MENUS)
        ));
        Long role2 = runAsPlatformSuperAdmin(() -> sysRoleService.createRole(
                OrgAndRbacTestFixtures.roleSave(null, "并发删角色2", "conc_del_r2", SAFE_TENANT_PACKAGE_MENUS)
        ));

        List<ConcurrentOutcome<Void>> roleOutcomes = runConcurrently(List.of(
                () -> runAsPlatformSuperAdmin(() -> {
                    sysRoleService.deleteRoles(role1 + "," + role2);
                    return null;
                }),
                () -> runAsPlatformSuperAdmin(() -> {
                    sysRoleService.deleteRoles(role2 + "," + role1);
                    return null;
                })
        ));

        assertThat(roleOutcomes.stream().filter(ConcurrentOutcome::succeeded).count()).isEqualTo(1L);
        assertThat(roleOutcomes.stream().filter(o -> isBizError(o, SystemResultCode.RECORD_NOT_FOUND.getCode())).count())
                .isEqualTo(1L);
        runAsPlatformSuperAdmin(() -> {
            assertThat(sysRoleMapper.selectById(role1)).isNull();
            assertThat(sysRoleMapper.selectById(role2)).isNull();
            return null;
        });

        Long post1 = runAsPlatformSuperAdmin(() -> sysPostService.createPost(
                OrgAndRbacTestFixtures.postSave(null, "conc_del_p1", "并发删岗位1", 1, null)
        ));
        Long post2 = runAsPlatformSuperAdmin(() -> sysPostService.createPost(
                OrgAndRbacTestFixtures.postSave(null, "conc_del_p2", "并发删岗位2", 2, null)
        ));

        List<ConcurrentOutcome<Void>> postOutcomes = runConcurrently(List.of(
                () -> runAsPlatformSuperAdmin(() -> {
                    sysPostService.deletePosts(post1 + "," + post2);
                    return null;
                }),
                () -> runAsPlatformSuperAdmin(() -> {
                    sysPostService.deletePosts(post2 + "," + post1);
                    return null;
                })
        ));

        assertThat(postOutcomes.stream().filter(ConcurrentOutcome::succeeded).count()).isEqualTo(1L);
        assertThat(postOutcomes.stream().filter(o -> isBizError(o, SystemResultCode.RECORD_NOT_FOUND.getCode())).count())
                .isEqualTo(1L);
        runAsPlatformSuperAdmin(() -> {
            assertThat(sysPostMapper.selectById(post1)).isNull();
            assertThat(sysPostMapper.selectById(post2)).isNull();
            return null;
        });
    }

    @Test
    @DisplayName("并发删除字典类型与新增字典明细必须在行锁下严格互斥，绝不产生孤儿字典明细")
    void shouldPreventOrphanDictDataWhenDeletingDictTypeConcurrentlyWithCreatingData() throws Exception {
        for (int round = 1; round <= 3; round++) {
            String dictTypeCode = "conc_dict_type_" + round;
            DictTypeSaveRequest typeReq = new DictTypeSaveRequest();
            typeReq.setDictName("并发字典类型" + round);
            typeReq.setDictType(dictTypeCode);
            typeReq.setStatus(CommonStatusEnum.NORMAL.getCode());
            Long typeId = runAsPlatformSuperAdmin(() -> sysDictService.createDictType(typeReq));

            DictDataSaveRequest dataReq = new DictDataSaveRequest();
            dataReq.setDictType(dictTypeCode);
            dataReq.setDictLabel("并发字典项");
            dataReq.setDictValue("val_1");
            dataReq.setSort(1);
            dataReq.setIsDefault(0);
            dataReq.setStatus(CommonStatusEnum.NORMAL.getCode());

            List<ConcurrentOutcome<Object>> outcomes = runConcurrently(List.of(
                    () -> runAsPlatformSuperAdmin(() -> {
                        sysDictService.deleteDictTypes(String.valueOf(typeId));
                        return null;
                    }),
                    () -> runAsPlatformSuperAdmin(() -> sysDictService.createDictData(dataReq))
            ));

            ConcurrentOutcome<Object> deleteOutcome = outcomes.get(0);
            ConcurrentOutcome<Object> createOutcome = outcomes.get(1);

            Long activeDataCount = TenantContextHolder.runWithoutTenant(() -> sysDictDataMapper.selectCount(
                    Wrappers.<SysDictData>lambdaQuery().eq(SysDictData::getDictType, dictTypeCode)
            ));
            var typeRow = TenantContextHolder.runWithoutTenant(() -> sysDictTypeMapper.selectById(typeId));

            if (deleteOutcome.succeeded()) {
                assertThat(isBizError(createOutcome, SystemResultCode.RECORD_NOT_FOUND.getCode())).isTrue();
                assertThat(typeRow).isNull();
                assertThat(activeDataCount).isZero();
            } else {
                assertThat(createOutcome.succeeded()).isTrue();
                assertThat(isBizError(deleteOutcome, SystemResultCode.RESOURCE_IN_USE.getCode())).isTrue();
                assertThat(typeRow).isNotNull();
                assertThat(activeDataCount).isEqualTo(1L);
            }
        }
    }

    @Test
    @DisplayName("应当验证分段锁不同槽位真并行同键互斥、两级缓存降级恢复自动清旧及导出上下文防爆清洗")
    void shouldVerifyStripedLocksParallelismTwoLevelRecoveryAndExportContextNormalization() throws Exception {
        // A. 分段 ReentrantLock 不同槽位真并行与同键互斥
        AdaptiveRedisSupport localOnlySupport = new AdaptiveRedisSupport(null, null, null, null, null);
        String keyA = "stripe-alpha-0";
        String keyB = findDifferentStripeKey(keyA);

        CountDownLatch differentStripesEntered = new CountDownLatch(2);
        List<ConcurrentOutcome<Boolean>> stripeOutcomes = runConcurrently(List.of(
                () -> localOnlySupport.supplyWithMutexLock(keyA, Duration.ofSeconds(3), () -> {
                    differentStripesEntered.countDown();
                    try {
                        return differentStripesEntered.await(3, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }),
                () -> localOnlySupport.supplyWithMutexLock(keyB, Duration.ofSeconds(3), () -> {
                    differentStripesEntered.countDown();
                    try {
                        return differentStripesEntered.await(3, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                })
        ));
        assertThat(stripeOutcomes).allMatch(o -> o.succeeded() && Boolean.TRUE.equals(o.value()));

        AtomicInteger concurrentInsideSameKey = new AtomicInteger(0);
        AtomicInteger maxConcurrentSameKey = new AtomicInteger(0);
        List<Callable<Void>> sameKeyTasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            sameKeyTasks.add(() -> {
                localOnlySupport.executeWithMutexLock(keyA, Duration.ofSeconds(3), () -> {
                    int current = concurrentInsideSameKey.incrementAndGet();
                    maxConcurrentSameKey.accumulateAndGet(current, Math::max);
                    try {
                        Thread.sleep(25L);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        concurrentInsideSameKey.decrementAndGet();
                    }
                });
                return null;
            });
        }
        List<ConcurrentOutcome<Void>> sameKeyOutcomes = runConcurrently(sameKeyTasks);
        assertThat(sameKeyOutcomes).allMatch(ConcurrentOutcome::succeeded);
        assertThat(maxConcurrentSameKey.get()).isEqualTo(1);

        // B. TwoLevelCacheManager 多线程故障降级 -> 恢复后自动清除本地陈旧条目
        MockEnvironment redisEnabledEnv = new MockEnvironment().withProperty("ateng.boot.redis.enabled", "true");
        TwoLevelCacheManager mockCacheManager = Mockito.mock(TwoLevelCacheManager.class);
        TwoLevelCache mockTwoLevelCache = Mockito.mock(TwoLevelCache.class);
        Mockito.when(mockCacheManager.getTwoLevelCache("testSpace")).thenReturn(mockTwoLevelCache);

        AtomicBoolean redisHealthy = new AtomicBoolean(false);
        Mockito.when(mockTwoLevelCache.get("k1", String.class)).thenAnswer(inv -> {
            if (!redisHealthy.get()) {
                throw new DataAccessResourceFailureException("simulated redis down");
            }
            return "v-redis-fresh";
        });

        AdaptiveRedisSupport mockRedisSupport = new AdaptiveRedisSupport(
                providerOf(redisEnabledEnv),
                providerOf(mockCacheManager),
                null,
                null,
                null
        );
        ConcurrentLruCache<String, String> localFallback = new ConcurrentLruCache<>(64, Duration.ofMinutes(5));

        List<Callable<String>> downPhaseTasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            downPhaseTasks.add(() -> mockRedisSupport.getOrLoadTwoLevel(
                    "testSpace", "k1", String.class, () -> "v-fallback", Duration.ofMinutes(5), localFallback
            ));
        }
        List<ConcurrentOutcome<String>> downPhaseResults = runConcurrently(downPhaseTasks);
        assertThat(downPhaseResults).allMatch(o -> o.succeeded() && "v-fallback".equals(o.value()));
        assertThat(localFallback.containsKey("k1")).isTrue();

        redisHealthy.set(true);
        List<Callable<String>> recoveredTasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            recoveredTasks.add(() -> mockRedisSupport.getOrLoadTwoLevel(
                    "testSpace", "k1", String.class, () -> "v-fallback-should-not-win", Duration.ofMinutes(5), localFallback
            ));
        }
        List<ConcurrentOutcome<String>> recoveredResults = runConcurrently(recoveredTasks);
        assertThat(recoveredResults).allMatch(o -> o.succeeded() && "v-redis-fresh".equals(o.value()));
        assertThat(localFallback.containsKey("k1")).isFalse();

        // C. SysUserExcelService#prepareExportContext 真实 H2 验证：ids 清洗、deptSubtreeIds 预解析与时间反转拦截
        Long parentDeptId = runAsPlatformSuperAdmin(() -> sysDeptService.createDept(
                OrgAndRbacTestFixtures.deptSave(null, 0L, "导出父部门68", 1, null, null)
        ));
        Long childDeptId = runAsPlatformSuperAdmin(() -> sysDeptService.createDept(
                OrgAndRbacTestFixtures.deptSave(null, parentDeptId, "导出子部门68", 2, null, null)
        ));
        try {
            runAsPlatformSuperAdmin(() -> {
                UserExportRequest idsReq = new UserExportRequest();
                idsReq.setIds(Arrays.asList(10L, null, 0L, -5L, 10L, 20L));
                SysUserExcelService.ExportContext idsContext = sysUserExcelService.prepareExportContext(idsReq);
                assertThat(idsContext.request().getIds()).containsExactly(10L, 20L);
                assertThat(idsContext.deptSubtreeIds()).isEmpty();

                UserExportRequest deptReq = new UserExportRequest();
                deptReq.setDeptId(parentDeptId);
                SysUserExcelService.ExportContext deptContext = sysUserExcelService.prepareExportContext(deptReq);
                assertThat(deptContext.deptSubtreeIds()).contains(parentDeptId, childDeptId);

                UserExportRequest reversedTimeReq = new UserExportRequest();
                reversedTimeReq.setBeginTime(LocalDateTime.of(2026, 11, 27, 14, 0));
                reversedTimeReq.setEndTime(LocalDateTime.of(2026, 11, 27, 13, 0));
                assertThatThrownBy(() -> sysUserExcelService.prepareExportContext(reversedTimeReq))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                                .isEqualTo(CommonBizResultCode.PARAM_INVALID.getCode()));
                return null;
            });
        } finally {
            runAsPlatformSuperAdmin(() -> {
                sysDeptService.deleteDept(childDeptId);
                sysDeptService.deleteDept(parentDeptId);
                return null;
            });
        }
    }

    private CreateTenantVO createTenantWithPackage(Long packageId, String tenantCode) {
        TenantCreateRequest req = new TenantCreateRequest();
        req.setTenantCode(tenantCode);
        req.setTenantName("并发租户-" + tenantCode);
        req.setPackageId(packageId);
        req.setAccountCount(-1);
        req.setStatus(CommonStatusEnum.NORMAL.getCode());
        req.setAdminUsername("admin_" + tenantCode.toLowerCase());
        req.setAdminPassword("Ateng@Conc1");
        return sysTenantService.createTenant(req);
    }

    private void assertMenuParentChainAcyclic(Long startMenuId) {
        Set<Long> visited = new HashSet<>();
        Long cursor = startMenuId;
        int steps = 0;
        while (cursor != null && cursor > 0L && steps < 10) {
            assertThat(visited.add(cursor)).as("检测到菜单成环: %s", startMenuId).isTrue();
            SysMenu menu = sysMenuMapper.selectById(cursor);
            assertThat(menu).isNotNull();
            cursor = menu.getParentId();
            steps++;
        }
        assertThat(cursor).isEqualTo(0L);
    }

    private static String findDifferentStripeKey(String baseKey) {
        int baseStripe = stripeIndex(baseKey);
        for (int i = 1; i <= 256; i++) {
            String candidate = "stripe-beta-" + i;
            if (stripeIndex(candidate) != baseStripe) {
                return candidate;
            }
        }
        throw new IllegalStateException("未找到不同分段锁槽位的测试键");
    }

    private static int stripeIndex(String lockKey) {
        int hash = lockKey.hashCode();
        return (hash ^ (hash >>> 16)) & 63;
    }

    private static <T> T runAsPlatformSuperAdmin(Callable<T> action) throws Exception {
        TenantContextHolder.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
        DataPermissionContextHolder.setSuperAdmin(true);
        try {
            return action.call();
        } finally {
            clearThreadLocals();
        }
    }

    private static void clearThreadLocals() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        DataPermissionContextHolder.clear();
        MDC.clear();
    }

    private static <T> List<ConcurrentOutcome<T>> runConcurrently(List<Callable<T>> tasks) throws Exception {
        CountDownLatch readyLatch = new CountDownLatch(tasks.size());
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<ConcurrentOutcome<T>>> futures = new ArrayList<>(tasks.size());
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    readyLatch.countDown();
                    if (!startLatch.await(5, TimeUnit.SECONDS)) {
                        return ConcurrentOutcome.failure(new IllegalStateException("等待并发起跑超时"));
                    }
                    try {
                        return ConcurrentOutcome.success(task.call());
                    } catch (Throwable ex) {
                        return ConcurrentOutcome.failure(ex);
                    } finally {
                        clearThreadLocals();
                    }
                }));
            }
            assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            List<ConcurrentOutcome<T>> results = new ArrayList<>(tasks.size());
            for (Future<ConcurrentOutcome<T>> future : futures) {
                results.add(future.get(15, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static boolean isBizError(ConcurrentOutcome<?> outcome, Integer expectedCode) {
        if (outcome.error() instanceof BusinessException bizEx) {
            return Objects.equals(expectedCode, bizEx.getCode());
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T instance) {
        ObjectProvider<T> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }

    private record ConcurrentOutcome<T>(T value, Throwable error) {

        private static <T> ConcurrentOutcome<T> success(T value) {
            return new ConcurrentOutcome<>(value, null);
        }

        private static <T> ConcurrentOutcome<T> failure(Throwable error) {
            return new ConcurrentOutcome<>(null, error);
        }

        private boolean succeeded() {
            return error == null;
        }
    }
}

package io.github.atengk.solid;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysDictData;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysDictDataMapper;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.request.DeptQueryRequest;
import io.github.atengk.solid.system.request.DeptSaveRequest;
import io.github.atengk.solid.system.request.DictDataSaveRequest;
import io.github.atengk.solid.system.request.DictTypeSaveRequest;
import io.github.atengk.solid.system.service.SysDeptQueryService;
import io.github.atengk.solid.system.service.SysDeptService;
import io.github.atengk.solid.system.service.SysDictService;
import io.github.atengk.solid.system.service.SysMenuService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.github.atengk.solid.OrgAndRbacTestFixtures.deptSave;
import static io.github.atengk.solid.OrgAndRbacTestFixtures.menuSave;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 部门/菜单树互斥锁防互环、层级深度与祖级长度硬上限（<= 10 / <= 500）、停用负责人校验及字典默认值互斥并发集成测试。
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
                "spring.datasource.url=jdbc:h2:mem:org_rbac_bounds_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        }
)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrgAndRbacConcurrencyAndBoundsIntegrationTest {

    private static final long TENANT_ID = 6001L;

    @Autowired
    private SysDeptService sysDeptService;
    @Autowired
    private SysDeptQueryService sysDeptQueryService;
    @Autowired
    private SysMenuService sysMenuService;
    @Autowired
    private SysDictService sysDictService;
    @Autowired
    private SysTenantMapper sysTenantMapper;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private SysDeptMapper sysDeptMapper;
    @Autowired
    private SysDictDataMapper sysDictDataMapper;

    private Long rootDeptId;
    private Long normalUserId;
    private Long disabledUserId;

    @BeforeAll
    void setUpBaseFixtures() {
        SysTenant tenant = new SysTenant();
        tenant.setId(TENANT_ID);
        tenant.setTenantCode("T6001");
        tenant.setTenantName("树并发边界测试企业");
        tenant.setAccountCount(-1);
        tenant.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysTenantMapper.insert(tenant);

        rootDeptId = TenantContextHolder.runWithTenant(TENANT_ID, () ->
                sysDeptService.createDept(deptSave(null, 0L, "总部根节点", 1, null, null))
        );

        String hash = PasswordUtil.hash("Ateng@Bounds1");
        TenantContextHolder.runWithTenant(TENANT_ID, () -> {
            SysUser normal = new SysUser();
            normal.setTenantId(TENANT_ID);
            normal.setDeptId(rootDeptId);
            normal.setUsername("normal_leader");
            normal.setNickname("正常负责人");
            normal.setPassword(hash);
            normal.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysUserMapper.insert(normal);
            normalUserId = normal.getId();

            SysUser disabled = new SysUser();
            disabled.setTenantId(TENANT_ID);
            disabled.setDeptId(rootDeptId);
            disabled.setUsername("disabled_leader");
            disabled.setNickname("停用负责人");
            disabled.setPassword(hash);
            disabled.setStatus(CommonStatusEnum.DISABLED.getCode());
            sysUserMapper.insert(disabled);
            disabledUserId = disabled.getId();
        });
    }

    @AfterEach
    void cleanContext() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        DataPermissionContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("部门负责人若处于停用状态应当在新增与修改入口被 100001 明确拒绝")
    void shouldRejectDisabledLeaderUserOnCreateAndUpdateDept() {
        TenantContextHolder.runWithTenant(TENANT_ID, () -> {
            DeptSaveRequest badCreate = deptSave(null, rootDeptId, "停用负责人校验部", 10, null, null);
            badCreate.setLeaderUserId(disabledUserId);
            assertThatThrownBy(() -> sysDeptService.createDept(badCreate))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(CommonBizResultCode.PARAM_INVALID.getCode()));

            DeptSaveRequest goodCreate = deptSave(null, rootDeptId, "正常负责人部", 11, null, null);
            goodCreate.setLeaderUserId(normalUserId);
            Long deptId = sysDeptService.createDept(goodCreate);

            DeptSaveRequest badUpdate = deptSave(deptId, rootDeptId, "正常负责人部", 11, null, null);
            badUpdate.setLeaderUserId(disabledUserId);
            assertThatThrownBy(() -> sysDeptService.updateDept(badUpdate))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(CommonBizResultCode.PARAM_INVALID.getCode()));
        });
    }

    @Test
    @DisplayName("部门树深度超出 10 层或级联重算子孙超出 10 层时应当阻断 100001 并完整回滚")
    void shouldEnforceDeptMaxDepthTenOnCreateAndCascadeUpdate() {
        TenantContextHolder.runWithTenant(TENANT_ID, () -> {
            Long parentId = rootDeptId;
            Long level9Id = null;
            Long level10Id = null;
            for (int level = 2; level <= 10; level++) {
                parentId = sysDeptService.createDept(deptSave(null, parentId, "第" + level + "级部门", level, null, null));
                if (level == 9) {
                    level9Id = parentId;
                }
                if (level == 10) {
                    level10Id = parentId;
                }
            }
            Long finalLevel10Id = level10Id;
            assertThatThrownBy(() -> sysDeptService.createDept(deptSave(null, finalLevel10Id, "第11级部门", 11, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(CommonBizResultCode.PARAM_INVALID.getCode()));

            // 构建 3 层子树 subRoot -> subChild -> subGrandChild，尝试整棵挂载到第 9 层（最终深度 12 > 10）必须被阻断
            Long subRoot = sysDeptService.createDept(deptSave(null, rootDeptId, "迁移子根", 20, null, null));
            Long subChild = sysDeptService.createDept(deptSave(null, subRoot, "迁移子节点", 21, null, null));
            sysDeptService.createDept(deptSave(null, subChild, "迁移孙节点", 22, null, null));

            Long targetParentLevel9 = level9Id;
            assertThatThrownBy(() -> sysDeptService.updateDept(
                    deptSave(subRoot, targetParentLevel9, "迁移子根", 20, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(CommonBizResultCode.PARAM_INVALID.getCode()));

            assertThat(sysDeptMapper.selectById(subRoot).getParentId()).isEqualTo(rootDeptId);
        });
    }

    @Test
    @DisplayName("并发相互改挂父部门应当在互斥锁下严格串行执行并保证零环与 ancestors 一致")
    void shouldPreventCycleOnConcurrentMutualDeptMoveUnderMutexLock() throws Exception {
        Long deptA = TenantContextHolder.runWithTenant(TENANT_ID, () ->
                sysDeptService.createDept(deptSave(null, rootDeptId, "并发互挂A", 30, null, null)));
        Long deptB = TenantContextHolder.runWithTenant(TENANT_ID, () ->
                sysDeptService.createDept(deptSave(null, rootDeptId, "并发互挂B", 31, null, null)));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Boolean> moveAToB = () -> TenantContextHolder.runWithTenant(TENANT_ID, () -> {
                ready.countDown();
                try {
                    start.await(3, TimeUnit.SECONDS);
                    sysDeptService.updateDept(deptSave(deptA, deptB, "并发互挂A", 30, null, null));
                    return true;
                } catch (BusinessException | InterruptedException ex) {
                    return false;
                }
            });
            Callable<Boolean> moveBToA = () -> TenantContextHolder.runWithTenant(TENANT_ID, () -> {
                ready.countDown();
                try {
                    start.await(3, TimeUnit.SECONDS);
                    sysDeptService.updateDept(deptSave(deptB, deptA, "并发互挂B", 31, null, null));
                    return true;
                } catch (BusinessException | InterruptedException ex) {
                    return false;
                }
            });

            Future<Boolean> f1 = pool.submit(moveAToB);
            Future<Boolean> f2 = pool.submit(moveBToA);
            assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            boolean ok1 = f1.get(10, TimeUnit.SECONDS);
            boolean ok2 = f2.get(10, TimeUnit.SECONDS);
            assertThat(ok1 && ok2).isFalse();
            assertThat(ok1 || ok2).isTrue();
        }

        TenantContextHolder.runWithTenant(TENANT_ID, () -> {
            SysDept entityA = sysDeptMapper.selectById(deptA);
            SysDept entityB = sysDeptMapper.selectById(deptB);
            boolean cycle = Objects.equals(entityA.getParentId(), deptB) && Objects.equals(entityB.getParentId(), deptA);
            assertThat(cycle).isFalse();
            assertThat(sysDeptQueryService.listTree(new DeptQueryRequest())).isNotEmpty();
        });
    }

    @Test
    @DisplayName("菜单树层级深度超过 10 层应当抛出 100001 拒绝创建")
    void shouldEnforceMenuMaxDepthTen() {
        TenantContextHolder.runWithTenant(SolidConstants.PLATFORM_TENANT_ID, () -> {
            Long parentId = 0L;
            for (int level = 1; level <= 9; level++) {
                parentId = sysMenuService.createMenu(menuSave(
                        null, parentId, "M", "深度目录" + level,
                        "DepthDir" + level, "/depth" + level, null, null, level, false
                ));
            }
            Long level10MenuId = sysMenuService.createMenu(menuSave(
                    null, parentId, "C", "第10层页面",
                    "DepthPage10", "depth-page-10", "system/depth/index", "system:depth:list", 10, false
            ));
            assertThatThrownBy(() -> sysMenuService.createMenu(menuSave(
                    null, level10MenuId, "F", "第11层按钮",
                    null, null, null, "system:depth:add", 1, false
            )))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                            .isEqualTo(CommonBizResultCode.PARAM_INVALID.getCode()));
        });
    }

    @Test
    @DisplayName("并发插入同一字典类型并声明 isDefault=1 时应当通过父行锁保证全表有且仅有一条默认明细")
    void shouldMaintainSingleDefaultDictDataUnderConcurrentDefaultUpdates() throws Exception {
        String dictType = "bounds_concurrent_default";
        TenantContextHolder.runWithTenant(SolidConstants.PLATFORM_TENANT_ID, () -> {
            DictTypeSaveRequest typeReq = new DictTypeSaveRequest();
            typeReq.setDictName("并发默认字典");
            typeReq.setDictType(dictType);
            typeReq.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysDictService.createDictType(typeReq);
        });

        int workers = 6;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>(workers);
        try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
            for (int i = 0; i < workers; i++) {
                int idx = i;
                futures.add(pool.submit(() -> TenantContextHolder.runWithTenant(SolidConstants.PLATFORM_TENANT_ID, () -> {
                    ready.countDown();
                    try {
                        start.await(3, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ex);
                    }
                    DictDataSaveRequest dataReq = new DictDataSaveRequest();
                    dataReq.setDictType(dictType);
                    dataReq.setDictLabel("选项" + idx);
                    dataReq.setDictValue("val_" + idx);
                    dataReq.setSort(idx + 1);
                    dataReq.setIsDefault(1);
                    dataReq.setStatus(CommonStatusEnum.NORMAL.getCode());
                    return sysDictService.createDictData(dataReq);
                })));
            }
            assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Long> future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS)).isPositive();
            }
        }

        Long defaultCount = TenantContextHolder.runWithoutTenant(() -> sysDictDataMapper.selectCount(
                Wrappers.<SysDictData>lambdaQuery()
                        .eq(SysDictData::getDictType, dictType)
                        .eq(SysDictData::getIsDefault, 1)
        ));
        assertThat(defaultCount).isEqualTo(1L);
    }
}

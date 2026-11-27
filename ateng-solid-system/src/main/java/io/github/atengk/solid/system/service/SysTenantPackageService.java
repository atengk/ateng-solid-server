package io.github.atengk.solid.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.mybatisplus.util.BatchUtil;
import io.github.atengk.boot.mybatisplus.util.PessimisticLockUtil;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysTenantPackage;
import io.github.atengk.solid.system.entity.SysTenantPackageMenu;
import io.github.atengk.solid.system.mapper.GroupCountRow;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMenuMapper;
import io.github.atengk.solid.system.request.TenantPackageSaveRequest;
import io.github.atengk.solid.system.support.SystemContextSupport;
import io.github.atengk.solid.system.support.TenantPackageMenuSyncSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 租户套餐写操作核心服务（校验 {@code platform_only} 禁分配、跨租户 {@code sys_role_menu} 原子裁剪与引用防误删）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Service
@RequiredArgsConstructor
public class SysTenantPackageService {

    private static final int MIN_PACKAGE_NAME_LEN = 2;
    private static final int MAX_PACKAGE_NAME_LEN = 50;

    private final SystemContextSupport contextSupport;
    private final SysTenantPackageMapper sysTenantPackageMapper;
    private final SysTenantPackageMenuMapper sysTenantPackageMenuMapper;
    private final SysTenantMapper sysTenantMapper;
    private final TenantPackageMenuSyncSupport menuSyncSupport;

    /**
     * 新增租户套餐并写入菜单关联。
     *
     * @param request 套餐保存请求
     * @return 新建套餐主键
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createPackage(TenantPackageSaveRequest request) {
        contextSupport.requireMultiTenantPlatformAccess();
        NormalizedPackagePayload payload = validateAndNormalize(null, request);
        List<Long> menuIds = menuSyncSupport.validateAndNormalizePackageMenuIds(request.getMenuIds());

        return TenantContextHolder.runWithoutTenant(() -> {
            assertUniquePackageName(null, payload.packageName());

            SysTenantPackage entity = new SysTenantPackage();
            entity.setPackageName(payload.packageName());
            entity.setMenuCheckStrictly(payload.menuCheckStrictly());
            entity.setStatus(payload.status());
            entity.setRemark(payload.remark());
            try {
                sysTenantPackageMapper.insert(entity);
            } catch (DuplicateKeyException ex) {
                throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "租户套餐名称已存在", ex);
            }
            replacePackageMenus(entity.getId(), menuIds);
            return entity.getId();
        });
    }

    /**
     * 修改租户套餐并同步绑定菜单；当缩减菜单且存在绑定租户时在事务内原子裁剪各租户越界角色菜单权限。
     *
     * @param request 套餐修改请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void updatePackage(TenantPackageSaveRequest request) {
        contextSupport.requireMultiTenantPlatformAccess();
        if (request == null || request.getId() == null || request.getId() <= 0L) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "修改套餐必须传入有效主键");
        }

        TenantContextHolder.runWithoutTenant(() -> {
            SysTenantPackage existing = menuSyncSupport.requirePackageExistsForUpdate(request.getId());
            NormalizedPackagePayload payload = validateAndNormalize(existing, request);
            List<Long> newMenuIds = menuSyncSupport.validateAndNormalizePackageMenuIds(request.getMenuIds());

            assertUniquePackageName(existing.getId(), payload.packageName());
            List<Long> oldMenuIds = menuSyncSupport.loadBoundMenuIds(existing.getId());

            LambdaUpdateWrapper<SysTenantPackage> wrapper = Wrappers.<SysTenantPackage>lambdaUpdate()
                    .eq(SysTenantPackage::getId, existing.getId())
                    .set(SysTenantPackage::getPackageName, payload.packageName())
                    .set(SysTenantPackage::getMenuCheckStrictly, payload.menuCheckStrictly())
                    .set(SysTenantPackage::getStatus, payload.status())
                    .set(SysTenantPackage::getRemark, payload.remark());
            try {
                sysTenantPackageMapper.update(new SysTenantPackage(), wrapper);
            } catch (DuplicateKeyException ex) {
                throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "租户套餐名称已存在", ex);
            }

            replacePackageMenus(existing.getId(), newMenuIds);

            Set<Long> newMenuSet = new HashSet<>(newMenuIds);
            boolean menuShrunk = oldMenuIds.stream().anyMatch(menuId -> !newMenuSet.contains(menuId));
            if (menuShrunk) {
                List<Long> boundTenantIds = loadBoundTenantIds(existing.getId());
                if (!boundTenantIds.isEmpty()) {
                    menuSyncSupport.pruneRoleMenusForTenants(boundTenantIds, newMenuSet);
                }
            }
        });
    }

    /**
     * 切换租户套餐启停状态（停用不影响存量已绑定租户继续运行，仅禁用后续新选）。
     *
     * @param id     套餐主键
     * @param status 目标状态（0=正常，1=停用）
     */
    @Transactional(rollbackFor = Exception.class)
    public void updatePackageStatus(Long id, Integer status) {
        contextSupport.requireMultiTenantPlatformAccess();
        Integer validStatus = SystemContextSupport.requireValidStatus(status);

        TenantContextHolder.runWithoutTenant(() -> {
            SysTenantPackage existing = menuSyncSupport.requirePackageExistsForUpdate(id);
            sysTenantPackageMapper.update(
                    new SysTenantPackage(),
                    Wrappers.<SysTenantPackage>lambdaUpdate()
                            .eq(SysTenantPackage::getId, existing.getId())
                            .set(SysTenantPackage::getStatus, validStatus)
            );
        });
    }

    /**
     * 批量删除未被租户引用的租户套餐；按升序加 {@code FOR UPDATE} 行锁校验引用，存在任一租户绑定阻断抛出 {@code 102005}。
     *
     * @param ids 逗号分隔的套餐主键列表串
     */
    @Transactional(rollbackFor = Exception.class)
    public void deletePackages(String ids) {
        contextSupport.requireMultiTenantPlatformAccess();
        List<Long> idList = SystemContextSupport.parsePathIds(ids);
        List<Long> sortedIds = idList.stream().sorted().toList();

        TenantContextHolder.runWithoutTenant(() -> {
            LambdaQueryWrapper<SysTenantPackage> lockQuery = Wrappers.<SysTenantPackage>lambdaQuery()
                    .in(SysTenantPackage::getId, sortedIds)
                    .orderByAsc(SysTenantPackage::getId);
            PessimisticLockUtil.forUpdate(lockQuery);
            List<SysTenantPackage> targets = sysTenantPackageMapper.selectList(lockQuery);
            if (CollectionUtils.isEmpty(targets) || targets.size() != sortedIds.size()) {
                throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "部分目标租户套餐不存在或已被删除");
            }
            assertNoTenantReferences(targets, sortedIds);

            sysTenantPackageMenuMapper.delete(
                    Wrappers.<SysTenantPackageMenu>lambdaQuery().in(SysTenantPackageMenu::getPackageId, sortedIds)
            );
            sysTenantPackageMapper.deleteByIds(sortedIds);
        });
    }

    private void assertUniquePackageName(Long excludeId, String packageName) {
        Long count = sysTenantPackageMapper.selectCount(
                Wrappers.<SysTenantPackage>lambdaQuery()
                        .eq(SysTenantPackage::getPackageName, packageName)
                        .ne(excludeId != null, SysTenantPackage::getId, excludeId)
        );
        if (count != null && count > 0L) {
            throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "租户套餐名称已存在：" + packageName);
        }
    }

    private List<Long> loadBoundTenantIds(Long packageId) {
        return sysTenantMapper.selectList(
                Wrappers.<SysTenant>lambdaQuery()
                        .select(SysTenant::getId)
                        .eq(SysTenant::getPackageId, packageId)
        ).stream().map(SysTenant::getId).filter(Objects::nonNull).toList();
    }

    private void replacePackageMenus(Long packageId, List<Long> menuIds) {
        sysTenantPackageMenuMapper.delete(
                Wrappers.<SysTenantPackageMenu>lambdaQuery().eq(SysTenantPackageMenu::getPackageId, packageId)
        );
        if (CollectionUtils.isEmpty(menuIds)) {
            return;
        }
        List<SysTenantPackageMenu> mappings = new ArrayList<>(menuIds.size());
        for (Long menuId : menuIds) {
            SysTenantPackageMenu mapping = new SysTenantPackageMenu();
            mapping.setPackageId(packageId);
            mapping.setMenuId(menuId);
            mappings.add(mapping);
        }
        BatchUtil.insertBatch(sysTenantPackageMenuMapper, mappings);
    }

    private void assertNoTenantReferences(List<SysTenantPackage> targets, List<Long> idList) {
        Map<Long, Long> countByPkgId = GroupCountRow.toCountMap(sysTenantMapper.selectTenantCountsByPackageIds(idList));
        for (SysTenantPackage target : targets) {
            Long boundCount = countByPkgId.get(target.getId());
            if (boundCount != null && boundCount > 0L) {
                throw new BusinessException(
                        SystemResultCode.RESOURCE_IN_USE,
                        "套餐【" + target.getPackageName() + "】已被 " + boundCount + " 个租户绑定，请先变更租户套餐后再删除"
                );
            }
        }
    }

    private static NormalizedPackagePayload validateAndNormalize(SysTenantPackage existing, TenantPackageSaveRequest request) {
        if (request == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "套餐参数不能为空");
        }
        String packageName = SystemContextSupport.trimToNull(request.getPackageName());
        if (packageName == null || packageName.length() < MIN_PACKAGE_NAME_LEN || packageName.length() > MAX_PACKAGE_NAME_LEN) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "套餐名称长度须在 2~50 个非空白字符之间");
        }
        Integer status = request.getStatus() != null
                ? SystemContextSupport.normalizeStatus(request.getStatus())
                : (existing != null ? existing.getStatus() : SystemContextSupport.normalizeStatus(null));
        boolean menuCheckStrictly = request.getMenuCheckStrictly() != null
                ? request.getMenuCheckStrictly()
                : (existing == null || !Boolean.FALSE.equals(existing.getMenuCheckStrictly()));
        String remark = SystemContextSupport.trimToNull(request.getRemark());
        return new NormalizedPackagePayload(packageName, menuCheckStrictly, status, remark);
    }

    private record NormalizedPackagePayload(
            String packageName,
            boolean menuCheckStrictly,
            Integer status,
            String remark
    ) {
    }
}

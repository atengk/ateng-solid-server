package io.github.atengk.solid.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.mybatisplus.util.PageUtil;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.entity.SysMenu;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysTenantPackage;
import io.github.atengk.solid.system.mapper.GroupCountRow;
import io.github.atengk.solid.system.mapper.SysMenuMapper;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMenuMapper;
import io.github.atengk.solid.system.request.TenantPackageQueryRequest;
import io.github.atengk.solid.system.request.TenantPackageShrinkPreviewRequest;
import io.github.atengk.solid.system.support.SystemContextSupport;
import io.github.atengk.solid.system.support.TenantPackageMenuSyncSupport;
import io.github.atengk.solid.system.vo.TenantPackageDetailVO;
import io.github.atengk.solid.system.vo.TenantPackageOptionVO;
import io.github.atengk.solid.system.vo.TenantPackageShrinkPreviewVO;
import io.github.atengk.solid.system.vo.TenantPackageShrinkPreviewVO.AffectedTenantVO;
import io.github.atengk.solid.system.vo.TenantPackageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 租户套餐只读查询、启用选项与缩减影响范围预览服务（锁定多租户开启态平台租户 {@code 0}）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Service
@RequiredArgsConstructor
public class SysTenantPackageQueryService {

    private final SystemContextSupport contextSupport;
    private final SysTenantPackageMapper sysTenantPackageMapper;
    private final SysTenantPackageMenuMapper sysTenantPackageMenuMapper;
    private final SysTenantMapper sysTenantMapper;
    private final SysMenuMapper sysMenuMapper;
    private final TenantPackageMenuSyncSupport menuSyncSupport;

    /**
     * 分页查询租户套餐列表（含绑定菜单数与绑定租户数统计）。
     *
     * @param query 分页与筛选参数
     * @return 套餐分页视图结果
     */
    public PageResult<TenantPackageVO> pagePackages(TenantPackageQueryRequest query) {
        contextSupport.requireMultiTenantPlatformAccess();
        TenantPackageQueryRequest safeQuery = query != null ? query : new TenantPackageQueryRequest();
        Page<SysTenantPackage> pageRequest = PageUtil.toPage(
                safeQuery, SysTenantPackage.class, true, SystemContextSupport.DEFAULT_MAX_PAGE_SIZE
        );
        Integer status = safeQuery.getStatus();

        Page<SysTenantPackage> resultPage = TenantContextHolder.runWithoutTenant(() -> {
            LambdaQueryWrapper<SysTenantPackage> wrapper = Wrappers.<SysTenantPackage>lambdaQuery()
                    .eq(status != null, SysTenantPackage::getStatus, status);
            SystemContextSupport.applyContainsLike(wrapper, SysTenantPackage::getPackageName, safeQuery.getPackageName());
            if (CollectionUtils.isEmpty(pageRequest.orders())) {
                wrapper.orderByDesc(SysTenantPackage::getCreateTime).orderByDesc(SysTenantPackage::getId);
            }
            return sysTenantPackageMapper.selectPage(pageRequest, wrapper);
        });

        List<SysTenantPackage> records = SystemContextSupport.safeList(resultPage.getRecords());
        if (records.isEmpty()) {
            return SystemContextSupport.toPageResult(resultPage, pkg -> toPackageVO(pkg, 0, 0));
        }

        List<Long> packageIds = records.stream().map(SysTenantPackage::getId).toList();
        Map<Long, Long> menuCounts = GroupCountRow.toCountMap(
                TenantContextHolder.runWithoutTenant(() -> sysTenantPackageMenuMapper.selectMenuCountsByPackageIds(packageIds))
        );
        Map<Long, Long> tenantCounts = GroupCountRow.toCountMap(
                TenantContextHolder.runWithoutTenant(() -> sysTenantMapper.selectTenantCountsByPackageIds(packageIds))
        );
        return SystemContextSupport.toPageResult(resultPage, pkg -> toPackageVO(
                pkg,
                menuCounts.getOrDefault(pkg.getId(), 0L).intValue(),
                tenantCounts.getOrDefault(pkg.getId(), 0L).intValue()
        ));
    }

    /**
     * 获取全部正常启用状态的租户套餐下拉选项。
     *
     * @return 启用中套餐选项列表
     */
    public List<TenantPackageOptionVO> listActiveOptions() {
        contextSupport.requireMultiTenantPlatformAccess();
        List<SysTenantPackage> packages = TenantContextHolder.runWithoutTenant(() -> sysTenantPackageMapper.selectList(
                Wrappers.<SysTenantPackage>lambdaQuery()
                        .select(SysTenantPackage::getId, SysTenantPackage::getPackageName)
                        .eq(SysTenantPackage::getStatus, CommonStatusEnum.NORMAL.getCode())
                        .orderByAsc(SysTenantPackage::getId)
        ));
        if (CollectionUtils.isEmpty(packages)) {
            return Collections.emptyList();
        }
        return packages.stream()
                .map(pkg -> TenantPackageOptionVO.builder()
                        .id(pkg.getId())
                        .packageName(pkg.getPackageName())
                        .build())
                .toList();
    }

    /**
     * 按主键查询租户套餐详情及已绑定菜单主键列表。
     *
     * @param id 套餐主键
     * @return 套餐详情视图
     */
    public TenantPackageDetailVO getDetail(Long id) {
        contextSupport.requireMultiTenantPlatformAccess();
        SysTenantPackage pkg = menuSyncSupport.requirePackageExists(id);
        List<Long> menuIds = menuSyncSupport.loadBoundMenuIds(pkg.getId());
        return TenantPackageDetailVO.builder()
                .id(pkg.getId())
                .packageName(pkg.getPackageName())
                .menuCheckStrictly(pkg.getMenuCheckStrictly())
                .status(pkg.getStatus())
                .remark(pkg.getRemark())
                .createTime(pkg.getCreateTime())
                .menuIds(menuIds)
                .build();
    }

    /**
     * 预览缩减套餐菜单集合时将被移除的菜单标题及受影响绑定租户清单。
     *
     * @param request 缩减预览请求（含 {@code packageId} 与新版 {@code newMenuIds}）
     * @return 只读影响范围预览
     */
    public TenantPackageShrinkPreviewVO previewShrinkImpact(TenantPackageShrinkPreviewRequest request) {
        contextSupport.requireMultiTenantPlatformAccess();
        if (request == null || request.getPackageId() == null || request.getPackageId() <= 0L) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "套餐主键必须为正整数");
        }
        SysTenantPackage pkg = menuSyncSupport.requirePackageExists(request.getPackageId());
        Set<Long> newMenuSet = new HashSet<>(
                SystemContextSupport.deduplicatePositiveIds(request.getNewMenuIds(), "存在无效的菜单主键标识")
        );
        List<Long> oldMenuIds = menuSyncSupport.loadBoundMenuIds(pkg.getId());
        List<Long> removedMenuIds = oldMenuIds.stream().filter(id -> !newMenuSet.contains(id)).toList();

        if (removedMenuIds.isEmpty()) {
            return TenantPackageShrinkPreviewVO.builder()
                    .removedMenuIds(Collections.emptyList())
                    .removedMenuTitles(Collections.emptyList())
                    .affectedTenantCount(0)
                    .affectedTenants(Collections.emptyList())
                    .build();
        }

        List<String> removedMenuTitles = loadMenuTitlesInOrder(removedMenuIds);
        List<AffectedTenantVO> affectedTenants = loadAffectedTenants(pkg.getId());
        return TenantPackageShrinkPreviewVO.builder()
                .removedMenuIds(removedMenuIds)
                .removedMenuTitles(removedMenuTitles)
                .affectedTenantCount(affectedTenants.size())
                .affectedTenants(affectedTenants)
                .build();
    }

    private List<String> loadMenuTitlesInOrder(List<Long> menuIds) {
        List<SysMenu> safeMenus = SystemContextSupport.safeList(
                TenantContextHolder.runWithoutTenant(() -> sysMenuMapper.selectList(
                        Wrappers.<SysMenu>lambdaQuery()
                                .select(SysMenu::getId, SysMenu::getTitle)
                                .in(SysMenu::getId, menuIds)
                ))
        );
        Map<Long, String> titleById = new HashMap<>(safeMenus.size());
        for (SysMenu menu : safeMenus) {
            titleById.put(menu.getId(), menu.getTitle());
        }
        List<String> titles = new ArrayList<>(menuIds.size());
        for (Long id : menuIds) {
            titles.add(titleById.getOrDefault(id, String.valueOf(id)));
        }
        return titles;
    }

    private List<AffectedTenantVO> loadAffectedTenants(Long packageId) {
        List<SysTenant> tenants = TenantContextHolder.runWithoutTenant(() -> sysTenantMapper.selectList(
                Wrappers.<SysTenant>lambdaQuery()
                        .select(SysTenant::getId, SysTenant::getTenantCode, SysTenant::getTenantName)
                        .eq(SysTenant::getPackageId, packageId)
                        .orderByAsc(SysTenant::getId)
        ));
        if (CollectionUtils.isEmpty(tenants)) {
            return Collections.emptyList();
        }
        return tenants.stream()
                .map(tenant -> AffectedTenantVO.builder()
                        .id(tenant.getId())
                        .tenantCode(tenant.getTenantCode())
                        .tenantName(tenant.getTenantName())
                        .build())
                .toList();
    }

    private static TenantPackageVO toPackageVO(SysTenantPackage pkg, int menuCount, int tenantCount) {
        return TenantPackageVO.builder()
                .id(pkg.getId())
                .packageName(pkg.getPackageName())
                .menuCount(menuCount)
                .tenantCount(tenantCount)
                .menuCheckStrictly(pkg.getMenuCheckStrictly())
                .status(pkg.getStatus())
                .remark(pkg.getRemark())
                .createTime(pkg.getCreateTime())
                .build();
    }
}

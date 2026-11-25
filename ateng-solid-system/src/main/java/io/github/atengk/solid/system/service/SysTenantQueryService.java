package io.github.atengk.solid.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.mybatisplus.util.PageUtil;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysTenantPackage;
import io.github.atengk.solid.system.mapper.GroupCountRow;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.request.TenantQueryRequest;
import io.github.atengk.solid.system.support.SystemContextSupport;
import io.github.atengk.solid.system.vo.TenantOptionVO;
import io.github.atengk.solid.system.vo.TenantVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 租户管理只读分页、详情与顶栏切换选项查询服务。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Service
@RequiredArgsConstructor
public class SysTenantQueryService {

    private final SystemContextSupport contextSupport;
    private final SysTenantMapper sysTenantMapper;
    private final SysTenantPackageMapper sysTenantPackageMapper;
    private final SysUserMapper sysUserMapper;

    /**
     * 分页查询平台纳管的全部 SaaS 租户列表（含关联套餐名称与已用账号数）。
     *
     * @param query 分页与筛选参数
     * @return 租户分页结果
     */
    public PageResult<TenantVO> pageTenants(TenantQueryRequest query) {
        contextSupport.requireMultiTenantPlatformAccess();
        TenantQueryRequest safeQuery = query != null ? query : new TenantQueryRequest();
        Page<SysTenant> pageRequest = PageUtil.toPage(
                safeQuery, SysTenant.class, true, SystemContextSupport.DEFAULT_MAX_PAGE_SIZE
        );

        String tenantCode = SystemContextSupport.trimToNull(safeQuery.getTenantCode());
        String tenantName = SystemContextSupport.trimToNull(safeQuery.getTenantName());
        Integer status = safeQuery.getStatus();
        Long packageId = safeQuery.getPackageId();

        Page<SysTenant> resultPage = TenantContextHolder.runWithoutTenant(() -> {
            LambdaQueryWrapper<SysTenant> wrapper = Wrappers.<SysTenant>lambdaQuery()
                    .like(tenantCode != null, SysTenant::getTenantCode, tenantCode)
                    .like(tenantName != null, SysTenant::getTenantName, tenantName)
                    .eq(status != null, SysTenant::getStatus, status)
                    .eq(packageId != null, SysTenant::getPackageId, packageId);
            if (CollectionUtils.isEmpty(safeQuery.getSorts())) {
                wrapper.orderByDesc(SysTenant::getCreateTime).orderByDesc(SysTenant::getId);
            }
            return sysTenantMapper.selectPage(pageRequest, wrapper);
        });

        List<SysTenant> records = SystemContextSupport.safeList(resultPage.getRecords());
        if (records.isEmpty()) {
            return SystemContextSupport.toPageResult(resultPage, t -> toTenantVO(t, null, 0L, LocalDateTime.now()));
        }

        Map<Long, SysTenantPackage> packageMap = batchLoadPackages(records);
        Map<Long, Long> userCountMap = batchLoadUserCounts(records.stream().map(SysTenant::getId).toList());
        LocalDateTime now = LocalDateTime.now();

        return SystemContextSupport.toPageResult(resultPage, tenant -> toTenantVO(
                tenant,
                tenant.getPackageId() != null ? packageMap.get(tenant.getPackageId()) : null,
                userCountMap.getOrDefault(tenant.getId(), 0L),
                now
        ));
    }

    /**
     * 获取当前平台超管顶栏可切换的正常启用且未过期租户列表。
     *
     * @return 活跃可切换租户选项列表
     */
    public List<TenantOptionVO> listSwitchOptions() {
        contextSupport.requirePlatformSuperAdminSwitchAccess();
        LocalDateTime now = LocalDateTime.now();
        List<SysTenant> activeTenants = TenantContextHolder.runWithoutTenant(() -> sysTenantMapper.selectList(
                Wrappers.<SysTenant>lambdaQuery()
                        .select(SysTenant::getId, SysTenant::getTenantCode, SysTenant::getTenantName,
                                SysTenant::getStatus, SysTenant::getExpireTime)
                        .eq(SysTenant::getStatus, CommonStatusEnum.NORMAL.getCode())
                        .and(w -> w.isNull(SysTenant::getExpireTime).or().gt(SysTenant::getExpireTime, now))
                        .orderByAsc(SysTenant::getId)
        ));
        if (CollectionUtils.isEmpty(activeTenants)) {
            return Collections.emptyList();
        }
        return activeTenants.stream()
                .map(t -> TenantOptionVO.builder()
                        .id(t.getId())
                        .tenantCode(t.getTenantCode())
                        .tenantName(t.getTenantName())
                        .status(t.getStatus())
                        .expireTime(t.getExpireTime())
                        .build())
                .toList();
    }

    /**
     * 按主键查询单个租户详情（包含 {@code id = 0} 平台默认租户）。
     *
     * @param id 租户主键
     * @return 租户详情视图对象
     */
    public TenantVO getDetail(Long id) {
        contextSupport.requireMultiTenantPlatformAccess();
        if (id == null || id < 0L) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "租户主键不能为负数");
        }
        SysTenant tenant = TenantContextHolder.runWithoutTenant(() -> sysTenantMapper.selectById(id));
        if (tenant == null) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "目标租户不存在");
        }
        SysTenantPackage pkg = tenant.getPackageId() != null
                ? TenantContextHolder.runWithoutTenant(() -> sysTenantPackageMapper.selectById(tenant.getPackageId()))
                : null;
        Map<Long, Long> userCountMap = batchLoadUserCounts(List.of(tenant.getId()));
        return toTenantVO(tenant, pkg, userCountMap.getOrDefault(tenant.getId(), 0L), LocalDateTime.now());
    }

    private Map<Long, SysTenantPackage> batchLoadPackages(List<SysTenant> tenants) {
        Set<Long> packageIds = tenants.stream()
                .map(SysTenant::getPackageId)
                .filter(id -> id != null && id > 0L)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (packageIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<SysTenantPackage> packages = TenantContextHolder.runWithoutTenant(() -> sysTenantPackageMapper.selectList(
                Wrappers.<SysTenantPackage>lambdaQuery().in(SysTenantPackage::getId, packageIds)
        ));
        if (CollectionUtils.isEmpty(packages)) {
            return Collections.emptyMap();
        }
        Map<Long, SysTenantPackage> map = new HashMap<>(packages.size());
        for (SysTenantPackage pkg : packages) {
            map.put(pkg.getId(), pkg);
        }
        return map;
    }

    private Map<Long, Long> batchLoadUserCounts(List<Long> tenantIds) {
        if (CollectionUtils.isEmpty(tenantIds)) {
            return Collections.emptyMap();
        }
        return GroupCountRow.toCountMap(
                DataPermissionContextHolder.runWithoutPermission(() ->
                        TenantContextHolder.runWithoutTenant(() -> sysUserMapper.selectUserCountsByTenantIds(tenantIds)))
        );
    }

    private static TenantVO toTenantVO(
            SysTenant tenant,
            SysTenantPackage pkg,
            long usedAccountCount,
            LocalDateTime now
    ) {
        boolean expired = tenant.getExpireTime() != null && !tenant.getExpireTime().isAfter(now);
        boolean pkgDisabled = pkg != null && CommonStatusEnum.DISABLED.getCode().equals(pkg.getStatus());
        return TenantVO.builder()
                .id(tenant.getId())
                .tenantCode(tenant.getTenantCode())
                .tenantName(tenant.getTenantName())
                .packageId(tenant.getPackageId())
                .packageName(pkg != null ? pkg.getPackageName() : null)
                .packageDisabled(pkgDisabled)
                .accountCount(tenant.getAccountCount())
                .usedAccountCount(usedAccountCount)
                .expireTime(tenant.getExpireTime())
                .expired(expired)
                .status(tenant.getStatus())
                .remark(tenant.getRemark())
                .platformDefault(Objects.equals(SolidConstants.PLATFORM_TENANT_ID, tenant.getId()))
                .createTime(tenant.getCreateTime())
                .build();
    }
}

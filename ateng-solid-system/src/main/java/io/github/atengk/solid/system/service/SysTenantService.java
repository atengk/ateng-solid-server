package io.github.atengk.solid.system.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.boot.core.util.TransactionUtil;
import io.github.atengk.boot.satoken.model.OnlineUser;
import io.github.atengk.boot.satoken.model.OnlineUserQuery;
import io.github.atengk.boot.satoken.util.LoginHelper;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.common.validator.PasswordPolicyValidator;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysTenantPackage;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMapper;
import io.github.atengk.solid.system.request.TenantCreateRequest;
import io.github.atengk.solid.system.request.TenantUpdateRequest;
import io.github.atengk.solid.system.support.SystemContextSupport;
import io.github.atengk.solid.system.support.TenantInitializationSupport;
import io.github.atengk.solid.system.support.TenantInitializationSupport.ProvisionTenantCommand;
import io.github.atengk.solid.system.support.TenantPackageMenuSyncSupport;
import io.github.atengk.solid.system.support.TenantStatusCache;
import io.github.atengk.solid.system.support.UserContactAndDictSupport;
import io.github.atengk.solid.system.vo.CreateTenantVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 租户管理写操作核心业务服务（4 步原子初始化、默认租户 {@code 0} 保护、换套餐裁剪及提交后缓存在线会话全清）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Service
@RequiredArgsConstructor
public class SysTenantService {

    private static final int UNLIMITED_QUOTA = -1;
    private static final int ONLINE_KICKOUT_PAGE_SIZE = 100;
    private static final int ONLINE_KICKOUT_MAX_SCAN_LIMIT = 20_000;
    private static final int MAX_KICKOUT_BATCHES = 1000;
    private static final Pattern TENANT_CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{4,32}$");

    private final SystemContextSupport contextSupport;
    private final SysTenantMapper sysTenantMapper;
    private final SysTenantPackageMapper sysTenantPackageMapper;
    private final TenantPackageMenuSyncSupport menuSyncSupport;
    private final TenantInitializationSupport tenantInitializationSupport;
    private final TenantStatusCache tenantStatusCache;

    /**
     * 新增租户并执行 4 步原子初始化（事务外完成强口令慢哈希，短事务写入租户/根部门/{@code tenant_admin}/初始管理员）。
     *
     * @param request 新增租户请求
     * @return 新建租户初始化凭据摘要
     */
    public CreateTenantVO createTenant(TenantCreateRequest request) {
        contextSupport.requireMultiTenantPlatformAccess();
        if (request == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "新增租户参数不能为空");
        }

        // 1. 事务外校验编码/配额/管理员账号与密码复杂度，并在事务外执行 PBKDF2 慢哈希
        String tenantCode = requireValidTenantCode(request.getTenantCode());
        String tenantName = requireValidTenantName(request.getTenantName());
        Integer accountCount = requireValidAccountCount(request.getAccountCount());
        Integer status = SystemContextSupport.normalizeStatus(request.getStatus());
        String remark = SystemContextSupport.trimToNull(request.getRemark());
        String adminUsername = UserContactAndDictSupport.requireValidUsernameFormat(
                request.getAdminUsername(), "租户管理员账号须为 2~30 位字母、数字或下划线"
        );
        String rawPassword = request.getAdminPassword();
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "租户管理员初始密码不能为空");
        }
        PasswordPolicyValidator.validate(rawPassword);
        String encodedPassword = PasswordUtil.hash(rawPassword);

        assertTenantCodeUnique(tenantCode);
        requireActivePackage(request.getPackageId());
        List<Long> packageMenuIds = menuSyncSupport.loadEligiblePackageMenuIds(request.getPackageId());

        // 2. 开启极短写事务顺序落库 4 步初始化（任一步异常整体回滚，零孤儿数据）
        CreateTenantVO created = tenantInitializationSupport.provisionNewTenantAtomically(new ProvisionTenantCommand(
                tenantCode,
                tenantName,
                request.getPackageId(),
                accountCount,
                request.getExpireTime(),
                status,
                remark,
                adminUsername,
                encodedPassword,
                packageMenuIds
        ));
        tenantStatusCache.evict(created.getTenantId());
        return created;
    }

    /**
     * 修改租户配置（平台租户 {@code 0} 仅允许修改名称与备注，换套餐缩窄时裁剪该租户角色菜单，提交后驱逐状态缓存）。
     *
     * @param request 修改租户请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateTenant(TenantUpdateRequest request) {
        contextSupport.requireMultiTenantPlatformAccess();
        if (request == null || request.getId() == null || request.getId() < 0L) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "修改租户必须传入有效主键");
        }
        SysTenant existing = requireTenantExists(request.getId());
        if (SolidConstants.PLATFORM_TENANT_ID.equals(existing.getId())) {
            updatePlatformDefaultTenant(existing, request);
            return;
        }
        updateOrdinaryTenant(existing, request);
    }

    /**
     * 切换租户启停状态（阻断 {@code 0} 号租户抛出 {@code 102003}，停用提交后驱逐缓存并强退该租户全部在线会话）。
     *
     * @param id     租户主键
     * @param status 目标状态（0=正常，1=停用）
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateTenantStatus(Long id, Integer status) {
        contextSupport.requireMultiTenantPlatformAccess();
        if (id == null || id < 0L) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "租户主键不能为负数");
        }
        Integer validStatus = SystemContextSupport.requireValidStatus(status);
        if (SolidConstants.PLATFORM_TENANT_ID.equals(id)) {
            throw new BusinessException(SystemResultCode.PLATFORM_TENANT_PROTECTED, "平台内置默认租户禁止停用");
        }
        SysTenant existing = requireTenantExists(id);

        TenantContextHolder.runWithoutTenant(() -> sysTenantMapper.update(
                new SysTenant(),
                Wrappers.<SysTenant>lambdaUpdate()
                        .eq(SysTenant::getId, existing.getId())
                        .set(SysTenant::getStatus, validStatus)
        ));

        boolean disabling = CommonStatusEnum.DISABLED.getCode().equals(validStatus);
        TransactionUtil.executeAfterCommit(() -> {
            tenantStatusCache.evict(existing.getId());
            if (disabling) {
                kickoutAllSessionsForTenants(List.of(existing.getId()));
            }
        });
    }

    /**
     * 批量删除租户（阻断 {@code 0} 号租户抛出 {@code 102003}，提交后驱逐缓存并强退被删租户全部在线会话）。
     *
     * @param ids 逗号分隔的租户主键列表串
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteTenants(String ids) {
        contextSupport.requireMultiTenantPlatformAccess();
        List<Long> idList = SystemContextSupport.parsePathIds(ids, true);
        if (idList.contains(SolidConstants.PLATFORM_TENANT_ID)) {
            throw new BusinessException(SystemResultCode.PLATFORM_TENANT_PROTECTED, "平台内置默认租户禁止删除");
        }

        TenantContextHolder.runWithoutTenant(() -> {
            List<SysTenant> targets = sysTenantMapper.selectList(
                    Wrappers.<SysTenant>lambdaQuery().in(SysTenant::getId, idList)
            );
            if (CollectionUtils.isEmpty(targets) || targets.size() != idList.size()) {
                throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "部分目标租户不存在或已被删除");
            }
            sysTenantMapper.deleteByIds(idList);
        });

        List<Long> snapshotIds = List.copyOf(idList);
        TransactionUtil.executeAfterCommit(() -> {
            for (Long tenantId : snapshotIds) {
                tenantStatusCache.evict(tenantId);
            }
            kickoutAllSessionsForTenants(snapshotIds);
        });
    }

    private void updatePlatformDefaultTenant(SysTenant existing, TenantUpdateRequest request) {
        String submittedCode = SystemContextSupport.trimToNull(request.getTenantCode());
        if (submittedCode != null && !Objects.equals(existing.getTenantCode(), submittedCode)) {
            throw new BusinessException(SystemResultCode.PLATFORM_TENANT_PROTECTED, "平台默认租户编码不允许篡改");
        }
        if (request.getStatus() != null && !CommonStatusEnum.NORMAL.getCode().equals(request.getStatus())) {
            throw new BusinessException(SystemResultCode.PLATFORM_TENANT_PROTECTED, "平台内置默认租户禁止停用");
        }
        if (request.getPackageId() != null || request.getExpireTime() != null
                || (request.getAccountCount() != null && request.getAccountCount() != UNLIMITED_QUOTA)) {
            throw new BusinessException(SystemResultCode.PLATFORM_TENANT_PROTECTED, "平台默认租户仅允许修改名称与备注");
        }

        String tenantName = requireValidTenantName(request.getTenantName());
        String remark = SystemContextSupport.trimToNull(request.getRemark());
        TenantContextHolder.runWithoutTenant(() -> sysTenantMapper.update(
                new SysTenant(),
                Wrappers.<SysTenant>lambdaUpdate()
                        .eq(SysTenant::getId, existing.getId())
                        .set(SysTenant::getTenantName, tenantName)
                        .set(SysTenant::getRemark, remark)
        ));
        TransactionUtil.executeAfterCommit(() -> tenantStatusCache.evict(existing.getId()));
    }

    private void updateOrdinaryTenant(SysTenant existing, TenantUpdateRequest request) {
        String submittedCode = SystemContextSupport.trimToNull(request.getTenantCode());
        if (submittedCode != null && !Objects.equals(existing.getTenantCode(), submittedCode)) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "租户编码创建后锁定只读，不允许修改");
        }
        String tenantName = requireValidTenantName(request.getTenantName());
        Long targetPackageId = request.getPackageId();
        if (targetPackageId == null || targetPackageId <= 0L) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "必须指定有效的关联租户套餐");
        }
        Integer accountCount = request.getAccountCount() != null
                ? requireValidAccountCount(request.getAccountCount())
                : existing.getAccountCount();
        Integer targetStatus = request.getStatus() != null
                ? SystemContextSupport.requireValidStatus(request.getStatus())
                : existing.getStatus();
        String remark = SystemContextSupport.trimToNull(request.getRemark());

        boolean packageChanged = !Objects.equals(existing.getPackageId(), targetPackageId);
        if (packageChanged) {
            requireActivePackage(targetPackageId);
        } else {
            requireExistingPackage(targetPackageId);
        }

        applyOrdinaryTenantUpdate(existing.getId(), tenantName, targetPackageId,
                request.getExpireTime(), accountCount, targetStatus, remark);
        if (packageChanged) {
            pruneMenusIfPackageNarrowed(existing.getId(), existing.getPackageId(), targetPackageId);
        }

        boolean disabling = CommonStatusEnum.DISABLED.getCode().equals(targetStatus)
                && !CommonStatusEnum.DISABLED.getCode().equals(existing.getStatus());
        TransactionUtil.executeAfterCommit(() -> {
            tenantStatusCache.evict(existing.getId());
            if (disabling) {
                kickoutAllSessionsForTenants(List.of(existing.getId()));
            }
        });
    }

    private void applyOrdinaryTenantUpdate(
            Long tenantId, String tenantName, Long packageId,
            LocalDateTime expireTime, Integer accountCount, Integer targetStatus, String remark) {
        TenantContextHolder.runWithoutTenant(() -> sysTenantMapper.update(
                new SysTenant(),
                new LambdaUpdateWrapper<SysTenant>()
                        .eq(SysTenant::getId, tenantId)
                        .set(SysTenant::getTenantName, tenantName)
                        .set(SysTenant::getPackageId, packageId)
                        .set(SysTenant::getExpireTime, expireTime)
                        .set(SysTenant::getAccountCount, accountCount)
                        .set(SysTenant::getStatus, targetStatus)
                        .set(SysTenant::getRemark, remark)
        ));
    }

    private void pruneMenusIfPackageNarrowed(Long tenantId, Long oldPackageId, Long newPackageId) {
        List<Long> oldMenuIds = menuSyncSupport.loadEligiblePackageMenuIds(oldPackageId);
        List<Long> newMenuIds = menuSyncSupport.loadEligiblePackageMenuIds(newPackageId);
        Set<Long> newMenuSet = new HashSet<>(newMenuIds);
        boolean narrowed = oldMenuIds.stream().anyMatch(menuId -> !newMenuSet.contains(menuId));
        if (narrowed) {
            menuSyncSupport.pruneRoleMenusForTenants(List.of(tenantId), newMenuSet);
        }
    }

    private SysTenant requireTenantExists(Long id) {
        SysTenant tenant = TenantContextHolder.runWithoutTenant(() -> sysTenantMapper.selectById(id));
        if (tenant == null) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "目标租户不存在");
        }
        return tenant;
    }

    private void assertTenantCodeUnique(String tenantCode) {
        Long count = TenantContextHolder.runWithoutTenant(() -> sysTenantMapper.selectCount(
                Wrappers.<SysTenant>lambdaQuery().eq(SysTenant::getTenantCode, tenantCode)
        ));
        if (count != null && count > 0L) {
            throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "租户编码已存在：" + tenantCode);
        }
    }

    private void requireExistingPackage(Long packageId) {
        SysTenantPackage pkg = TenantContextHolder.runWithoutTenant(() -> sysTenantPackageMapper.selectById(packageId));
        if (pkg == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "所选租户套餐不存在");
        }
    }

    private void requireActivePackage(Long packageId) {
        if (packageId == null || packageId <= 0L) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "必须选择正常启用状态的租户套餐");
        }
        SysTenantPackage pkg = TenantContextHolder.runWithoutTenant(() -> sysTenantPackageMapper.selectById(packageId));
        if (pkg == null || !CommonStatusEnum.NORMAL.getCode().equals(pkg.getStatus())) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "所选租户套餐不存在或已停用");
        }
    }

    private void kickoutAllSessionsForTenants(Collection<Long> tenantIds) {
        for (Long tenantId : tenantIds) {
            if (tenantId == null || SolidConstants.PLATFORM_TENANT_ID.equals(tenantId)) {
                continue;
            }
            kickoutSingleTenantSessions(String.valueOf(tenantId));
        }
    }

    private void kickoutSingleTenantSessions(String tenantIdStr) {
        OnlineUserQuery queryByTenant = OnlineUserQuery.builder()
                .tenantId(tenantIdStr)
                .maxScanLimit(ONLINE_KICKOUT_MAX_SCAN_LIMIT)
                .build();
        Set<String> attemptedTokens = new HashSet<>();
        int pageNum = 1;
        for (int batch = 0; batch < MAX_KICKOUT_BATCHES; batch++) {
            PageResult<OnlineUser> page = LoginHelper.searchOnlineUserPage(
                    SolidConstants.ADMIN_LOGIN_TYPE,
                    queryByTenant,
                    pageNum,
                    ONLINE_KICKOUT_PAGE_SIZE
            );
            List<OnlineUser> records = page != null ? page.getRecords() : Collections.emptyList();
            if (CollectionUtils.isEmpty(records)) {
                return;
            }
            boolean kickedAnyNewToken = false;
            for (OnlineUser onlineUser : records) {
                String tokenValue = onlineUser != null ? onlineUser.getTokenValue() : null;
                if (!StringUtils.hasText(tokenValue) || !attemptedTokens.add(tokenValue)) {
                    continue;
                }
                LoginHelper.kickoutByTokenForTenant(SolidConstants.ADMIN_LOGIN_TYPE, tokenValue, tenantIdStr);
                kickedAnyNewToken = true;
            }
            if (!kickedAnyNewToken) {
                pageNum++;
            }
        }
    }

    private static String requireValidTenantCode(String rawCode) {
        String code = SystemContextSupport.trimToNull(rawCode);
        if (code == null || !TENANT_CODE_PATTERN.matcher(code).matches()) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "租户编码须为 4~32 位字母、数字、下划线或短横线");
        }
        if (SolidConstants.PLATFORM_TENANT_CODE.equals(code)) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "禁止使用平台保留租户编码 000000");
        }
        return code;
    }

    private static String requireValidTenantName(String rawName) {
        String name = SystemContextSupport.trimToNull(rawName);
        if (name == null || name.length() < 2 || name.length() > 50) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "租户名称长度须在 2~50 个非空白字符之间");
        }
        return name;
    }

    private static Integer requireValidAccountCount(Integer accountCount) {
        if (accountCount == null || (accountCount != UNLIMITED_QUOTA && accountCount < 1)) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "账号配额上限仅支持 -1（不限）或 >= 1 的正整数");
        }
        return accountCount;
    }
}

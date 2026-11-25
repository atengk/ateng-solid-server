package io.github.atengk.solid.system.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.mybatisplus.util.PessimisticLockUtil;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 租户账号配额并发防超卖守卫深模块。
 * <p>
 * 在多租户开启时统一收口新增用户、停用转启用用户与 500 行批量导入阶段 3 的 {@code account_count} 配额校核；
 * 写事务入口强制通过 {@link PessimisticLockUtil#forUpdate} 锁定 {@code sys_tenant} 目标行，彻底消除并发超卖窗口。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Component
@RequiredArgsConstructor
public class TenantQuotaGuard {

    private final SystemContextSupport contextSupport;
    private final SysTenantMapper sysTenantMapper;
    private final SysUserMapper sysUserMapper;

    /**
     * 在当前事务内以行级悲观锁锁定目标租户记录并校验新增/启用后有效账号数是否超出套餐配额上限。
     * <p>
     * 调用方必须处于活跃事务内（{@code @Transactional} 或 {@code TransactionTemplate}），否则抛出异常防止无锁穿透。
     * </p>
     *
     * @param tenantId 目标租户主键
     * @param delta    本次增量名额（新增用户传 {@code 1}，批量导入传导入行数，停用转启用存量用户传 {@code 0}）
     * @throws BusinessException 租户不存在抛出 {@code 102001}，超出配额抛出 {@code 102006}
     */
    public void assertQuotaAvailable(Long tenantId, int delta) {
        if (!contextSupport.isMultiTenantEnabled() || tenantId == null) {
            return;
        }
        if (delta < 0) {
            throw new IllegalArgumentException("配额增量 delta 不能为负数: " + delta);
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("TenantQuotaGuard.assertQuotaAvailable 必须在事务上下文中调用以保证 FOR UPDATE 行锁有效");
        }
        SysTenant tenant = TenantContextHolder.runWithoutTenant(() -> {
            LambdaQueryWrapper<SysTenant> lockQuery = Wrappers.<SysTenant>lambdaQuery()
                    .eq(SysTenant::getId, tenantId);
            PessimisticLockUtil.forUpdate(lockQuery);
            return sysTenantMapper.selectOne(lockQuery);
        });
        verifyTenantQuota(tenantId, tenant, delta);
    }

    /**
     * 无锁只读预检当前租户账号配额（专供批量导入阶段 1 在进入耗时 PBKDF2 哈希前快速拒绝明显超额请求）。
     *
     * @param tenantId 目标租户主键
     * @param delta    本次待导入用户数量
     * @throws BusinessException 租户不存在抛出 {@code 102001}，超出配额抛出 {@code 102006}
     */
    public void checkQuotaPreview(Long tenantId, int delta) {
        if (!contextSupport.isMultiTenantEnabled() || tenantId == null || delta <= 0) {
            return;
        }
        SysTenant tenant = TenantContextHolder.runWithoutTenant(() -> sysTenantMapper.selectOne(
                Wrappers.<SysTenant>lambdaQuery().eq(SysTenant::getId, tenantId)
        ));
        verifyTenantQuota(tenantId, tenant, delta);
    }

    private void verifyTenantQuota(Long tenantId, SysTenant tenant, int delta) {
        if (tenant == null) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "目标租户不存在");
        }
        Integer accountCount = tenant.getAccountCount();
        if (accountCount == null || accountCount <= 0) {
            // account_count <= 0（如 -1）代表不限制租户账号数量
            return;
        }
        long currentCount = countExistingUsersInTenant(tenantId);
        if (currentCount + delta > accountCount.longValue()) {
            throw new BusinessException(
                    SystemResultCode.TENANT_QUOTA_OR_SCOPE_EXCEEDED,
                    String.format(
                            "当前租户用户数已达套餐上限（已用 %d / 上限 %d，本次新增 %d），请联系平台管理员扩容",
                            currentCount, accountCount, delta
                    )
            );
        }
    }

    private long countExistingUsersInTenant(Long tenantId) {
        Long count = DataPermissionContextHolder.runWithoutPermission(() ->
                TenantContextHolder.runWithTenant(tenantId, () -> sysUserMapper.selectCount(
                        Wrappers.<SysUser>lambdaQuery().eq(SysUser::getTenantId, tenantId)
                ))
        );
        return count != null ? count : 0L;
    }
}

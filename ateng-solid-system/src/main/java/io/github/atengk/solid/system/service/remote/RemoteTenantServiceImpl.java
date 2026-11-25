package io.github.atengk.solid.system.service.remote;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.solid.system.api.dto.TenantAuthDTO;
import io.github.atengk.solid.system.api.service.RemoteTenantService;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.support.TenantStatusCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 系统管理域租户查询与可用性校验远程服务实现。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Service
@RequiredArgsConstructor
public class RemoteTenantServiceImpl implements RemoteTenantService {

    private final SysTenantMapper sysTenantMapper;
    private final TenantStatusCache tenantStatusCache;

    @Override
    public TenantAuthDTO getTenantByCode(String tenantCode) {
        if (!StringUtils.hasText(tenantCode)) {
            return null;
        }
        String normalizedCode = tenantCode.trim();
        SysTenant entity = TenantContextHolder.runWithoutTenant(() -> sysTenantMapper.selectOne(
                Wrappers.<SysTenant>lambdaQuery().eq(SysTenant::getTenantCode, normalizedCode)
        ));
        return toAuthDTO(entity);
    }

    @Override
    public TenantAuthDTO getTenantById(Long tenantId) {
        if (tenantId == null) {
            return null;
        }
        return tenantStatusCache.getOrLoad(tenantId, this::loadTenantByIdFromDb);
    }

    @Override
    public boolean isTenantAvailable(Long tenantId) {
        TenantAuthDTO tenant = getTenantById(tenantId);
        return tenant != null && tenant.isAvailableAt(LocalDateTime.now());
    }

    private TenantAuthDTO loadTenantByIdFromDb(Long tenantId) {
        SysTenant entity = TenantContextHolder.runWithoutTenant(() -> sysTenantMapper.selectById(tenantId));
        return toAuthDTO(entity);
    }

    private static TenantAuthDTO toAuthDTO(SysTenant entity) {
        if (entity == null) {
            return null;
        }
        return TenantAuthDTO.builder()
                .tenantId(entity.getId())
                .tenantCode(entity.getTenantCode())
                .tenantName(entity.getTenantName())
                .packageId(entity.getPackageId())
                .expireTime(entity.getExpireTime())
                .status(entity.getStatus())
                .build();
    }
}

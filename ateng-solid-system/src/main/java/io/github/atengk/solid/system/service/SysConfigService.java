package io.github.atengk.solid.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.util.EnumUtil;
import io.github.atengk.boot.core.util.TransactionUtil;
import io.github.atengk.boot.mybatisplus.util.PageUtil;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.common.validator.PasswordPolicyValidator;
import io.github.atengk.solid.system.api.enums.ConfigTypeEnum;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysConfig;
import io.github.atengk.solid.system.mapper.SysConfigMapper;
import io.github.atengk.solid.system.request.ConfigQueryRequest;
import io.github.atengk.solid.system.request.ConfigSaveRequest;
import io.github.atengk.solid.system.support.SysConfigCache;
import io.github.atengk.solid.system.support.SystemContextSupport;
import io.github.atengk.solid.system.vo.ConfigVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 系统参数管理服务：实现按工作租户隔离的参数 CRUD、内置参数（{@code configType='Y'}）防删改键保护（{@code 102007}）、
 * {@code sys.user.initPassword} 强口令策略校验、以及非 {@code 0} 租户未命中时自动回退平台默认租户的缓存兜底读取。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Service
@RequiredArgsConstructor
public class SysConfigService {

    private static final Pattern CONFIG_KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]{2,100}$");

    private final SystemContextSupport contextSupport;
    private final SysConfigMapper sysConfigMapper;
    private final SysConfigCache sysConfigCache;

    /**
     * 分页查询当前工作租户下的系统参数列表。
     *
     * @param query 分页与筛选参数
     * @return 系统参数分页结果
     */
    public PageResult<ConfigVO> pageConfigs(ConfigQueryRequest query) {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        ConfigQueryRequest safeQuery = query != null ? query : new ConfigQueryRequest();
        Page<SysConfig> pageRequest = PageUtil.toPage(
                safeQuery, SysConfig.class, true, SystemContextSupport.DEFAULT_MAX_PAGE_SIZE
        );

        String configType = SystemContextSupport.trimToNull(safeQuery.getConfigType());
        LambdaQueryWrapper<SysConfig> wrapper = Wrappers.<SysConfig>lambdaQuery()
                .eq(SysConfig::getTenantId, tenantId)
                .eq(configType != null, SysConfig::getConfigType, configType);
        SystemContextSupport.applyContainsLike(wrapper, SysConfig::getConfigName, safeQuery.getConfigName());
        SystemContextSupport.applyContainsLike(wrapper, SysConfig::getConfigKey, safeQuery.getConfigKey());
        if (CollectionUtils.isEmpty(pageRequest.orders())) {
            wrapper.orderByAsc(SysConfig::getId);
        }

        Page<SysConfig> result = TenantContextHolder.runWithTenant(
                tenantId,
                () -> sysConfigMapper.selectPage(pageRequest, wrapper)
        );
        return SystemContextSupport.toPageResult(result, SysConfigService::toVO);
    }

    /**
     * 查询当前工作租户下的指定系统参数详情。
     *
     * @param id 参数主键
     * @return 参数详情
     */
    public ConfigVO getDetail(Long id) {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        return toVO(requireConfigInTenant(tenantId, id));
    }

    /**
     * 按参数键名读取当前工作租户的参数值；当多租户模式开启且当前租户（{@code tenantId != 0}）未配置该键时，
     * 自动在平台默认租户 {@code 0} 下回退读取全局默认值（消费 30m 双模缓存）。
     *
     * @param configKey 参数键名
     * @return 参数键值；双方均未配置或入参空白时返回 {@code null}
     */
    public String getConfigValue(String configKey) {
        return getConfigValue(contextSupport.requireEffectiveTenantId(), configKey);
    }

    /**
     * 按显式指定租户与参数键名读取参数值；当目标租户（{@code tenantId != 0}）未配置该键时，
     * 自动在平台默认租户 {@code 0} 下回退读取全局默认值（供跨租户/异步场景直接消费 30m 双模缓存）。
     *
     * @param tenantId  目标工作租户主键（为空时按平台默认租户 {@code 0} 处理）
     * @param configKey 参数键名
     * @return 参数键值；双方均未配置或入参格式非法时返回 {@code null}
     */
    public String getConfigValue(Long tenantId, String configKey) {
        String normalizedKey = SystemContextSupport.trimToNull(configKey);
        if (normalizedKey == null || !CONFIG_KEY_PATTERN.matcher(normalizedKey).matches()) {
            return null;
        }
        Long effectiveTenantId = tenantId != null ? tenantId : SolidConstants.PLATFORM_TENANT_ID;
        if (effectiveTenantId < 0L) {
            return null;
        }
        String tenantValue = loadFromTenantWithCache(effectiveTenantId, normalizedKey);
        if (tenantValue == null && !SolidConstants.PLATFORM_TENANT_ID.equals(effectiveTenantId)) {
            return loadFromTenantWithCache(SolidConstants.PLATFORM_TENANT_ID, normalizedKey);
        }
        return tenantValue;
    }

    /**
     * 新增系统参数，仅允许创建业务自建类型（{@code N}），校验同租户键名唯一性与 {@code sys.user.initPassword} 强口令策略。
     *
     * @param request 参数保存请求
     * @return 新建参数主键
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createConfig(ConfigSaveRequest request) {
        if (request == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "系统参数请求不能为空");
        }
        Long tenantId = contextSupport.requireEffectiveTenantId();
        ConfigPayload payload = normalizePayload(request, null, true);

        Long createdId = TenantContextHolder.runWithTenant(tenantId, () -> {
            assertConfigKeyUnique(tenantId, payload.configKey(), null);
            SysConfig entity = new SysConfig();
            entity.setTenantId(tenantId);
            entity.setConfigName(payload.configName());
            entity.setConfigKey(payload.configKey());
            entity.setConfigValue(payload.configValue());
            entity.setConfigType(payload.configType());
            entity.setRemark(payload.remark());
            try {
                sysConfigMapper.insert(entity);
            } catch (DuplicateKeyException ex) {
                throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "参数键名在当前租户内已存在", ex);
            }
            return entity.getId();
        });
        TransactionUtil.executeAfterCommit(() -> sysConfigCache.evict(tenantId, payload.configKey()));
        return createdId;
    }

    /**
     * 修改系统参数；系统内置项（{@code Y}）禁止修改键名与降级为自建（{@code 102007}），
     * 更新 {@code sys.user.initPassword} 时强制执行强口令校验，提交后驱逐新旧参数键缓存。
     *
     * @param request 参数修改请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateConfig(ConfigSaveRequest request) {
        if (request == null || request.getId() == null || request.getId() <= 0L) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "修改系统参数时主键不能为空");
        }
        Long tenantId = contextSupport.requireEffectiveTenantId();
        SysConfig existing = requireConfigInTenant(tenantId, request.getId());
        boolean isSystemBuiltin = ConfigTypeEnum.SYSTEM.getCode().equals(existing.getConfigType());

        ConfigPayload payload = normalizePayload(request, existing, false);
        if (isSystemBuiltin) {
            if (!existing.getConfigKey().equals(payload.configKey())) {
                throw new BusinessException(
                        SystemResultCode.SYSTEM_BUILTIN_PROTECTED,
                        "系统内置参数受保护，禁止修改参数键名"
                );
            }
            if (!ConfigTypeEnum.SYSTEM.getCode().equals(payload.configType())) {
                throw new BusinessException(
                        SystemResultCode.SYSTEM_BUILTIN_PROTECTED,
                        "系统内置参数受保护，禁止变更为业务自建类型"
                );
            }
        } else if (ConfigTypeEnum.SYSTEM.getCode().equals(payload.configType())) {
            throw new BusinessException(
                    CommonBizResultCode.PARAM_INVALID,
                    "业务自建参数创建后禁止变更为系统内置类型 Y"
            );
        }

        TenantContextHolder.runWithTenant(tenantId, () -> {
            assertConfigKeyUnique(tenantId, payload.configKey(), existing.getId());
            LambdaUpdateWrapper<SysConfig> wrapper = Wrappers.<SysConfig>lambdaUpdate()
                    .eq(SysConfig::getTenantId, tenantId)
                    .eq(SysConfig::getId, existing.getId())
                    .set(SysConfig::getConfigName, payload.configName())
                    .set(SysConfig::getConfigKey, payload.configKey())
                    .set(SysConfig::getConfigValue, payload.configValue())
                    .set(SysConfig::getConfigType, payload.configType())
                    .set(SysConfig::getRemark, payload.remark());
            try {
                sysConfigMapper.update(new SysConfig(), wrapper);
            } catch (DuplicateKeyException ex) {
                throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "参数键名在当前租户内已存在", ex);
            }
        });
        TransactionUtil.executeAfterCommit(() -> {
            sysConfigCache.evict(tenantId, existing.getConfigKey());
            if (!existing.getConfigKey().equals(payload.configKey())) {
                sysConfigCache.evict(tenantId, payload.configKey());
            }
        });
    }

    /**
     * 批量删除当前租户下的业务自建参数；任一目标项为系统内置参数（{@code Y}）时整批阻断抛出 {@code 102007}。
     *
     * @param ids 逗号分隔的参数主键串
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteConfigs(String ids) {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        List<Long> idList = SystemContextSupport.parsePathIds(ids);

        List<SysConfig> existingList = TenantContextHolder.runWithTenant(tenantId, () -> sysConfigMapper.selectList(
                Wrappers.<SysConfig>lambdaQuery()
                        .eq(SysConfig::getTenantId, tenantId)
                        .in(SysConfig::getId, idList)
        ));
        if (existingList == null || existingList.size() != idList.size()) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "部分或全部待删除系统参数不存在");
        }
        for (SysConfig item : existingList) {
            if (ConfigTypeEnum.SYSTEM.getCode().equals(item.getConfigType())) {
                throw new BusinessException(
                        SystemResultCode.SYSTEM_BUILTIN_PROTECTED,
                        "系统内置参数【" + item.getConfigName() + "】受保护，禁止删除"
                );
            }
        }

        List<String> affectedKeys = existingList.stream().map(SysConfig::getConfigKey).toList();
        TenantContextHolder.runWithTenant(tenantId, () -> sysConfigMapper.deleteByIds(idList));
        TransactionUtil.executeAfterCommit(() -> affectedKeys.forEach(key -> sysConfigCache.evict(tenantId, key)));
    }

    private String loadFromTenantWithCache(Long targetTenantId, String configKey) {
        return sysConfigCache.getOrLoad(targetTenantId, configKey, () ->
                TenantContextHolder.runWithTenant(targetTenantId, () -> {
                    SysConfig record = sysConfigMapper.selectOne(
                            Wrappers.<SysConfig>lambdaQuery()
                                    .select(SysConfig::getConfigValue)
                                    .eq(SysConfig::getTenantId, targetTenantId)
                                    .eq(SysConfig::getConfigKey, configKey)
                    );
                    return record != null ? record.getConfigValue() : null;
                })
        );
    }

    private SysConfig requireConfigInTenant(Long tenantId, Long id) {
        if (id == null || id <= 0L) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "目标系统参数不存在");
        }
        SysConfig config = TenantContextHolder.runWithTenant(tenantId, () -> sysConfigMapper.selectOne(
                Wrappers.<SysConfig>lambdaQuery()
                        .eq(SysConfig::getTenantId, tenantId)
                        .eq(SysConfig::getId, id)
        ));
        if (config == null) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "目标系统参数不存在");
        }
        return config;
    }

    private void assertConfigKeyUnique(Long tenantId, String configKey, Long excludeId) {
        Long count = sysConfigMapper.selectCount(
                Wrappers.<SysConfig>lambdaQuery()
                        .eq(SysConfig::getTenantId, tenantId)
                        .eq(SysConfig::getConfigKey, configKey)
                        .ne(excludeId != null, SysConfig::getId, excludeId)
        );
        if (count != null && count > 0L) {
            throw new BusinessException(SystemResultCode.DUPLICATE_RESOURCE, "参数键名【" + configKey + "】在当前租户内已存在");
        }
    }

    private static ConfigPayload normalizePayload(ConfigSaveRequest request, SysConfig existing, boolean isCreate) {
        String configName = SystemContextSupport.trimToNull(request.getConfigName());
        String configKey = SystemContextSupport.trimToNull(request.getConfigKey());
        if (configName == null || configName.length() < 2 || configName.length() > 100) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "参数名称长度须在 2~100 个字符之间");
        }
        if (configKey == null || !CONFIG_KEY_PATTERN.matcher(configKey).matches()) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "参数键名仅支持字母、数字、点、下划线和短横线（2~100 位）");
        }

        // 口令类配置必须基于原始未裁剪值执行强口令校验，杜绝首尾空白字符被静默 trim 放行
        String configValue;
        if (SolidConstants.INIT_PASSWORD_CONFIG_KEY.equals(configKey)) {
            String rawPassword = request.getConfigValue();
            PasswordPolicyValidator.validate(rawPassword);
            configValue = rawPassword;
        } else {
            configValue = SystemContextSupport.trimToNull(request.getConfigValue());
            if (!StringUtils.hasText(configValue) || configValue.length() > 2000) {
                throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "参数键值长度须在 1~2000 个字符之间");
            }
        }

        String requestedType = SystemContextSupport.trimToNull(request.getConfigType());
        String configType;
        if (isCreate) {
            if (requestedType != null && !ConfigTypeEnum.BUSINESS.getCode().equals(requestedType)) {
                throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "新建系统参数仅允许使用业务自建类型 N");
            }
            configType = ConfigTypeEnum.BUSINESS.getCode();
        } else if (requestedType != null) {
            ConfigTypeEnum parsedType = EnumUtil.fromCode(ConfigTypeEnum.class, requestedType).orElseThrow(
                    () -> new BusinessException(CommonBizResultCode.PARAM_INVALID, "参数类型仅支持 Y（系统内置）或 N（业务自建）")
            );
            configType = parsedType.getCode();
        } else {
            configType = existing != null ? existing.getConfigType() : ConfigTypeEnum.BUSINESS.getCode();
        }

        String remark = SystemContextSupport.trimToNull(request.getRemark());
        if (remark != null && remark.length() > 500) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "备注长度不能超过 500 个字符");
        }
        return new ConfigPayload(configName, configKey, configValue, configType, remark);
    }

    private record ConfigPayload(
            String configName,
            String configKey,
            String configValue,
            String configType,
            String remark
    ) {
    }

    private static ConfigVO toVO(SysConfig entity) {
        return ConfigVO.builder()
                .id(entity.getId())
                .configName(entity.getConfigName())
                .configKey(entity.getConfigKey())
                .configValue(entity.getConfigValue())
                .configType(entity.getConfigType())
                .remark(entity.getRemark())
                .createTime(entity.getCreateTime())
                .build();
    }
}

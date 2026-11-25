package io.github.atengk.solid.system.audit;

import io.github.atengk.boot.core.audit.OperateLogModel;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.web.audit.OperateLogStorageProvider;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.LogStatusEnum;
import io.github.atengk.solid.system.entity.SysOperLog;
import io.github.atengk.solid.system.mapper.SysOperLogMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.support.SystemContextSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 操作审计日志异步持久化存储实现：在脱离 HTTP/Sa-Token 上下文的异步工作线程中，基于 {@link OperateLogModel}
 * 携带的租户与用户主键单点查询真实操作人账号，脱敏技术错误后落库 {@code sys_oper_log}。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SolidOperateLogStorageProvider implements OperateLogStorageProvider {

    private static final int MAX_SHORT_COL_LEN = 64;
    private static final int MAX_METHOD_LEN = 16;
    private static final int MAX_LOC_LEN = 128;
    private static final int MAX_DESC_LEN = 255;
    private static final int MAX_URL_LEN = 500;
    private static final int MAX_TEXT_COL_LEN = 16_000;

    private final SysOperLogMapper sysOperLogMapper;
    private final SysUserMapper sysUserMapper;

    @Override
    public void store(OperateLogModel logModel) {
        if (logModel == null) {
            return;
        }
        Long effectiveTenantId = parseLongOrDefault(logModel.getTenantId(), SolidConstants.PLATFORM_TENANT_ID, "tenantId");
        Long userId = parseLongOrDefault(logModel.getUserId(), null, "userId");

        try {
            TenantContextHolder.runWithTenant(effectiveTenantId, () ->
                    DataPermissionContextHolder.runWithoutPermission(() -> {
                        String resolvedUserName = resolveRealUsername(effectiveTenantId, userId, logModel.getUserName());
                        SysOperLog entity = buildEntity(logModel, effectiveTenantId, userId, resolvedUserName);
                        sysOperLogMapper.insert(entity);
                    })
            );
        } catch (RuntimeException ex) {
            log.error("操作审计日志落库失败: traceId={}, tenantId={}, module={}",
                    logModel.getTraceId(), effectiveTenantId, logModel.getModule(), ex);
        }
    }

    private String resolveRealUsername(Long effectiveTenantId, Long userId, String fallbackUserName) {
        if (userId != null) {
            String username = sysUserMapper.selectUsernameById(userId);
            // 平台超管通过 X-Tenant-Id 切换至普通租户视角操作时，其用户记录归属平台租户 0
            if (!StringUtils.hasText(username) && !SolidConstants.PLATFORM_TENANT_ID.equals(effectiveTenantId)) {
                username = TenantContextHolder.runWithTenant(
                        SolidConstants.PLATFORM_TENANT_ID,
                        () -> sysUserMapper.selectUsernameById(userId)
                );
            }
            if (StringUtils.hasText(username)) {
                return username.trim();
            }
        }
        return SystemContextSupport.trimToNull(fallbackUserName);
    }

    private static SysOperLog buildEntity(
            OperateLogModel model,
            Long effectiveTenantId,
            Long userId,
            String resolvedUserName
    ) {
        SysOperLog entity = new SysOperLog();
        entity.setTenantId(effectiveTenantId);
        entity.setTraceId(SystemContextSupport.truncate(model.getTraceId(), MAX_SHORT_COL_LEN));
        entity.setUserId(userId);
        entity.setUserName(SystemContextSupport.truncate(resolvedUserName, MAX_SHORT_COL_LEN));
        String module = SystemContextSupport.truncate(model.getModule(), MAX_SHORT_COL_LEN);
        entity.setModule(module != null ? module : "");
        entity.setOperateType(model.getOperateType() != null
                ? model.getOperateType().getCode()
                : OperateTypeEnum.OTHER.getCode());
        entity.setDescription(SystemContextSupport.truncate(model.getDescription(), MAX_DESC_LEN));
        entity.setRequestMethod(SystemContextSupport.truncate(model.getRequestMethod(), MAX_METHOD_LEN));
        entity.setRequestUrl(SystemContextSupport.truncate(model.getRequestUrl(), MAX_URL_LEN));
        entity.setClientIp(SystemContextSupport.truncate(model.getClientIp(), MAX_SHORT_COL_LEN));
        entity.setOperateLocation(SystemContextSupport.truncate(model.getOperateLocation(), MAX_LOC_LEN));
        entity.setBrowser(SystemContextSupport.truncate(model.getBrowser(), MAX_SHORT_COL_LEN));
        entity.setOs(SystemContextSupport.truncate(model.getOs(), MAX_SHORT_COL_LEN));
        entity.setRequestParams(SystemContextSupport.truncate(model.getRequestParams(), MAX_TEXT_COL_LEN));
        entity.setResponseResult(SystemContextSupport.truncate(model.getResponseResult(), MAX_TEXT_COL_LEN));
        entity.setStatus(model.getStatus() != null ? model.getStatus() : LogStatusEnum.SUCCESS.getCode());
        entity.setCostTime(model.getCostTime() != null ? model.getCostTime() : 0L);
        entity.setErrorMsg(SystemContextSupport.sanitizeAuditErrorMsg(model.getErrorMsg()));
        entity.setExtraJson(model.getExtra() != null && !model.getExtra().isEmpty()
                ? SystemContextSupport.truncate(JsonUtil.toJsonString(model.getExtra()), MAX_TEXT_COL_LEN)
                : null);
        entity.setOperateTime(model.getOperateTime() != null ? model.getOperateTime() : LocalDateTime.now());
        return entity;
    }

    private static Long parseLongOrDefault(String raw, Long fallback, String fieldName) {
        String trimmed = SystemContextSupport.trimToNull(raw);
        if (trimmed == null) {
            return fallback;
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ex) {
            log.warn("操作审计上下文数字字段解析失败，已回退默认值: field={}, raw={}", fieldName, trimmed);
            return fallback;
        }
    }
}

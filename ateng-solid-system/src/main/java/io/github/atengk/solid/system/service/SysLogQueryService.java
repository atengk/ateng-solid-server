package io.github.atengk.solid.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.mybatisplus.util.PageUtil;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysLoginLog;
import io.github.atengk.solid.system.entity.SysOperLog;
import io.github.atengk.solid.system.mapper.SysLoginLogMapper;
import io.github.atengk.solid.system.mapper.SysOperLogMapper;
import io.github.atengk.solid.system.request.LoginLogQueryRequest;
import io.github.atengk.solid.system.request.OperLogQueryRequest;
import io.github.atengk.solid.system.support.SystemContextSupport;
import io.github.atengk.solid.system.vo.LoginLogVO;
import io.github.atengk.solid.system.vo.OperLogVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;

/**
 * 审计日志只读检索服务，按当前工作租户隔离提供操作审计分页/详情与登录审计分页查询，按发生时间倒序输出。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Service
@RequiredArgsConstructor
public class SysLogQueryService {

    private final SystemContextSupport contextSupport;
    private final SysOperLogMapper sysOperLogMapper;
    private final SysLoginLogMapper sysLoginLogMapper;

    /**
     * 分页查询当前工作租户下的操作审计日志（默认按操作时间与主键倒序）。
     *
     * @param query 分页与筛选参数
     * @return 操作日志分页结果
     */
    public PageResult<OperLogVO> pageOperLogs(OperLogQueryRequest query) {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        OperLogQueryRequest safeQuery = query != null ? query : new OperLogQueryRequest();
        assertValidTimeRange(safeQuery.getBeginTime(), safeQuery.getEndTime());

        Page<SysOperLog> pageRequest = PageUtil.toPage(
                safeQuery, SysOperLog.class, true, SystemContextSupport.DEFAULT_MAX_PAGE_SIZE
        );
        String module = SystemContextSupport.trimToNull(safeQuery.getModule());
        String userName = SystemContextSupport.trimToNull(safeQuery.getUserName());
        String traceId = SystemContextSupport.trimToNull(safeQuery.getTraceId());

        LambdaQueryWrapper<SysOperLog> wrapper = Wrappers.<SysOperLog>lambdaQuery()
                .eq(SysOperLog::getTenantId, tenantId)
                .like(module != null, SysOperLog::getModule, module)
                .like(userName != null, SysOperLog::getUserName, userName)
                .eq(safeQuery.getOperateType() != null, SysOperLog::getOperateType, safeQuery.getOperateType())
                .eq(safeQuery.getStatus() != null, SysOperLog::getStatus, safeQuery.getStatus())
                .eq(traceId != null, SysOperLog::getTraceId, traceId)
                .ge(safeQuery.getBeginTime() != null, SysOperLog::getOperateTime, safeQuery.getBeginTime())
                .le(safeQuery.getEndTime() != null, SysOperLog::getOperateTime, safeQuery.getEndTime());
        if (CollectionUtils.isEmpty(safeQuery.getSorts())) {
            wrapper.orderByDesc(SysOperLog::getOperateTime).orderByDesc(SysOperLog::getId);
        }

        Page<SysOperLog> result = TenantContextHolder.runWithTenant(
                tenantId,
                () -> sysOperLogMapper.selectPage(pageRequest, wrapper)
        );
        return SystemContextSupport.toPageResult(result, SysLogQueryService::toOperLogVO);
    }

    /**
     * 查询当前工作租户下的单条操作审计日志详情。
     *
     * @param id 操作日志主键
     * @return 操作日志详情
     * @throws BusinessException 日志不存在或非当前租户所属时抛出 {@code 102001}
     */
    public OperLogVO getOperLogDetail(Long id) {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        if (id == null || id <= 0L) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "操作日志不存在");
        }
        SysOperLog entity = TenantContextHolder.runWithTenant(tenantId, () -> sysOperLogMapper.selectOne(
                Wrappers.<SysOperLog>lambdaQuery()
                        .eq(SysOperLog::getTenantId, tenantId)
                        .eq(SysOperLog::getId, id)
        ));
        if (entity == null) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "操作日志不存在");
        }
        return toOperLogVO(entity);
    }

    /**
     * 分页查询当前工作租户下的登录审计日志（默认按登录时间与主键倒序）。
     *
     * @param query 分页与筛选参数
     * @return 登录日志分页结果
     */
    public PageResult<LoginLogVO> pageLoginLogs(LoginLogQueryRequest query) {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        LoginLogQueryRequest safeQuery = query != null ? query : new LoginLogQueryRequest();
        assertValidTimeRange(safeQuery.getBeginTime(), safeQuery.getEndTime());

        Page<SysLoginLog> pageRequest = PageUtil.toPage(
                safeQuery, SysLoginLog.class, true, SystemContextSupport.DEFAULT_MAX_PAGE_SIZE
        );
        String username = SystemContextSupport.trimToNull(safeQuery.getUsername());
        String clientIp = SystemContextSupport.trimToNull(safeQuery.getClientIp());
        String traceId = SystemContextSupport.trimToNull(safeQuery.getTraceId());

        LambdaQueryWrapper<SysLoginLog> wrapper = Wrappers.<SysLoginLog>lambdaQuery()
                .eq(SysLoginLog::getTenantId, tenantId)
                .like(username != null, SysLoginLog::getUsername, username)
                .like(clientIp != null, SysLoginLog::getClientIp, clientIp)
                .eq(safeQuery.getStatus() != null, SysLoginLog::getStatus, safeQuery.getStatus())
                .eq(traceId != null, SysLoginLog::getTraceId, traceId)
                .ge(safeQuery.getBeginTime() != null, SysLoginLog::getLoginTime, safeQuery.getBeginTime())
                .le(safeQuery.getEndTime() != null, SysLoginLog::getLoginTime, safeQuery.getEndTime());
        if (CollectionUtils.isEmpty(safeQuery.getSorts())) {
            wrapper.orderByDesc(SysLoginLog::getLoginTime).orderByDesc(SysLoginLog::getId);
        }

        Page<SysLoginLog> result = TenantContextHolder.runWithTenant(
                tenantId,
                () -> sysLoginLogMapper.selectPage(pageRequest, wrapper)
        );
        return SystemContextSupport.toPageResult(result, SysLogQueryService::toLoginLogVO);
    }

    private static void assertValidTimeRange(LocalDateTime beginTime, LocalDateTime endTime) {
        if (beginTime != null && endTime != null && beginTime.isAfter(endTime)) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "查询开始时间不能晚于结束时间");
        }
    }

    private static OperLogVO toOperLogVO(SysOperLog entity) {
        return OperLogVO.builder()
                .id(entity.getId())
                .traceId(entity.getTraceId())
                .userId(entity.getUserId())
                .userName(entity.getUserName())
                .module(entity.getModule())
                .operateType(entity.getOperateType())
                .description(entity.getDescription())
                .requestMethod(entity.getRequestMethod())
                .requestUrl(entity.getRequestUrl())
                .clientIp(entity.getClientIp())
                .operateLocation(entity.getOperateLocation())
                .browser(entity.getBrowser())
                .os(entity.getOs())
                .requestParams(entity.getRequestParams())
                .responseResult(entity.getResponseResult())
                .status(entity.getStatus())
                .costTime(entity.getCostTime())
                .errorMsg(SystemContextSupport.sanitizeAuditErrorMsg(entity.getErrorMsg()))
                .extraJson(entity.getExtraJson())
                .operateTime(entity.getOperateTime())
                .build();
    }

    private static LoginLogVO toLoginLogVO(SysLoginLog entity) {
        return LoginLogVO.builder()
                .id(entity.getId())
                .traceId(entity.getTraceId())
                .username(entity.getUsername())
                .loginType(entity.getLoginType())
                .clientIp(entity.getClientIp())
                .loginLocation(entity.getLoginLocation())
                .browser(entity.getBrowser())
                .os(entity.getOs())
                .status(entity.getStatus())
                .msg(entity.getMsg())
                .loginTime(entity.getLoginTime())
                .build();
    }
}

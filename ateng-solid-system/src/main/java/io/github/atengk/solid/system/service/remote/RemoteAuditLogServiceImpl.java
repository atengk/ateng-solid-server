package io.github.atengk.solid.system.service.remote;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.model.UserAgentInfo;
import io.github.atengk.boot.core.util.UserAgentUtil;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.LogStatusEnum;
import io.github.atengk.solid.system.api.command.LoginAuditCommand;
import io.github.atengk.solid.system.api.service.RemoteAuditLogService;
import io.github.atengk.solid.system.entity.SysLoginLog;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.mapper.SysLoginLogMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.support.RedisActivationResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 审计日志跨模块远程落库服务实现：负责基于 {@code eventId} 的幂等登录日志写库、User-Agent 解析以及登录成功后的用户最近登录轨迹同步。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RemoteAuditLogServiceImpl implements RemoteAuditLogService {

    private static final String LOGIN_EVENT_IDEMPOTENT_PREFIX = "ateng:solid:audit:login:event:";
    private static final Duration EVENT_IDEMPOTENT_TTL = Duration.ofHours(24);
    private static final int LOCAL_DEDUP_MAX_CAPACITY = 10_000;
    private static final int MAX_SHORT_COL_LEN = 64;
    private static final int MAX_LOGIN_TYPE_LEN = 32;
    private static final int MAX_MSG_LEN = 500;

    private final SysLoginLogMapper sysLoginLogMapper;
    private final SysUserMapper sysUserMapper;
    private final TransactionTemplate transactionTemplate;
    private final ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider;
    private final ObjectProvider<Environment> environmentProvider;

    private final ReentrantLock localDedupLock = new ReentrantLock();
    private final Map<String, Boolean> localProcessedEvents = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > LOCAL_DEDUP_MAX_CAPACITY;
        }
    };

    @Override
    public void recordLoginAudit(LoginAuditCommand cmd) {
        if (cmd == null) {
            return;
        }
        String eventId = StringUtils.hasText(cmd.getEventId()) ? cmd.getEventId().trim() : null;
        if (eventId != null && !tryAcquireEventProcessing(eventId)) {
            log.debug("登录审计事件已幂等消费，跳过重复落库: eventId={}", eventId);
            return;
        }

        try {
            persistLoginAudit(cmd);
        } catch (RuntimeException ex) {
            releaseEventProcessing(eventId);
            throw ex;
        }
    }

    private void persistLoginAudit(LoginAuditCommand cmd) {
        Long effectiveTenantId = cmd.getTenantId() != null ? cmd.getTenantId() : SolidConstants.PLATFORM_TENANT_ID;
        LocalDateTime loginTime = cmd.getOccurredAt() != null ? cmd.getOccurredAt() : LocalDateTime.now();
        UserAgentInfo uaInfo = UserAgentUtil.parse(cmd.getUserAgent());
        String browser = formatClientMetric(uaInfo.browser(), uaInfo.browserVersion());
        String os = formatClientMetric(uaInfo.os(), uaInfo.osVersion());
        String clientIp = truncate(cmd.getClientIp(), MAX_SHORT_COL_LEN);
        Integer status = cmd.getStatus() != null ? cmd.getStatus() : LogStatusEnum.FAIL.getCode();

        TenantContextHolder.runWithTenant(effectiveTenantId, () -> transactionTemplate.executeWithoutResult(tx -> {
            SysLoginLog loginLog = new SysLoginLog();
            loginLog.setTenantId(effectiveTenantId);
            loginLog.setTraceId(truncate(cmd.getTraceId(), MAX_SHORT_COL_LEN));
            loginLog.setUsername(truncateOrEmpty(cmd.getUsername(), MAX_SHORT_COL_LEN));
            loginLog.setLoginType(truncateOrDefault(
                    cmd.getLoginType(), MAX_LOGIN_TYPE_LEN, SolidConstants.ADMIN_LOGIN_TYPE));
            loginLog.setClientIp(clientIp);
            loginLog.setBrowser(truncate(browser, MAX_SHORT_COL_LEN));
            loginLog.setOs(truncate(os, MAX_SHORT_COL_LEN));
            loginLog.setStatus(status);
            loginLog.setMsg(truncate(cmd.getMessage(), MAX_MSG_LEN));
            loginLog.setLoginTime(loginTime);
            sysLoginLogMapper.insert(loginLog);

            if (LogStatusEnum.SUCCESS.getCode().equals(status) && cmd.getUserId() != null) {
                sysUserMapper.update(
                        new SysUser(),
                        Wrappers.<SysUser>lambdaUpdate()
                                .eq(SysUser::getTenantId, effectiveTenantId)
                                .eq(SysUser::getId, cmd.getUserId())
                                .set(SysUser::getLoginIp, clientIp)
                                .set(SysUser::getLoginDate, loginTime)
                );
            }
        }));
    }

    private boolean tryAcquireEventProcessing(String eventId) {
        StringRedisTemplate redis = RedisActivationResolver.resolveActive(
                stringRedisTemplateProvider,
                environmentProvider
        );
        if (redis != null) {
            try {
                Boolean first = redis.opsForValue().setIfAbsent(
                        LOGIN_EVENT_IDEMPOTENT_PREFIX + eventId,
                        "1",
                        EVENT_IDEMPOTENT_TTL
                );
                return Boolean.TRUE.equals(first);
            } catch (RuntimeException ex) {
                log.warn("Redis 登录审计幂等锁异常，降级使用本地有界去重表: eventId={}", eventId, ex);
            }
        }
        localDedupLock.lock();
        try {
            return localProcessedEvents.putIfAbsent(eventId, Boolean.TRUE) == null;
        } finally {
            localDedupLock.unlock();
        }
    }

    private void releaseEventProcessing(String eventId) {
        if (eventId == null) {
            return;
        }
        localDedupLock.lock();
        try {
            localProcessedEvents.remove(eventId);
        } finally {
            localDedupLock.unlock();
        }
        StringRedisTemplate redis = RedisActivationResolver.resolveActive(
                stringRedisTemplateProvider,
                environmentProvider
        );
        if (redis != null) {
            try {
                redis.delete(LOGIN_EVENT_IDEMPOTENT_PREFIX + eventId);
            } catch (RuntimeException ex) {
                log.warn("回滚删除 Redis 登录审计幂等键失败: eventId={}", eventId, ex);
            }
        }
    }

    private static String formatClientMetric(String name, String version) {
        if (!StringUtils.hasText(name)) {
            return UserAgentInfo.UNKNOWN;
        }
        String cleanName = name.trim();
        if (!StringUtils.hasText(version) || UserAgentInfo.UNKNOWN.equalsIgnoreCase(version.trim())) {
            return cleanName;
        }
        return cleanName + " " + version.trim();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private static String truncateOrEmpty(String value, int maxLength) {
        return value == null ? "" : truncate(value, maxLength);
    }

    private static String truncateOrDefault(String value, int maxLength, String fallback) {
        return StringUtils.hasText(value) ? truncate(value, maxLength) : fallback;
    }
}

package io.github.atengk.solid.auth.service;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.satoken.model.OnlineUser;
import io.github.atengk.boot.satoken.model.OnlineUserQuery;
import io.github.atengk.boot.satoken.util.LoginHelper;
import io.github.atengk.solid.auth.request.OnlineSessionQuery;
import io.github.atengk.solid.auth.result.AuthResultCode;
import io.github.atengk.solid.auth.security.OpaqueSessionHandleCodec;
import io.github.atengk.solid.auth.vo.OnlineSessionVO;
import io.github.atengk.solid.common.constant.SolidConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 在线会话治理服务：按当前生效租户隔离分页检索在线会话、签密下发不透明操作句柄并执行防自杀与防越权强退。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Service
@RequiredArgsConstructor
public class OnlineSessionService {

    private static final int MAX_PAGE_SIZE = 100;

    private final OpaqueSessionHandleCodec opaqueSessionHandleCodec;

    /**
     * 分页查询当前生效工作租户下的在线 {@code admin} 会话列表，绝不下发原始访问令牌。
     *
     * @param query 分页与筛选条件
     * @return 在线会话分页结果
     * @throws BusinessException 未登录或无法识别租户上下文时抛出 {@code 401}
     */
    public PageResult<OnlineSessionVO> page(OnlineSessionQuery query) {
        String effectiveTenantId = AuthContextSupport.resolveEffectiveTenantIdString();
        long current = query != null ? query.safeCurrent() : 1L;
        long size = query != null ? Math.min(query.safeSize(), MAX_PAGE_SIZE) : 10L;

        OnlineUserQuery baseQuery = OnlineUserQuery.builder()
                .tenantId(effectiveTenantId)
                .userAccount(query != null ? trimToNull(query.getUsername()) : null)
                .loginIp(query != null ? trimToNull(query.getClientIp()) : null)
                .build();

        PageResult<OnlineUser> rawPage = LoginHelper.searchOnlineUserPage(
                SolidConstants.ADMIN_LOGIN_TYPE,
                baseQuery,
                (int) current,
                (int) size
        );
        if (rawPage == null) {
            return PageResult.empty(current, size);
        }
        if (CollectionUtils.isEmpty(rawPage.getRecords())) {
            return PageResult.of(
                    Collections.emptyList(),
                    rawPage.getTotal(),
                    rawPage.getCurrent(),
                    rawPage.getSize(),
                    rawPage.getPages()
            );
        }

        String currentToken = LoginHelper.getTokenValue(SolidConstants.ADMIN_LOGIN_TYPE);
        List<OnlineSessionVO> records = rawPage.getRecords().stream()
                .map(onlineUser -> toOnlineSessionVO(onlineUser, effectiveTenantId, currentToken))
                .toList();

        return PageResult.of(
                records,
                rawPage.getTotal(),
                rawPage.getCurrent(),
                rawPage.getSize(),
                rawPage.getPages()
        );
    }

    /**
     * 校验并解密不透明会话句柄后强退目标在线会话；目标已自然过期时幂等返回成功。
     *
     * @param sessionId 无状态 AES-256-GCM 签密会话句柄
     * @throws BusinessException 句柄无效/跨租户 AAD 不匹配抛出 {@code 400}，强退自身抛出 {@code 101007}，跨租户强退抛出 {@code 403}
     */
    public void kickout(String sessionId) {
        String effectiveTenantId = AuthContextSupport.resolveEffectiveTenantIdString();
        String realToken = opaqueSessionHandleCodec.decode(
                SolidConstants.ADMIN_LOGIN_TYPE,
                effectiveTenantId,
                sessionId
        );
        String currentToken = LoginHelper.getTokenValue(SolidConstants.ADMIN_LOGIN_TYPE);
        if (Objects.equals(currentToken, realToken)) {
            throw new BusinessException(AuthResultCode.CANNOT_KICKOUT_SELF);
        }
        LoginHelper.kickoutByTokenForTenant(SolidConstants.ADMIN_LOGIN_TYPE, realToken, effectiveTenantId);
    }

    private OnlineSessionVO toOnlineSessionVO(
            OnlineUser onlineUser,
            String effectiveTenantId,
            String currentToken
    ) {
        String rawToken = onlineUser.getTokenValue();
        String sessionHandle = opaqueSessionHandleCodec.encode(
                SolidConstants.ADMIN_LOGIN_TYPE,
                effectiveTenantId,
                rawToken
        );
        String tokenMask = opaqueSessionHandleCodec.maskToken(rawToken);
        LocalDateTime loginTime = onlineUser.getLoginTime() != null
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(onlineUser.getLoginTime()), ZoneId.systemDefault())
                : null;
        long tokenTimeout = onlineUser.getTokenTimeout() != null ? onlineUser.getTokenTimeout() : 0L;

        return OnlineSessionVO.builder()
                .sessionId(sessionHandle)
                .tokenMask(tokenMask)
                .currentSession(Objects.equals(currentToken, rawToken))
                .userId(AuthContextSupport.parseLongOrNull(onlineUser.getUserId()))
                .username(onlineUser.getUserAccount())
                .nickname(onlineUser.getUserName())
                .tenantId(AuthContextSupport.parseLongOrNull(onlineUser.getTenantId()))
                .loginDevice(onlineUser.getLoginDevice())
                .clientIp(onlineUser.getLoginIp())
                .loginTime(loginTime)
                .tokenTimeout(tokenTimeout)
                .build();
    }

    private static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}

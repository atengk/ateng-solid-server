package io.github.atengk.solid.auth.service;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.satoken.model.OnlineUser;
import io.github.atengk.boot.satoken.model.OnlineUserQuery;
import io.github.atengk.boot.satoken.util.LoginHelper;
import io.github.atengk.solid.auth.request.OnlineSessionQuery;
import io.github.atengk.solid.auth.security.OpaqueSessionHandleCodec;
import io.github.atengk.solid.auth.vo.OnlineSessionVO;
import io.github.atengk.solid.common.constant.SolidConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * {@link OnlineSessionService} 时区锁定（{@code Asia/Shanghai}）与越界分页边界防御 Layer 1 单元测试。
 *
 * @author Ateng
 * @since 2026-11-27
 */
class OnlineSessionServiceTest {

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("在线会话 loginTime 应当固定按 Asia/Shanghai 转换，不受 JVM 默认时区漂移影响")
    void shouldFormatLoginTimeUsingFixedAsiaShanghaiZone() {
        TimeZone previousTz = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            OpaqueSessionHandleCodec codec = mock(OpaqueSessionHandleCodec.class);
            when(codec.encode(SolidConstants.ADMIN_LOGIN_TYPE, "0", "tok-1")).thenReturn("opaque-1");
            when(codec.maskToken("tok-1")).thenReturn("tok-****1");

            long loginMillis = Instant.parse("2026-11-27T01:30:00Z").toEpochMilli();
            OnlineUser rawUser = OnlineUser.builder()
                    .tokenValue("tok-1")
                    .userId("1001")
                    .userAccount("alice")
                    .userName("爱丽丝")
                    .tenantId("0")
                    .loginDevice("PC")
                    .loginIp("127.0.0.1")
                    .loginTime(loginMillis)
                    .tokenTimeout(86400L)
                    .build();

            OnlineSessionService service = new OnlineSessionService(codec);
            TenantContextHolder.setTenantId("0");
            try (MockedStatic<LoginHelper> helper = Mockito.mockStatic(LoginHelper.class)) {
                helper.when(LoginHelper::getOnlineSessionMaxScanLimit).thenReturn(2000);
                helper.when(() -> LoginHelper.searchOnlineUserPage(
                        eq(SolidConstants.ADMIN_LOGIN_TYPE),
                        any(OnlineUserQuery.class),
                        eq(1),
                        eq(10)
                )).thenReturn(PageResult.of(List.of(rawUser), 1L, 1L, 10L, 1L));
                helper.when(() -> LoginHelper.getTokenValue(SolidConstants.ADMIN_LOGIN_TYPE)).thenReturn("tok-1");

                PageResult<OnlineSessionVO> page = service.page(new OnlineSessionQuery());

                assertThat(page.getRecords()).hasSize(1);
                assertThat(page.getRecords().getFirst().getLoginTime())
                        .isEqualTo(LocalDateTime.of(2026, 11, 27, 9, 30, 0));
                assertThat(ZoneId.systemDefault().getId()).isEqualTo("UTC");
            }
        } finally {
            TimeZone.setDefault(previousTz);
        }
    }

    @Test
    @DisplayName("超出 Integer.MAX_VALUE 或最大扫描上限的页码应当直接返回空分页，绝不穿透到底层 int 强转")
    void shouldReturnEmptyPageWhenCurrentOverflowsOrExceedsScanLimit() {
        OpaqueSessionHandleCodec codec = mock(OpaqueSessionHandleCodec.class);
        OnlineSessionService service = new OnlineSessionService(codec);
        TenantContextHolder.setTenantId("0");

        try (MockedStatic<LoginHelper> helper = Mockito.mockStatic(LoginHelper.class)) {
            helper.when(LoginHelper::getOnlineSessionMaxScanLimit).thenReturn(2000);

            OnlineSessionQuery overflowQuery = new OnlineSessionQuery();
            overflowQuery.setCurrent((long) Integer.MAX_VALUE + 5L);
            overflowQuery.setSize(100L);
            PageResult<OnlineSessionVO> overflowRes = service.page(overflowQuery);
            assertThat(overflowRes.getRecords()).isEmpty();
            assertThat(overflowRes.getCurrent()).isEqualTo((long) Integer.MAX_VALUE + 5L);

            OnlineSessionQuery beyondScanQuery = new OnlineSessionQuery();
            beyondScanQuery.setCurrent(21L);
            beyondScanQuery.setSize(100L);
            PageResult<OnlineSessionVO> beyondRes = service.page(beyondScanQuery);
            assertThat(beyondRes.getRecords()).isEmpty();
            assertThat(beyondRes.getCurrent()).isEqualTo(21L);

            helper.verify(() -> LoginHelper.searchOnlineUserPage(
                    any(), any(OnlineUserQuery.class), anyInt(), anyInt()), never());
        }
    }
}

package io.github.atengk.solid.auth.service;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.web.captcha.template.CaptchaTemplate;
import io.github.atengk.solid.auth.properties.SolidAuthProperties;
import io.github.atengk.solid.auth.request.UpdatePasswordRequest;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.common.spi.auth.AuthGrantCommand;
import io.github.atengk.solid.common.spi.auth.AuthGrantHandler;
import io.github.atengk.solid.common.spi.auth.AuthenticatedPrincipal;
import io.github.atengk.solid.system.api.service.RemotePermissionService;
import io.github.atengk.solid.system.api.service.RemoteUserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link AuthService} 重复授权策略 Fail-Fast 与 {@link ProfileService} 改密边界 Layer 1 单元测试。
 *
 * @author Ateng
 * @since 2026-11-27
 */
class AuthServiceAndProfileTest {

    @Test
    @DisplayName("存在重复 grantType 的 AuthGrantHandler 时 AuthService 构造应当 Fail-Fast 抛出 IllegalStateException")
    @SuppressWarnings("unchecked")
    void shouldFailFastWhenDuplicateAuthGrantHandlersRegistered() {
        AuthGrantHandler first = new StubGrantHandler("password");
        AuthGrantHandler duplicate = new StubGrantHandler("password");

        assertThatThrownBy(() -> new AuthService(
                List.of(first, duplicate),
                new SolidAuthProperties(),
                mock(ObjectProvider.class),
                mock(RemoteUserService.class),
                mock(RemotePermissionService.class),
                mock(TenantResolutionSupport.class),
                mock(AuthAuditSupport.class)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("password");
    }

    @Test
    @DisplayName("ProfileService#updatePassword 新旧密码相同时应当抛出 100001 且不触发远端用户查询")
    void shouldRejectUpdatePasswordWhenNewMatchesOld() {
        RemoteUserService remoteUserService = mock(RemoteUserService.class);
        ProfileService profileService = new ProfileService(remoteUserService);

        UpdatePasswordRequest request = new UpdatePasswordRequest();
        request.setOldPassword("Ateng@Old2026");
        request.setNewPassword("Ateng@Old2026");

        assertThatThrownBy(() -> profileService.updatePassword(request))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(CommonBizResultCode.PARAM_INVALID.getCode());

        verifyNoInteractions(remoteUserService);
    }

    private record StubGrantHandler(String grantType) implements AuthGrantHandler {

        @Override
        public String getGrantType() {
            return grantType;
        }

        @Override
        public AuthenticatedPrincipal authenticate(AuthGrantCommand command) {
            return null;
        }
    }
}

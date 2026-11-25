package io.github.atengk.solid.common.spi.auth;

/**
 * 认证授权策略扩展点（SPI），按授权模式 {@code grantType} 解耦主流程与具体凭据校验算法。
 *
 * @author Ateng
 * @since 2026-11-25
 */
public interface AuthGrantHandler {

    /**
     * 返回当前策略处理器支持的授权模式标识（例如 {@code "password"}）。
     *
     * @return 非空授权模式标识
     */
    String getGrantType();

    /**
     * 执行特定授权模式下的身份核验并返回通过认证的主体身份信息。
     *
     * @param command 认证凭据与请求上下文指令，不可为 {@code null}
     * @return 认证通过的主体身份信息
     */
    AuthenticatedPrincipal authenticate(AuthGrantCommand command);
}

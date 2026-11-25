package io.github.atengk.solid.system.api.service;

import io.github.atengk.solid.system.api.command.LoginAuditCommand;

/**
 * 审计日志跨模块远程落库服务契约。
 *
 * @author Ateng
 * @since 2026-11-25
 */
public interface RemoteAuditLogService {

    /**
     * 幂等写入登录/登出审计日志，并在登录成功且存在用户主键时同步刷新用户最近登录 IP 与时间。
     *
     * @param cmd 登录审计持久化指令，不可为 {@code null}
     */
    void recordLoginAudit(LoginAuditCommand cmd);
}

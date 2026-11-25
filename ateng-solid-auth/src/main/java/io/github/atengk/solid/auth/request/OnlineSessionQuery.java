package io.github.atengk.solid.auth.request;

import io.github.atengk.boot.core.model.PageQuery;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 在线会话分页筛选查询参数。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OnlineSessionQuery extends PageQuery {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 登录账号模糊过滤条件
     */
    @Size(max = 64, message = "登录账号筛选条件长度不能超过 64 个字符")
    private String username;

    /**
     * 客户端来源 IP 或网段过滤条件
     */
    @Size(max = 64, message = "客户端 IP 筛选条件长度不能超过 64 个字符")
    private String clientIp;
}

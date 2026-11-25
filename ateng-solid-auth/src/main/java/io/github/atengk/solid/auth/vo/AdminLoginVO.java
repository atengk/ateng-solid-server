package io.github.atengk.solid.auth.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;

/**
 * 管理端登录成功会话凭据与生效租户摘要视图对象。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "tokenValue")
public class AdminLoginVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 认证请求头名称（固定为 {@code satoken-admin}）
     */
    private String tokenName;

    /**
     * 签发的管理端访问令牌
     */
    private String tokenValue;

    /**
     * 令牌剩余有效期（秒，{@code -1} 表示永不过期）
     */
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private long expireIn;

    /**
     * 当前生效租户主键（JSON 序列化为字符串）
     */
    private Long tenantId;

    /**
     * 当前生效租户名称
     */
    private String tenantName;
}

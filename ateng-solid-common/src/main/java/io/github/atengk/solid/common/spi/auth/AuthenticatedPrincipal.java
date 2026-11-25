package io.github.atengk.solid.common.spi.auth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 认证策略核验通过后的标准化用户主体，交由认证服务统一签发会话令牌与发布审计事件。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthenticatedPrincipal implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户主键
     */
    private Long userId;

    /**
     * 所属租户主键
     */
    private Long tenantId;

    /**
     * 所属租户业务编码
     */
    private String tenantCode;

    /**
     * 所属租户名称
     */
    private String tenantName;

    /**
     * 归属部门主键（可空）
     */
    private Long deptId;

    /**
     * 登录账号
     */
    private String username;

    /**
     * 用户昵称
     */
    private String nickname;

    /**
     * 多账号登录体系标识（如 admin）
     */
    private String loginType;

    /**
     * 用户已授权角色编码集合（无角色时为空集合）
     */
    @Builder.Default
    private List<String> roles = Collections.emptyList();

    /**
     * 用户直接授权权限标识集合（无权限时为空集合）
     */
    @Builder.Default
    private List<String> permissions = Collections.emptyList();
}

package io.github.atengk.solid.auth.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 当前登录管理端用户轻量身份摘要视图对象（对应 {@code GET /admin/auth/info}）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminProfileVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户主键（JSON 序列化为字符串）
     */
    private Long userId;

    /**
     * 当前生效租户主键（JSON 序列化为字符串）
     */
    private Long tenantId;

    /**
     * 当前生效租户名称
     */
    private String tenantName;

    /**
     * 登录账号
     */
    private String username;

    /**
     * 用户昵称
     */
    private String nickname;

    /**
     * 动态签发的头像可访问地址
     */
    private String avatar;

    /**
     * 拥有的角色编码集合
     */
    @Builder.Default
    private List<String> roles = Collections.emptyList();

    /**
     * 拥有的细粒度按钮权限标识集合
     */
    @Builder.Default
    private List<String> permissions = Collections.emptyList();

    /**
     * 是否仍需提醒自助修改初始或重置密码
     */
    private boolean passwordNeedsChange;

    /**
     * 最近一次登录客户端 IP
     */
    private String loginIp;

    /**
     * 最近一次登录时间
     */
    private LocalDateTime loginDate;
}

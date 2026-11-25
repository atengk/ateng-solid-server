package io.github.atengk.solid.system.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管理端工作台与导航栏高频身份摘要数据传输对象（对应 {@code /admin/auth/info} 基础字段来源）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminProfileDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户主键
     */
    private Long userId;

    /**
     * 当前生效租户主键
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
     * 已动态签发（inline 预览）的可访问头像地址，未设置头像时可为空
     */
    private String avatarUrl;

    /**
     * 最近一次自助改密时间（为空时由认证域派生为需要改密提示）
     */
    private LocalDateTime pwdUpdateTime;

    /**
     * 最近登录客户端 IP
     */
    private String loginIp;

    /**
     * 最近登录时间
     */
    private LocalDateTime loginDate;
}

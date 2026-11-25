package io.github.atengk.solid.system.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 认证期专用的用户核心凭据与状态数据传输对象。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "passwordHash")
public class AdminAuthUserDTO implements Serializable {

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
     * 所属部门主键（允许为空）
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
     * PBKDF2 慢哈希口令密文（仅用于认证校验，严禁日志输出）
     */
    private String passwordHash;

    /**
     * 账号状态（0=正常，1=停用）
     */
    private Integer status;

    /**
     * 最近一次自助修改密码时间（为空表示仍处于初始或重置口令状态）
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

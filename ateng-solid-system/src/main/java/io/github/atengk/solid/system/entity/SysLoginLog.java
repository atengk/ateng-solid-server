package io.github.atengk.solid.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseIdEntity;
import io.github.atengk.boot.mybatisplus.entity.TenantAware;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 登录审计日志只追加持久层实体（支持行级多租户隔离，无软删与乐观锁）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
@TableName("sys_login_log")
public class SysLoginLog extends BaseIdEntity implements TenantAware<Long> {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属租户主键
     */
    private Long tenantId;

    /**
     * 全链路追踪标识
     */
    private String traceId;

    /**
     * 登录尝试账号
     */
    private String username;

    /**
     * 登录账号体系标识（默认 admin）
     */
    private String loginType;

    /**
     * 客户端来源 IP
     */
    private String clientIp;

    /**
     * 登录归属地（首期可空）
     */
    private String loginLocation;

    /**
     * 解析后的浏览器标识
     */
    private String browser;

    /**
     * 解析后的操作系统标识
     */
    private String os;

    /**
     * 登录执行状态（0=成功，1=失败）
     */
    private Integer status;

    /**
     * 登录结果提示消息
     */
    private String msg;

    /**
     * 登录发生时间
     */
    private LocalDateTime loginTime;
}

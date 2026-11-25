package io.github.atengk.solid.system.vo;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * 登录审计日志只读分页响应视图对象。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Value
@Builder
public class LoginLogVO {

    /**
     * 登录日志主键
     */
    Long id;

    /**
     * 全链路追踪标识
     */
    String traceId;

    /**
     * 登录尝试账号
     */
    String username;

    /**
     * 登录账号体系标识
     */
    String loginType;

    /**
     * 客户端来源 IP
     */
    String clientIp;

    /**
     * 登录归属地（首期可空）
     */
    String loginLocation;

    /**
     * 解析后的浏览器标识
     */
    String browser;

    /**
     * 解析后的操作系统标识
     */
    String os;

    /**
     * 登录执行状态（0=成功，1=失败）
     */
    Integer status;

    /**
     * 登录结果提示消息
     */
    String msg;

    /**
     * 登录发生时间
     */
    LocalDateTime loginTime;
}

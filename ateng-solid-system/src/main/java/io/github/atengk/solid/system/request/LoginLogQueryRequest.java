package io.github.atengk.solid.system.request;

import io.github.atengk.boot.core.model.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 登录审计日志只读分页检索查询参数。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class LoginLogQueryRequest extends PageQuery {

    /**
     * 登录尝试账号（模糊匹配）
     */
    private String username;

    /**
     * 客户端来源 IP（模糊匹配）
     */
    private String clientIp;

    /**
     * 登录执行状态（0=成功，1=失败）
     */
    private Integer status;

    /**
     * 全链路追踪标识（精确匹配）
     */
    private String traceId;

    /**
     * 登录时间起始边界（含）
     */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime beginTime;

    /**
     * 登录时间截止边界（含）
     */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
}

package io.github.atengk.solid.system.request;

import io.github.atengk.boot.core.model.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 操作审计日志只读分页检索查询参数。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OperLogQueryRequest extends PageQuery {

    /**
     * 操作模块名（模糊匹配）
     */
    private String module;

    /**
     * 操作人账号或昵称（模糊匹配）
     */
    private String userName;

    /**
     * 操作类型编码（对齐 {@code OperateTypeEnum.code}）
     */
    private Integer operateType;

    /**
     * 执行状态（0=成功，1=失败）
     */
    private Integer status;

    /**
     * 全链路追踪标识（精确匹配）
     */
    private String traceId;

    /**
     * 操作时间起始边界（含）
     */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime beginTime;

    /**
     * 操作时间截止边界（含）
     */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
}

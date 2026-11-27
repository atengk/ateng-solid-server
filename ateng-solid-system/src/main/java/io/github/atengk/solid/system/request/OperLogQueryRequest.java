package io.github.atengk.solid.system.request;

import io.github.atengk.boot.core.model.PageQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
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
    @Size(max = 64, message = "操作模块关键字长度不能超过 64 个字符")
    private String module;

    /**
     * 操作人账号或昵称（模糊匹配）
     */
    @Size(max = 64, message = "操作人关键字长度不能超过 64 个字符")
    private String userName;

    /**
     * 操作类型编码（对齐 {@code OperateTypeEnum.code}）
     */
    @Min(value = 0, message = "操作类型编码不合法")
    @Max(value = 99, message = "操作类型编码不合法")
    private Integer operateType;

    /**
     * 执行状态（0=成功，1=失败）
     */
    @Min(value = 0, message = "执行状态仅支持 0（成功）或 1（失败）")
    @Max(value = 1, message = "执行状态仅支持 0（成功）或 1（失败）")
    private Integer status;

    /**
     * 全链路追踪标识（精确匹配）
     */
    @Size(max = 64, message = "追踪标识长度不能超过 64 个字符")
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

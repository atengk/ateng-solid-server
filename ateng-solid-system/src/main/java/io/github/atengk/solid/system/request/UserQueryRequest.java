package io.github.atengk.solid.system.request;

import io.github.atengk.boot.core.model.PageQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 用户列表分页与导出通用筛选请求参数。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserQueryRequest extends PageQuery {

    /**
     * 用户账号或昵称模糊关键字
     */
    @Size(max = 64, message = "关键字长度不能超过 64 个字符")
    private String username;

    /**
     * 完整手机号码（精确匹配，后端自动执行确定性加密检索）
     */
    @Size(max = 32, message = "手机号码长度不能超过 32 个字符")
    private String phone;

    /**
     * 账号启停状态（0=正常，1=停用）
     */
    @Min(value = 0, message = "账号状态仅支持 0（正常）或 1（停用）")
    @Max(value = 1, message = "账号状态仅支持 0（正常）或 1（停用）")
    private Integer status;

    /**
     * 归属部门主键（联动当前部门及全量下级子孙部门过滤）
     */
    @Positive(message = "归属部门主键必须为正整数")
    private Long deptId;

    /**
     * 创建时间起始边界（含）
     */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime beginTime;

    /**
     * 创建时间截止边界（含）
     */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
}

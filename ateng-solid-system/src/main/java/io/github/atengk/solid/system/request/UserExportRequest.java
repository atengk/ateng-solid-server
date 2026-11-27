package io.github.atengk.solid.system.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户 Excel 流式导出请求体（支持按勾选主键集合导出或按筛选条件全量受权导出）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
public class UserExportRequest {

    /**
     * 已勾选待导出的用户主键列表（非空时优先按主键过滤，仍叠加租户与数据权限边界）
     */
    @Size(max = 1000, message = "单次勾选导出主键数量不能超过 1000 个")
    private List<@NotNull(message = "导出用户主键不能为空") @Positive(message = "导出用户主键必须为正整数") Long> ids;

    /**
     * 用户账号或昵称模糊关键字
     */
    @Size(max = 64, message = "关键字长度不能超过 64 个字符")
    private String username;

    /**
     * 完整手机号码（精确匹配）
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
     * 归属部门主键（含下级部门子树）
     */
    @Positive(message = "归属部门主键必须为正整数")
    private Long deptId;

    /**
     * 创建时间起始边界（含）
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime beginTime;

    /**
     * 创建时间截止边界（含）
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
}

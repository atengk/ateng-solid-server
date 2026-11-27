package io.github.atengk.solid.system.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 部门树检索查询条件。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
public class DeptQueryRequest {

    /**
     * 部门名称（模糊匹配）
     */
    @Size(max = 100, message = "部门名称关键字长度不能超过 100 个字符")
    private String deptName;

    /**
     * 部门状态（0=正常，1=停用）
     */
    @Min(value = 0, message = "部门状态仅支持 0（正常）或 1（停用）")
    @Max(value = 1, message = "部门状态仅支持 0（正常）或 1（停用）")
    private Integer status;
}

package io.github.atengk.solid.system.request;

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
    private String deptName;

    /**
     * 部门状态（0=正常，1=停用）
     */
    private Integer status;
}

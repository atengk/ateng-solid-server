package io.github.atengk.solid.system.request;

import io.github.atengk.boot.core.model.PageQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 岗位分页检索查询条件。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PostQueryRequest extends PageQuery {

    /**
     * 岗位编码（模糊匹配）
     */
    @Size(max = 64, message = "岗位编码关键字长度不能超过 64 个字符")
    private String postCode;

    /**
     * 岗位名称（模糊匹配）
     */
    @Size(max = 100, message = "岗位名称关键字长度不能超过 100 个字符")
    private String postName;

    /**
     * 岗位状态（0=正常，1=停用）
     */
    @Min(value = 0, message = "岗位状态仅支持 0（正常）或 1（停用）")
    @Max(value = 1, message = "岗位状态仅支持 0（正常）或 1（停用）")
    private Integer status;
}

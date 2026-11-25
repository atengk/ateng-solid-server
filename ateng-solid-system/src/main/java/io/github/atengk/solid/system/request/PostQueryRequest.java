package io.github.atengk.solid.system.request;

import io.github.atengk.boot.core.model.PageQuery;
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
    private String postCode;

    /**
     * 岗位名称（模糊匹配）
     */
    private String postName;

    /**
     * 岗位状态（0=正常，1=停用）
     */
    private Integer status;
}

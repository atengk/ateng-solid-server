package io.github.atengk.solid.system.request;

import io.github.atengk.boot.core.model.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 动态字典类型分页检索查询条件。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DictTypeQueryRequest extends PageQuery {

    /**
     * 字典名称（模糊匹配）
     */
    private String dictName;

    /**
     * 字典类型编码（模糊匹配）
     */
    private String dictType;

    /**
     * 启停状态（0=正常，1=停用）
     */
    private Integer status;
}

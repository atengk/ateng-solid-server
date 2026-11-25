package io.github.atengk.solid.system.request;

import io.github.atengk.boot.core.model.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 动态字典数据明细分页检索查询条件。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DictDataQueryRequest extends PageQuery {

    /**
     * 所属字典类型编码（精确匹配）
     */
    private String dictType;

    /**
     * 字典展示标签（模糊匹配）
     */
    private String dictLabel;

    /**
     * 启停状态（0=正常，1=停用）
     */
    private Integer status;
}

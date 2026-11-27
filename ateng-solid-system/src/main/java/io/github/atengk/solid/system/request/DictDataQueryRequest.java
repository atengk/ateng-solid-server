package io.github.atengk.solid.system.request;

import io.github.atengk.boot.core.model.PageQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
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
    @Size(max = 100, message = "字典类型编码长度不能超过 100 个字符")
    private String dictType;

    /**
     * 字典展示标签（模糊匹配）
     */
    @Size(max = 100, message = "字典标签关键字长度不能超过 100 个字符")
    private String dictLabel;

    /**
     * 启停状态（0=正常，1=停用）
     */
    @Min(value = 0, message = "字典状态仅支持 0（正常）或 1（停用）")
    @Max(value = 1, message = "字典状态仅支持 0（正常）或 1（停用）")
    private Integer status;
}

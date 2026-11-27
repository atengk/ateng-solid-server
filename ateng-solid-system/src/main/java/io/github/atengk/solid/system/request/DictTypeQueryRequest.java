package io.github.atengk.solid.system.request;

import io.github.atengk.boot.core.model.PageQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
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
    @Size(max = 100, message = "字典名称关键字长度不能超过 100 个字符")
    private String dictName;

    /**
     * 字典类型编码（模糊匹配）
     */
    @Size(max = 100, message = "字典类型关键字长度不能超过 100 个字符")
    private String dictType;

    /**
     * 启停状态（0=正常，1=停用）
     */
    @Min(value = 0, message = "字典状态仅支持 0（正常）或 1（停用）")
    @Max(value = 1, message = "字典状态仅支持 0（正常）或 1（停用）")
    private Integer status;
}

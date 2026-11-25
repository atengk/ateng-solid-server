package io.github.atengk.solid.system.vo;

import lombok.Builder;
import lombok.Value;

/**
 * Excel 批量导入单条校验错误明细视图对象（严格禁止透出 {@code rowData} 实体数据）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Value
@Builder
public class ImportErrorVO {

    /**
     * 物理行号（1-based，表头为 1，首行数据为 2）
     */
    Integer rowNum;

    /**
     * 出错列名或定位范围（如 {@code 用户账号}、{@code 整行}）
     */
    String columnName;

    /**
     * 可读的中文错误原因说明
     */
    String errorMessage;

    /**
     * 出错单元格原始文本值（可为空）
     */
    String rawValue;
}

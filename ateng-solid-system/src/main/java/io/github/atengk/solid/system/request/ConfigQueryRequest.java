package io.github.atengk.solid.system.request;

import io.github.atengk.boot.core.model.PageQuery;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 系统参数分页检索查询条件。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ConfigQueryRequest extends PageQuery {

    /**
     * 参数名称（模糊匹配）
     */
    @Size(max = 100, message = "参数名称关键字长度不能超过 100 个字符")
    private String configName;

    /**
     * 参数键名（模糊匹配）
     */
    @Size(max = 100, message = "参数键名关键字长度不能超过 100 个字符")
    private String configKey;

    /**
     * 参数类型（Y=系统内置，N=业务自建）
     */
    @Size(max = 16, message = "参数类型长度不能超过 16 个字符")
    private String configType;
}

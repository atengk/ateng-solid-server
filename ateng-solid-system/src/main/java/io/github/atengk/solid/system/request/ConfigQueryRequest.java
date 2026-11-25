package io.github.atengk.solid.system.request;

import io.github.atengk.boot.core.model.PageQuery;
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
    private String configName;

    /**
     * 参数键名（模糊匹配）
     */
    private String configKey;

    /**
     * 参数类型（Y=系统内置，N=业务自建）
     */
    private String configType;
}

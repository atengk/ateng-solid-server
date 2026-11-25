package io.github.atengk.solid.system.vo;

import lombok.Builder;
import lombok.Value;

/**
 * 动态字典类型下拉选项视图对象。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Value
@Builder
public class DictTypeOptionVO {

    /**
     * 字典类型主键
     */
    Long id;

    /**
     * 字典名称
     */
    String dictName;

    /**
     * 字典类型编码
     */
    String dictType;

    /**
     * 启停状态（0=正常，1=停用）
     */
    Integer status;
}

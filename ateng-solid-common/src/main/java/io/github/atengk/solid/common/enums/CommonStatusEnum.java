package io.github.atengk.solid.common.enums;

import io.github.atengk.boot.core.enums.IBaseEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 通用启停状态枚举，与数据库 TINYINT 状态列（0=正常，1=停用）严格对齐。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Getter
@AllArgsConstructor
public enum CommonStatusEnum implements IBaseEnum<Integer> {

    /**
     * 正常启用状态
     */
    NORMAL(0, "正常"),

    /**
     * 已停用状态
     */
    DISABLED(1, "停用");

    /**
     * 数据库存储与接口传输编码
     */
    private final Integer code;

    /**
     * 前端展示中文标签
     */
    private final String label;
}

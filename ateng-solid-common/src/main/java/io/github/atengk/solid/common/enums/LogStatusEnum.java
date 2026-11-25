package io.github.atengk.solid.common.enums;

import io.github.atengk.boot.core.enums.IBaseEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 审计日志执行结果状态枚举，与操作日志及登录日志状态列（0=成功，1=失败）严格对齐。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Getter
@AllArgsConstructor
public enum LogStatusEnum implements IBaseEnum<Integer> {

    /**
     * 执行或认证成功
     */
    SUCCESS(0, "成功"),

    /**
     * 执行或认证失败
     */
    FAIL(1, "失败");

    /**
     * 状态编码
     */
    private final Integer code;

    /**
     * 中文标签
     */
    private final String label;
}

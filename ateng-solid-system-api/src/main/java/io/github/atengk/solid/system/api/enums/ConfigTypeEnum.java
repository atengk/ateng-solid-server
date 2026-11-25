package io.github.atengk.solid.system.api.enums;

import io.github.atengk.boot.core.enums.IBaseEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 系统参数内置标识枚举，与数据库 {@code sys_config.config_type}（Y/N）严格对齐。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Getter
@AllArgsConstructor
public enum ConfigTypeEnum implements IBaseEnum<String> {

    /**
     * 系统内置参数（受 102007 保护，禁止修改键名或删除）
     */
    SYSTEM("Y", "系统内置"),

    /**
     * 业务自建参数
     */
    BUSINESS("N", "业务自建");

    /**
     * 参数类型编码
     */
    private final String code;

    /**
     * 中文展示名称
     */
    private final String label;
}

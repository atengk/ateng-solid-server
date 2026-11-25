package io.github.atengk.solid.system.api.enums;

import io.github.atengk.boot.core.enums.IBaseEnum;
import io.github.atengk.boot.core.util.EnumUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 菜单侧栏显隐状态枚举，与数据库 {@code sys_menu.visible}（0=显示，1=隐藏）严格对齐。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Getter
@AllArgsConstructor
public enum VisibleStatusEnum implements IBaseEnum<Integer> {

    /**
     * 在左侧导航栏显示
     */
    VISIBLE(0, "显示"),

    /**
     * 在左侧导航栏隐藏（仍保留路由注册与跳转能力）
     */
    HIDDEN(1, "隐藏");

    /**
     * 数据库存储编码
     */
    private final Integer code;

    /**
     * 中文展示名称
     */
    private final String label;

    /**
     * 按数值编码解析菜单侧栏显隐状态。
     *
     * @param code 显隐状态编码（0=显示，1=隐藏）
     * @return 匹配的枚举实例；未匹配或传入 {@code null} 时返回 {@code null}
     */
    public static VisibleStatusEnum fromCode(Integer code) {
        return EnumUtil.fromCode(VisibleStatusEnum.class, code).orElse(null);
    }
}

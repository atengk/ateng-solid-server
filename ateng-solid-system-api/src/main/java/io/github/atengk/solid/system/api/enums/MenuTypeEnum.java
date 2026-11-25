package io.github.atengk.solid.system.api.enums;

import io.github.atengk.boot.core.enums.IBaseEnum;
import io.github.atengk.boot.core.util.EnumUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 菜单权限节点类型枚举，与数据库 {@code sys_menu.menu_type}（M/C/F）严格对齐。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Getter
@AllArgsConstructor
public enum MenuTypeEnum implements IBaseEnum<String> {

    /**
     * 导航目录节点
     */
    DIRECTORY("M", "目录"),

    /**
     * 页面菜单节点
     */
    MENU("C", "菜单"),

    /**
     * 页面内按钮/操作权限点
     */
    BUTTON("F", "按钮");

    /**
     * 节点类型编码
     */
    private final String code;

    /**
     * 中文展示名称
     */
    private final String label;

    /**
     * 按字符编码解析菜单节点类型。
     *
     * @param code 节点编码（M/C/F）
     * @return 匹配的枚举实例；未匹配或传入 {@code null} 时返回 {@code null}
     */
    public static MenuTypeEnum fromCode(String code) {
        return EnumUtil.fromCode(MenuTypeEnum.class, code).orElse(null);
    }
}

package io.github.atengk.solid.system.api.enums;

import io.github.atengk.boot.core.enums.IBaseEnum;
import io.github.atengk.boot.core.util.EnumUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 角色 5 级数据权限范围枚举，与数据库 {@code sys_role.data_scope}（1 ~ 5）严格对齐。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Getter
@AllArgsConstructor
public enum DataScopeType implements IBaseEnum<Integer> {

    /**
     * 全部数据权限（不追加部门或本人过滤条件）
     */
    ALL(1, "全部数据权限"),

    /**
     * 自定部门数据权限（基于角色关联部门集合过滤）
     */
    CUSTOM(2, "自定部门数据权限"),

    /**
     * 本部门数据权限
     */
    DEPT(3, "本部门数据权限"),

    /**
     * 本部门及以下子孙部门数据权限
     */
    DEPT_AND_CHILD(4, "本部门及以下数据权限"),

    /**
     * 仅本人数据权限
     */
    SELF(5, "仅本人数据权限");

    /**
     * 数据库存储编码
     */
    private final Integer code;

    /**
     * 中文展示名称
     */
    private final String label;

    /**
     * 按数值编码解析数据权限类型。
     *
     * @param code 数据范围编码（1 ~ 5）
     * @return 匹配的枚举实例；未匹配或传入 {@code null} 时返回 {@code null}
     */
    public static DataScopeType fromCode(Integer code) {
        return EnumUtil.fromCode(DataScopeType.class, code).orElse(null);
    }
}

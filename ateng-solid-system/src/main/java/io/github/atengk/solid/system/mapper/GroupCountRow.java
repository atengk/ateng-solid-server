package io.github.atengk.solid.system.mapper;

import lombok.Data;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分组统计映射投影对象（显式绑定列别名以兼顾 MySQL 8.4 与 H2 小写元数据）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
public class GroupCountRow {

    /**
     * 分组外键主键（如 {@code package_id} 或 {@code tenant_id}）
     */
    private Long groupId;

    /**
     * 分组统计行数
     */
    private Long rowCount;

    /**
     * 将分组统计结果转换为 {@code groupId -> count} 只读/可变映射表（无匹配返回空 Map）。
     *
     * @param rows 分组统计查询结果
     * @return 按分组主键映射的计数字典
     */
    public static Map<Long, Long> toCountMap(List<GroupCountRow> rows) {
        if (CollectionUtils.isEmpty(rows)) {
            return Collections.emptyMap();
        }
        Map<Long, Long> map = new HashMap<>(rows.size());
        for (GroupCountRow row : rows) {
            if (row != null && row.getGroupId() != null && row.getRowCount() != null) {
                map.put(row.getGroupId(), row.getRowCount());
            }
        }
        return map;
    }
}

package io.github.atengk.solid.system.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 动态字典数据明细响应视图对象（同时作为缓存 JSON 载体，保留无参构造支持无框架反序列化）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DictDataVO {

    /**
     * 字典数据主键
     */
    private Long id;

    /**
     * 所属字典类型编码
     */
    private String dictType;

    /**
     * 字典展示标签
     */
    private String dictLabel;

    /**
     * 字典真实键值
     */
    private String dictValue;

    /**
     * 回显标签语义样式（空串 / primary / success / warning / danger / info）
     */
    private String tagType;

    /**
     * 自定义扩展 CSS 类名
     */
    private String cssClass;

    /**
     * 显示排序（升序）
     */
    private Integer sort;

    /**
     * 是否默认项（0=否，1=是）
     */
    private Integer isDefault;

    /**
     * 启停状态（0=正常，1=停用）
     */
    private Integer status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}

package io.github.atengk.solid.system.vo;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * 动态字典类型分页与详情响应视图对象。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Value
@Builder
public class DictTypeVO {

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

    /**
     * 备注
     */
    String remark;

    /**
     * 创建时间
     */
    LocalDateTime createTime;
}

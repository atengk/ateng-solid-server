package io.github.atengk.solid.system.vo;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * 岗位列表与详情响应视图对象。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Value
@Builder
public class PostVO {

    /**
     * 岗位主键
     */
    Long id;

    /**
     * 岗位编码
     */
    String postCode;

    /**
     * 岗位名称
     */
    String postName;

    /**
     * 显示排序
     */
    Integer sort;

    /**
     * 岗位状态（0=正常，1=停用）
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

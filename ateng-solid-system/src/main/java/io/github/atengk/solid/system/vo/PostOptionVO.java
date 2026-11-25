package io.github.atengk.solid.system.vo;

import lombok.Builder;
import lombok.Value;

/**
 * 岗位下拉选项轻量化视图对象。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Value
@Builder
public class PostOptionVO {

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
     * 岗位状态（0=正常，1=停用）
     */
    Integer status;
}

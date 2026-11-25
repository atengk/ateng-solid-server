package io.github.atengk.solid.system.request;

import lombok.Data;

/**
 * 菜单树检索查询条件。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
public class MenuQueryRequest {

    /**
     * 菜单或权限名称（模糊匹配）
     */
    private String title;

    /**
     * 菜单状态（0=正常，1=停用）
     */
    private Integer status;

    /**
     * 是否平台专属筛选（null=全部，true=仅平台专属，false=通用可分发）
     */
    private Boolean platformOnly;
}

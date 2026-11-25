package io.github.atengk.solid.system.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 平台超管顶栏租户切换器下拉选项视图对象（与前端 {@code TenantOptionVO} 严格 1:1 对齐）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantOptionVO {

    /**
     * 租户主键
     */
    private Long id;

    /**
     * 租户唯一业务编码
     */
    private String tenantCode;

    /**
     * 租户名称
     */
    private String tenantName;

    /**
     * 租户状态（0=正常，1=停用）
     */
    private Integer status;

    /**
     * 租户授权过期时间（为空表示永久有效）
     */
    private LocalDateTime expireTime;
}

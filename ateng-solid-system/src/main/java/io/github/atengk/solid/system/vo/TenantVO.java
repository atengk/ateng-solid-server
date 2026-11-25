package io.github.atengk.solid.system.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 租户管理分页及详情视图对象（含关联套餐名称、已用账号数与实时过期状态标志）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantVO {

    /**
     * 租户主键（平台默认租户为 0）
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
     * 关联套餐主键（平台默认租户为 null）
     */
    private Long packageId;

    /**
     * 关联套餐名称
     */
    private String packageName;

    /**
     * 所绑定套餐当前是否处于停用状态
     */
    private Boolean packageDisabled;

    /**
     * 账号配额上限（-1 表示不限制）
     */
    private Integer accountCount;

    /**
     * 当前租户已用有效账号数量
     */
    private Long usedAccountCount;

    /**
     * 租户授权过期时间（为空表示永久有效）
     */
    private LocalDateTime expireTime;

    /**
     * 当前是否已过期
     */
    private Boolean expired;

    /**
     * 租户启停状态（0=正常，1=停用）
     */
    private Integer status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 是否平台内置默认租户
     */
    private Boolean platformDefault;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}

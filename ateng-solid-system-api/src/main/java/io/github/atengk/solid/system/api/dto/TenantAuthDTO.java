package io.github.atengk.solid.system.api.dto;

import io.github.atengk.solid.common.enums.CommonStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 租户鉴权与可用性校验数据传输对象（同时作为零漂移状态缓存载荷）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantAuthDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 租户主键（不存在的防穿透哨兵对象中该字段为 null）
     */
    private Long tenantId;

    /**
     * 租户业务编码
     */
    private String tenantCode;

    /**
     * 租户名称
     */
    private String tenantName;

    /**
     * 关联租户套餐主键（平台默认租户为 null）
     */
    private Long packageId;

    /**
     * 租户授权到期时间（为空代表永久有效）
     */
    private LocalDateTime expireTime;

    /**
     * 租户启停状态（0=正常，1=停用）
     */
    private Integer status;

    /**
     * 根据给定当前时刻实时判断租户是否处于可用状态（实现缓存读时 0 秒过期截断）。
     *
     * @param now 当前比对时刻
     * @return 租户存在、状态为正常且尚未超过过期时间时返回 {@code true}
     */
    public boolean isAvailableAt(LocalDateTime now) {
        if (tenantId == null || !CommonStatusEnum.NORMAL.getCode().equals(status)) {
            return false;
        }
        if (expireTime == null) {
            return true;
        }
        return now != null && expireTime.isAfter(now);
    }
}

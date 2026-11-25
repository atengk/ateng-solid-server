package io.github.atengk.solid.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseLogicEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 租户信息持久层实体（平台共享表，免除行级多租户过滤）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
@TableName("sys_tenant")
public class SysTenant extends BaseLogicEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 租户业务编码（平台默认租户固定为 000000，编辑锁定只读）
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
     * 授权过期时间（null 表示永久有效）
     */
    private LocalDateTime expireTime;

    /**
     * 账号配额上限（-1 表示不限；>=1 限制有效用户总数）
     */
    private Integer accountCount;

    /**
     * 租户启停状态（0=正常，1=停用）
     */
    private Integer status;

    /**
     * 备注
     */
    private String remark;
}

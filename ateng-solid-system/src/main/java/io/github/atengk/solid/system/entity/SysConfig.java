package io.github.atengk.solid.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseLogicEntity;
import io.github.atengk.boot.mybatisplus.entity.TenantAware;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serial;

/**
 * 系统参数持久层实体（支持行级多租户隔离与平台默认租户参数回退）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
@TableName("sys_config")
public class SysConfig extends BaseLogicEntity implements TenantAware<Long> {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属租户主键
     */
    private Long tenantId;

    /**
     * 参数名称
     */
    private String configName;

    /**
     * 参数键名（同租户未删除唯一；内置项禁止更改键名与删除）
     */
    private String configKey;

    /**
     * 参数键值
     */
    private String configValue;

    /**
     * 参数类型（Y=系统内置，N=业务自建）
     */
    private String configType;

    /**
     * 备注
     */
    private String remark;
}

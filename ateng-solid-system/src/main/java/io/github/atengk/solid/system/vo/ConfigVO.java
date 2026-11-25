package io.github.atengk.solid.system.vo;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * 系统参数分页与详情响应视图对象。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Value
@Builder
public class ConfigVO {

    /**
     * 参数主键
     */
    Long id;

    /**
     * 参数名称
     */
    String configName;

    /**
     * 参数键名
     */
    String configKey;

    /**
     * 参数键值
     */
    String configValue;

    /**
     * 参数类型（Y=系统内置，N=业务自建）
     */
    String configType;

    /**
     * 备注
     */
    String remark;

    /**
     * 创建时间
     */
    LocalDateTime createTime;
}

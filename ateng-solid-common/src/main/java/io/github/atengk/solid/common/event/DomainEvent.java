package io.github.atengk.solid.common.event;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 跨模块领域事件统一标记契约，携带事件唯一标识、触发时间、租户归属与全链路追踪标识。
 *
 * @author Ateng
 * @since 2026-11-25
 */
public interface DomainEvent extends Serializable {

    /**
     * 获取领域事件全局唯一标识（用于下游消费端幂等去重）。
     *
     * @return 事件唯一标识，非空
     */
    String getEventId();

    /**
     * 获取领域事件发生时间。
     *
     * @return 事件发生时间戳
     */
    LocalDateTime getOccurredAt();

    /**
     * 获取事件归属的租户主键。
     *
     * @return 租户主键（无法定位具体租户时通常为平台默认租户 {@code 0L}）
     */
    Long getTenantId();

    /**
     * 获取发布事件时关联的全链路追踪标识。
     *
     * @return Trace ID，可能为 {@code null}
     */
    String getTraceId();
}

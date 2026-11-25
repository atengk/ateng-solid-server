package io.github.atengk.solid.common.event;

/**
 * 跨模块领域事件发布端口，屏蔽单体进程内广播与分布式消息队列实现差异。
 *
 * @author Ateng
 * @since 2026-11-25
 */
public interface DomainEventPublisher {

    /**
     * 立即发布领域事件（适用于无数据库事务包裹的主流程，如认证日志发布）。
     *
     * @param event 待发布的领域事件，不可为 {@code null}
     */
    void publish(DomainEvent event);

    /**
     * 注册在当前物理事务成功提交后发布领域事件；若当前无活跃事务同步器，则就地立即发布。
     *
     * @param event 待发布的领域事件，不可为 {@code null}
     */
    void publishAfterCommit(DomainEvent event);
}

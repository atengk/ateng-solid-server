package io.github.atengk.solid.common.event;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.util.TransactionUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 基于 Spring {@link ApplicationEventPublisher} 的默认进程内领域事件发布实现；
 * 提交后发布路径自动绑定 {@link TransactionUtil#executeAfterCommit(Runnable)} 与 {@link AsyncContext#wrap(Runnable)} 上下文快照透传。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Component
@RequiredArgsConstructor
public class SpringDomainEventPublisher implements DomainEventPublisher {

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void publish(DomainEvent event) {
        Objects.requireNonNull(event, "event 不能为空");
        eventPublisher.publishEvent(event);
    }

    @Override
    public void publishAfterCommit(DomainEvent event) {
        Objects.requireNonNull(event, "event 不能为空");
        Runnable wrappedTask = AsyncContext.wrap(() -> eventPublisher.publishEvent(event));
        TransactionUtil.executeAfterCommit(wrappedTask);
    }
}

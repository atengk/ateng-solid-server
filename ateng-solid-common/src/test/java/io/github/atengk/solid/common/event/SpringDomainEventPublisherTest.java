package io.github.atengk.solid.common.event;

import io.github.atengk.boot.core.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SpringDomainEventPublisher} Layer 1 纯单元测试（验证同步发布、非事务降级、事务提交后延迟发布与回滚拦截及上下文透传）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
class SpringDomainEventPublisherTest {

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("空事件入参应当快速失败抛出 NullPointerException")
    void shouldRejectNullEvent() {
        SpringDomainEventPublisher publisher = new SpringDomainEventPublisher(event -> {
        });

        assertThatThrownBy(() -> publisher.publish(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> publisher.publishAfterCommit(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("立即发布路径应当同步广播事件并保留 AsyncContext 上下文")
    void shouldPublishImmediatelyWithContext() {
        List<Object> published = new ArrayList<>();
        List<String> observedTenants = new ArrayList<>();
        List<String> observedTraces = new ArrayList<>();
        ApplicationEventPublisher delegate = event -> {
            published.add(event);
            observedTenants.add(TenantContextHolder.getTenantId());
            observedTraces.add(MDC.get("traceId"));
        };
        SpringDomainEventPublisher publisher = new SpringDomainEventPublisher(delegate);

        TenantContextHolder.setTenantId("1001");
        MDC.put("traceId", "trace-sync-001");
        LoginAuditEvent event = LoginAuditEvent.builder()
                .tenantId(1001L)
                .traceId("trace-sync-001")
                .username("alice")
                .loginType("admin")
                .status(0)
                .message("登录成功")
                .build();

        publisher.publish(event);

        assertThat(published).containsExactly(event);
        assertThat(observedTenants).containsExactly("1001");
        assertThat(observedTraces).containsExactly("trace-sync-001");
    }

    @Test
    @DisplayName("无活跃事务时 publishAfterCommit 应当平滑降级为就地发布")
    void shouldPublishImmediatelyWhenNoActiveTransaction() {
        List<Object> published = new ArrayList<>();
        SpringDomainEventPublisher publisher = new SpringDomainEventPublisher(published::add);
        LoginAuditEvent event = LoginAuditEvent.builder()
                .tenantId(0L)
                .username("superadmin")
                .loginType("admin")
                .status(0)
                .build();

        publisher.publishAfterCommit(event);

        assertThat(published).containsExactly(event);
    }

    @Test
    @DisplayName("活跃事务提交后延迟发布应当在 commit 后触发并还原事务内上下文快照")
    void shouldPublishOnlyAfterTransactionCommitsAndRestoreContext() {
        List<Object> published = new ArrayList<>();
        List<String> observedTenants = new ArrayList<>();
        List<String> observedTraces = new ArrayList<>();
        ApplicationEventPublisher delegate = event -> {
            published.add(event);
            observedTenants.add(TenantContextHolder.getTenantId());
            observedTraces.add(MDC.get("traceId"));
        };
        SpringDomainEventPublisher publisher = new SpringDomainEventPublisher(delegate);
        TransactionTemplate transactionTemplate = new TransactionTemplate(new InMemoryTransactionManager());

        LoginAuditEvent event = LoginAuditEvent.builder()
                .tenantId(2002L)
                .traceId("trace-tx-002")
                .username("bob")
                .loginType("admin")
                .status(0)
                .build();

        transactionTemplate.executeWithoutResult(status -> {
            TenantContextHolder.setTenantId("2002");
            MDC.put("traceId", "trace-tx-002");
            publisher.publishAfterCommit(event);
            // 提交前不得提前投递
            assertThat(published).isEmpty();
            // 模拟发布调用后外部清除本地线程上下文
            TenantContextHolder.clear();
            MDC.clear();
        });

        assertThat(published).containsExactly(event);
        assertThat(observedTenants).containsExactly("2002");
        assertThat(observedTraces).containsExactly("trace-tx-002");
    }

    @Test
    @DisplayName("活跃事务回滚时 publishAfterCommit 不得触发事件投递")
    void shouldNotPublishWhenTransactionRollsBack() {
        List<Object> published = new ArrayList<>();
        SpringDomainEventPublisher publisher = new SpringDomainEventPublisher(published::add);
        TransactionTemplate transactionTemplate = new TransactionTemplate(new InMemoryTransactionManager());
        LoginAuditEvent event = LoginAuditEvent.builder()
                .tenantId(0L)
                .username("charlie")
                .loginType("admin")
                .status(1)
                .build();

        transactionTemplate.executeWithoutResult(status -> {
            publisher.publishAfterCommit(event);
            status.setRollbackOnly();
        });

        assertThat(published).isEmpty();
    }

    /**
     * 轻量内存事务管理器桩，仅用于触发 Spring 事务同步器提交/回滚生命周期回调。
     */
    private static final class InMemoryTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}

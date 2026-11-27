package io.github.atengk.solid.system.service.remote;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.redis.idempotent.RedisIdempotentTemplate;
import io.github.atengk.boot.redis.idempotent.enums.IdempotentStatus;
import io.github.atengk.boot.redis.properties.RedisProperties;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.LogStatusEnum;
import io.github.atengk.solid.system.api.command.LoginAuditCommand;
import io.github.atengk.solid.system.entity.SysLoginLog;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.mapper.SysLoginLogMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.support.AdaptiveRedisSupport;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.function.Consumer;

import static io.github.atengk.solid.system.support.CacheTestFixtures.adaptiveSupport;
import static io.github.atengk.solid.system.support.CacheTestFixtures.emptyEnvProvider;
import static io.github.atengk.solid.system.support.CacheTestFixtures.redisDisabledEnvProvider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RemoteAuditLogServiceImpl} 基于 {@link RedisIdempotentTemplate} 幂等去重与离线降级 Layer 1 单元测试。
 *
 * @author Ateng
 * @since 2026-11-27
 */
class RemoteAuditLogServiceImplTest {

    @BeforeAll
    static void initMyBatisPlusTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SysUser.class);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    private static AdaptiveRedisSupport adaptiveWithIdempotent(
            RedisIdempotentTemplate idempotentTemplate,
            ObjectProvider<Environment> envProvider
    ) {
        return adaptiveSupport(null, null, null, idempotentTemplate, envProvider);
    }

    private static TransactionTemplate immediateTxTemplate() {
        TransactionTemplate txTemplate = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(txTemplate).executeWithoutResult(any());
        return txTemplate;
    }

    private static RedisIdempotentTemplate realTemplateBackedByBucket(
            RedissonClient redissonClient,
            String keyPrefix,
            RBucket<String> bucket
    ) {
        RedisProperties properties = new RedisProperties();
        properties.setKeyPrefix(keyPrefix);
        when(redissonClient.getBucket(any(String.class), eq(StringCodec.INSTANCE))).thenAnswer(inv -> bucket);
        return new RedisIdempotentTemplate(redissonClient, properties);
    }

    private static LoginAuditCommand sampleCommand(String eventId) {
        return LoginAuditCommand.builder()
                .eventId(eventId)
                .occurredAt(LocalDateTime.of(2026, 11, 27, 8, 45, 0))
                .tenantId(1001L)
                .traceId("trace-audit-1")
                .userId(9001L)
                .username("alice")
                .loginType(SolidConstants.ADMIN_LOGIN_TYPE)
                .clientIp("10.10.0.8")
                .userAgent("Mozilla/5.0 Chrome/131.0")
                .status(LogStatusEnum.SUCCESS.getCode())
                .message("登录成功")
                .build();
    }

    @Test
    @DisplayName("Redis 启用时应当通过相对键 audit:login:{eventId} 抢占 TRYING、成功置 SUCCESS 终态并静默跳过重复投递")
    @SuppressWarnings("unchecked")
    void shouldAcquireTryingTransitionToSuccessAndSkipDuplicateSilently() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RBucket<String> bucket = mock(RBucket.class);
        RedisIdempotentTemplate idempotentTemplate = realTemplateBackedByBucket(redissonClient, "solid:", bucket);

        String relativeKey = RemoteAuditLogServiceImpl.buildEventIdempotentKey("evt-100");
        assertThat(relativeKey).isEqualTo("audit:login:evt-100");
        assertThat(relativeKey).doesNotContain("ateng:solid:");
        assertThat(idempotentTemplate.formatKey(relativeKey)).isEqualTo("solid:idempotent:audit:login:evt-100");

        when(bucket.setIfAbsent(IdempotentStatus.TRYING.name(), Duration.ofHours(24)))
                .thenReturn(true)
                .thenReturn(false);

        SysLoginLogMapper loginLogMapper = mock(SysLoginLogMapper.class);
        SysUserMapper userMapper = mock(SysUserMapper.class);
        RemoteAuditLogServiceImpl service = new RemoteAuditLogServiceImpl(
                loginLogMapper,
                userMapper,
                immediateTxTemplate(),
                adaptiveWithIdempotent(idempotentTemplate, emptyEnvProvider())
        );

        service.recordLoginAudit(sampleCommand("evt-100"));
        service.recordLoginAudit(sampleCommand("evt-100"));

        verify(bucket, times(2)).setIfAbsent(IdempotentStatus.TRYING.name(), Duration.ofHours(24));
        verify(bucket, times(1)).set(IdempotentStatus.SUCCESS.name(), Duration.ofHours(24));
        verify(bucket, never()).delete();
        verify(loginLogMapper, times(1)).insert(any(SysLoginLog.class));
        verify(userMapper, times(1)).update(any(), any());
    }

    @Test
    @DisplayName("落库异常时 deleteOnFailure=true 应当自动删除 Redis 幂等键并重抛异常允许上游重试")
    @SuppressWarnings("unchecked")
    void shouldDeleteRedisKeyOnPersistenceFailureAndAllowRetry() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RBucket<String> bucket = mock(RBucket.class);
        RedisIdempotentTemplate idempotentTemplate = realTemplateBackedByBucket(redissonClient, "solid:", bucket);

        when(bucket.setIfAbsent(IdempotentStatus.TRYING.name(), Duration.ofHours(24))).thenReturn(true);

        SysLoginLogMapper loginLogMapper = mock(SysLoginLogMapper.class);
        when(loginLogMapper.insert(any(SysLoginLog.class)))
                .thenThrow(new IllegalStateException("db write timeout"))
                .thenReturn(1);
        SysUserMapper userMapper = mock(SysUserMapper.class);

        RemoteAuditLogServiceImpl service = new RemoteAuditLogServiceImpl(
                loginLogMapper,
                userMapper,
                immediateTxTemplate(),
                adaptiveWithIdempotent(idempotentTemplate, emptyEnvProvider())
        );

        assertThatThrownBy(() -> service.recordLoginAudit(sampleCommand("evt-retry-1")))
                .isInstanceOf(IllegalStateException.class);
        verify(bucket, times(1)).delete();
        verify(bucket, never()).set(eq(IdempotentStatus.SUCCESS.name()), any());

        // 重试成功置终态
        service.recordLoginAudit(sampleCommand("evt-retry-1"));
        verify(bucket, times(1)).set(IdempotentStatus.SUCCESS.name(), Duration.ofHours(24));
        verify(loginLogMapper, times(2)).insert(any(SysLoginLog.class));
    }

    @Test
    @DisplayName("Redis 抢占阶段故障应当降级本地有界去重表执行，且不因异常触发重复落库")
    @SuppressWarnings("unchecked")
    void shouldFallbackToBoundedLocalDedupWhenRedisAcquireFails() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RBucket<String> bucket = mock(RBucket.class);
        RedisIdempotentTemplate idempotentTemplate = realTemplateBackedByBucket(redissonClient, "solid:", bucket);
        when(bucket.setIfAbsent(IdempotentStatus.TRYING.name(), Duration.ofHours(24)))
                .thenThrow(new IllegalStateException("redis unreachable"));

        SysLoginLogMapper loginLogMapper = mock(SysLoginLogMapper.class);
        SysUserMapper userMapper = mock(SysUserMapper.class);
        RemoteAuditLogServiceImpl service = new RemoteAuditLogServiceImpl(
                loginLogMapper,
                userMapper,
                immediateTxTemplate(),
                adaptiveWithIdempotent(idempotentTemplate, emptyEnvProvider())
        );

        service.recordLoginAudit(sampleCommand("evt-fallback-1"));
        service.recordLoginAudit(sampleCommand("evt-fallback-1"));

        verify(loginLogMapper, times(1)).insert(any(SysLoginLog.class));
    }

    @Test
    @DisplayName("ateng.boot.redis.enabled=false 时应当忽略 RedisIdempotentTemplate 并在本地失败回滚后允许重试")
    void shouldBypassRedisTemplateWhenDisabledAndRollbackLocalDedupOnFailure() {
        RedisIdempotentTemplate idempotentTemplate = mock(RedisIdempotentTemplate.class);

        SysLoginLogMapper loginLogMapper = mock(SysLoginLogMapper.class);
        when(loginLogMapper.insert(any(SysLoginLog.class)))
                .thenThrow(new IllegalStateException("transient db error"))
                .thenReturn(1);
        SysUserMapper userMapper = mock(SysUserMapper.class);

        RemoteAuditLogServiceImpl service = new RemoteAuditLogServiceImpl(
                loginLogMapper,
                userMapper,
                immediateTxTemplate(),
                adaptiveWithIdempotent(idempotentTemplate, redisDisabledEnvProvider())
        );

        assertThatThrownBy(() -> service.recordLoginAudit(sampleCommand("evt-local-1")))
                .isInstanceOf(IllegalStateException.class);
        service.recordLoginAudit(sampleCommand("evt-local-1"));
        service.recordLoginAudit(sampleCommand("evt-local-1"));

        verify(loginLogMapper, times(2)).insert(any(SysLoginLog.class));
        verifyNoInteractions(idempotentTemplate);
    }

    @Test
    @DisplayName("空事件或空白 eventId 应当安全跳过或直写；离线有界去重表超过 10000 上限应当自动淘汰旧键")
    void shouldHandleNullOrBlankEventIdAndEnforceLocalDedupCapacityCap() {
        SysLoginLogMapper loginLogMapper = mock(SysLoginLogMapper.class);
        SysUserMapper userMapper = mock(SysUserMapper.class);
        RemoteAuditLogServiceImpl service = new RemoteAuditLogServiceImpl(
                loginLogMapper,
                userMapper,
                immediateTxTemplate(),
                adaptiveWithIdempotent(null, emptyEnvProvider())
        );

        service.recordLoginAudit(null);
        service.recordLoginAudit(sampleCommand("  "));
        verify(loginLogMapper, times(1)).insert(any(SysLoginLog.class));
        assertThat(service.localDedupSize()).isZero();

        for (int i = 0; i < 10_040; i++) {
            service.recordLoginAudit(sampleCommand("cap-evt-" + i));
        }
        assertThat(service.localDedupSize()).isLessThanOrEqualTo(AdaptiveRedisSupport.DEFAULT_LOCAL_MAX_CAPACITY);
    }
}

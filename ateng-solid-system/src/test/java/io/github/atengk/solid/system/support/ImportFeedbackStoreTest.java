package io.github.atengk.solid.system.support;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.redis.properties.RedisProperties;
import io.github.atengk.boot.redis.util.RedisUtil;
import io.github.atengk.solid.system.properties.SolidSystemProperties;
import io.github.atengk.solid.system.support.ImportFeedbackStore.FeedbackFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.github.atengk.solid.system.support.CacheTestFixtures.adaptiveSupport;
import static io.github.atengk.solid.system.support.CacheTestFixtures.emptyEnvProvider;
import static io.github.atengk.solid.system.support.CacheTestFixtures.offlineAdaptiveSupport;
import static io.github.atengk.solid.system.support.CacheTestFixtures.providerOf;
import static io.github.atengk.solid.system.support.CacheTestFixtures.redisDisabledEnvProvider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ImportFeedbackStore} 批注双键（{@link RedisUtil}）持久化与本地有界降级 Layer 1 单元测试。
 *
 * @author Ateng
 * @since 2026-11-25
 */
class ImportFeedbackStoreTest {

    @AfterEach
    void tearDown() {
        RedisUtil.init(null);
    }

    @SuppressWarnings("unchecked")
    private static RedisHarness initRedisHarness() {
        Map<String, Object> storage = new HashMap<>();
        Map<String, Duration> ttlMap = new HashMap<>();
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, Object> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenAnswer(inv -> storage.get(inv.<String>getArgument(0)));
        doAnswer(inv -> {
            String key = inv.getArgument(0);
            long timeout = inv.getArgument(2);
            TimeUnit unit = inv.getArgument(3);
            storage.put(key, inv.getArgument(1));
            ttlMap.put(key, Duration.ofMillis(unit.toMillis(timeout)));
            return null;
        }).when(valueOps).set(anyString(), any(), anyLong(), any(TimeUnit.class));
        when(redisTemplate.delete(anyString())).thenAnswer(inv -> storage.remove(inv.<String>getArgument(0)) != null);

        RedisProperties properties = new RedisProperties();
        properties.setKeyPrefix("solid:");
        RedisUtil redisUtil = new RedisUtil(redisTemplate, properties);
        return new RedisHarness(redisUtil, redisTemplate, valueOps, storage, ttlMap);
    }

    private record RedisHarness(
            RedisUtil redisUtil,
            RedisTemplate<String, Object> redisTemplate,
            ValueOperations<String, Object> valueOps,
            Map<String, Object> storage,
            Map<String, Duration> ttlMap
    ) {
    }

    @Test
    @DisplayName("本地有界降级模式下应校验 4MB 上限、路径穿越/403/404 隔离以及 50 条 LRU 有界淘汰")
    void shouldEnforceIsolationSizeAndLocalBoundInFallbackMode() {
        SolidSystemProperties props = new SolidSystemProperties();
        props.getUser().setImportFeedbackTtl(Duration.ofMinutes(15));
        ImportFeedbackStore store = new ImportFeedbackStore(offlineAdaptiveSupport(), providerOf(null), props);

        byte[] sampleBytes = "excel-feedback-bytes".getBytes(StandardCharsets.UTF_8);
        String firstToken = store.save(100L, 200L, "用户导入错误批注.xlsx", sampleBytes);
        String secondToken = store.save(100L, 200L, "第二批注.xlsx", sampleBytes);
        assertThat(firstToken).isNotBlank();

        FeedbackFile loaded = store.load(firstToken, 100L, 200L);
        assertThat(loaded.filename()).isEqualTo("用户导入错误批注.xlsx");
        assertThat(loaded.content()).isEqualTo(sampleBytes);

        // 跨租户与跨操作人返回 403
        assertThatThrownBy(() -> store.load(firstToken, 101L, 200L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(CommonResultCode.FORBIDDEN.getCode()));
        assertThatThrownBy(() -> store.load(firstToken, 100L, 201L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(CommonResultCode.FORBIDDEN.getCode()));

        // 空令牌、非法路径穿越令牌或不存在令牌均返回 404
        assertThatThrownBy(() -> store.load("  ", 100L, 200L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(CommonResultCode.NOT_FOUND.getCode()));
        assertThatThrownBy(() -> store.load("../bad-token", 100L, 200L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(CommonResultCode.NOT_FOUND.getCode()));
        assertThatThrownBy(() -> store.load("missing-token-1234567890", 100L, 200L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(CommonResultCode.NOT_FOUND.getCode()));

        // 超过 4MB 拒绝暂存
        byte[] oversized = new byte[ImportFeedbackStore.MAX_FEEDBACK_BYTES + 1];
        assertThatThrownBy(() -> store.save(100L, 200L, "big.xlsx", oversized))
                .isInstanceOf(BusinessException.class);

        // 触发 50 条 LRU 有界淘汰（firstToken 因前面已 load 刷新了活跃度，插入 49 条后 secondToken 最先被淘汰）
        for (int i = 0; i < ImportFeedbackStore.MAX_LOCAL_ENTRIES - 1; i++) {
            store.save(100L, 200L, "batch_" + i + ".xlsx", sampleBytes);
        }
        assertThat(store.localSize()).isEqualTo(ImportFeedbackStore.MAX_LOCAL_ENTRIES);
        assertThat(store.load(firstToken, 100L, 200L).content()).isEqualTo(sampleBytes);
        assertThatThrownBy(() -> store.load(secondToken, 100L, 200L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(CommonResultCode.NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("RedisUtil 双键模式应按 key-prefix 隔离存储并先校验 meta，403/404 时禁止读取 bin 且能处理 bin 损坏")
    void shouldPersistViaRedisUtilAndEnforceMetaFirstSecurityBeforeReadingBin() {
        RedisHarness harness = initRedisHarness();
        SolidSystemProperties props = new SolidSystemProperties();
        props.getUser().setImportFeedbackTtl(Duration.ofMinutes(15));
        ImportFeedbackStore store = new ImportFeedbackStore(
                adaptiveSupport(null, null, null, null, emptyEnvProvider()),
                providerOf(harness.redisUtil()),
                props
        );

        byte[] content = "PK-feedback-content".getBytes(StandardCharsets.UTF_8);
        String token = store.save(10L, 99L, "错误批注.xlsx", content);

        String expectedMetaKey = "solid:" + ImportFeedbackStore.META_KEY_PREFIX + token;
        String expectedBinKey = "solid:" + ImportFeedbackStore.BIN_KEY_PREFIX + token;
        assertThat(ImportFeedbackStore.META_KEY_PREFIX).isEqualTo("excel:feedback:meta:");
        assertThat(ImportFeedbackStore.BIN_KEY_PREFIX).isEqualTo("excel:feedback:bin:");
        assertThat(harness.storage()).containsKeys(expectedMetaKey, expectedBinKey);
        assertThat(harness.storage().keySet()).noneMatch(k -> k.contains("ateng:solid:"));
        assertThat(harness.storage().get(expectedBinKey))
                .isEqualTo(Base64.getEncoder().encodeToString(content));
        assertThat(harness.ttlMap().get(expectedMetaKey)).isEqualTo(Duration.ofMinutes(15));
        assertThat(harness.ttlMap().get(expectedBinKey)).isEqualTo(Duration.ofMinutes(15));

        FeedbackFile loaded = store.load(token, 10L, 99L);
        assertThat(loaded.filename()).isEqualTo("错误批注.xlsx");
        assertThat(loaded.content()).isEqualTo(content);

        // 校验 meta-first：跨租户 403 拒绝时严禁访问 4MB bin 大键
        clearInvocations(harness.valueOps());
        assertThatThrownBy(() -> store.load(token, 999L, 99L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(CommonResultCode.FORBIDDEN.getCode()));
        verify(harness.valueOps()).get(expectedMetaKey);
        verify(harness.valueOps(), never()).get(expectedBinKey);

        // 校验 meta-first：跨操作人 403 拒绝时严禁访问 4MB bin 大键
        clearInvocations(harness.valueOps());
        assertThatThrownBy(() -> store.load(token, 10L, 888L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(CommonResultCode.FORBIDDEN.getCode()));
        verify(harness.valueOps()).get(expectedMetaKey);
        verify(harness.valueOps(), never()).get(expectedBinKey);

        // 校验 meta-first：令牌不存在 404 时严禁访问 4MB bin 大键
        clearInvocations(harness.valueOps());
        String missingToken = "nonexistenttoken1234567890abcdef";
        assertThatThrownBy(() -> store.load(missingToken, 10L, 99L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(CommonResultCode.NOT_FOUND.getCode()));
        verify(harness.valueOps()).get("solid:" + ImportFeedbackStore.META_KEY_PREFIX + missingToken);
        verify(harness.valueOps(), never()).get("solid:" + ImportFeedbackStore.BIN_KEY_PREFIX + missingToken);

        // 归属合法但 bin 损坏或丢失时应返回 404
        harness.storage().put(expectedBinKey, "!!!not-valid-base64!!!");
        assertThatThrownBy(() -> store.load(token, 10L, 99L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(CommonResultCode.NOT_FOUND.getCode()));

        harness.storage().remove(expectedBinKey);
        assertThatThrownBy(() -> store.load(token, 10L, 99L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(CommonResultCode.NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("Redis 写入全失败或写完 bin 后写 meta 半途失败时，均应降级本地有界存储且可恢复下载")
    void shouldDegradeToLocalWhenRedisFailsCompletelyOrPartially() {
        // 1. 全失败场景
        RedisHarness harness = initRedisHarness();
        doThrow(new QueryTimeoutException("redis unavailable"))
                .when(harness.valueOps())
                .set(anyString(), any(), anyLong(), any(TimeUnit.class));
        when(harness.valueOps().get(anyString())).thenThrow(new QueryTimeoutException("redis read timeout"));

        ImportFeedbackStore store = new ImportFeedbackStore(
                adaptiveSupport(null, null, null, null, emptyEnvProvider()),
                providerOf(harness.redisUtil()),
                new SolidSystemProperties()
        );

        byte[] content = "fallback-bytes".getBytes(StandardCharsets.UTF_8);
        String token = store.save(55L, 66L, "降级恢复测试.xlsx", content);

        FeedbackFile loaded = store.load(token, 55L, 66L);
        assertThat(loaded.filename()).isEqualTo("降级恢复测试.xlsx");
        assertThat(loaded.content()).isEqualTo(content);

        // 2. 写 bin 成功但写 meta 失败的半途异常场景：Redis 随后恢复查空时应从本地降级兜底取回
        RedisHarness partialHarness = initRedisHarness();
        doThrow(new QueryTimeoutException("meta write timeout"))
                .when(partialHarness.valueOps())
                .set(argThat(key -> key != null && key.contains(ImportFeedbackStore.META_KEY_PREFIX)),
                        any(), anyLong(), any(TimeUnit.class));
        ImportFeedbackStore partialStore = new ImportFeedbackStore(
                adaptiveSupport(null, null, null, null, emptyEnvProvider()),
                providerOf(partialHarness.redisUtil()),
                new SolidSystemProperties()
        );

        String partialToken = partialStore.save(55L, 66L, "半途降级测试.xlsx", content);
        assertThat(partialStore.load(partialToken, 55L, 66L).content()).isEqualTo(content);
    }

    @Test
    @DisplayName("当 ateng.boot.redis.enabled=false 时应忽略 RedisUtil 且零访问 RedisTemplate")
    void shouldBypassRedisUtilWhenRedisExplicitlyDisabled() {
        RedisHarness harness = initRedisHarness();
        ImportFeedbackStore store = new ImportFeedbackStore(
                adaptiveSupport(null, null, null, null, redisDisabledEnvProvider()),
                providerOf(harness.redisUtil()),
                new SolidSystemProperties()
        );

        byte[] content = "disabled-redis-bytes".getBytes(StandardCharsets.UTF_8);
        String token = store.save(77L, 88L, "禁用Redis测试.xlsx", content);
        FeedbackFile loaded = store.load(token, 77L, 88L);

        assertThat(loaded.filename()).isEqualTo("禁用Redis测试.xlsx");
        assertThat(loaded.content()).isEqualTo(content);
        verifyNoInteractions(harness.redisTemplate());
    }
}

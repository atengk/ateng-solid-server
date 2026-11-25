package io.github.atengk.solid.system.support;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.solid.system.properties.SolidSystemProperties;
import io.github.atengk.solid.system.support.ImportFeedbackStore.FeedbackFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ImportFeedbackStore} 双模批注暂存组件 Layer 1 单元测试（覆盖 Redis 双键、4MB 上限、403/404 隔离与本地有界淘汰）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
class ImportFeedbackStoreTest {

    @Test
    @DisplayName("本地有界降级模式应当执行 50 条容量上限淘汰、4MB 上限拦截以及 403/404 归属隔离")
    void shouldEnforceBoundsAndOwnershipIsolationInLocalMode() {
        SolidSystemProperties props = new SolidSystemProperties();
        props.getUser().setImportFeedbackTtl(Duration.ofMinutes(15));
        ImportFeedbackStore store = new ImportFeedbackStore(emptyProvider(), props, emptyProvider());

        byte[] sampleBytes = "mock-xlsx-payload".getBytes(StandardCharsets.UTF_8);
        String token = store.save(100L, 200L, "用户导入错误批注.xlsx", sampleBytes);

        FeedbackFile loaded = store.load(token, 100L, 200L);
        assertThat(loaded.filename()).isEqualTo("用户导入错误批注.xlsx");
        assertThat(loaded.content()).isEqualTo(sampleBytes);

        // 跨用户与跨租户访问必须抛出 403
        assertThatThrownBy(() -> store.load(token, 100L, 201L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(CommonResultCode.FORBIDDEN.getCode()));
        assertThatThrownBy(() -> store.load(token, 101L, 200L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(CommonResultCode.FORBIDDEN.getCode()));

        // 不存在或非法 token 抛出 404
        assertThatThrownBy(() -> store.load("0123456789abcdef0123456789abcdef", 100L, 200L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(CommonResultCode.NOT_FOUND.getCode()));
        assertThatThrownBy(() -> store.load("../bad-token", 100L, 200L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(CommonResultCode.NOT_FOUND.getCode()));

        // 超过 4MB 体积拒绝暂存
        byte[] oversized = new byte[ImportFeedbackStore.MAX_FEEDBACK_BYTES + 1];
        assertThatThrownBy(() -> store.save(100L, 200L, "big.xlsx", oversized))
                .isInstanceOf(BusinessException.class);

        // 连续写入超过 50 条后本地暂存容量受 MAX_LOCAL_ENTRIES 封顶，最旧条目被淘汰返回 404
        for (int i = 0; i < ImportFeedbackStore.MAX_LOCAL_ENTRIES + 5; i++) {
            store.save(100L, 200L, "batch_" + i + ".xlsx", sampleBytes);
        }
        assertThat(store.localSize()).isEqualTo(ImportFeedbackStore.MAX_LOCAL_ENTRIES);
        assertThatThrownBy(() -> store.load(token, 100L, 200L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(CommonResultCode.NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("Redis 激活态应当使用 meta 与 bin 双键写入并校验归属隔离")
    @SuppressWarnings("unchecked")
    void shouldUseRedisDualKeysWhenRedisIsActive() {
        Map<String, String> redisMemory = new ConcurrentHashMap<>();
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        doAnswer(inv -> {
            redisMemory.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(valueOps.get(anyString())).thenAnswer(inv -> redisMemory.get(inv.getArgument(0)));

        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);

        Environment env = mock(Environment.class);
        when(env.getProperty("ateng.boot.redis.enabled", Boolean.class, Boolean.TRUE)).thenReturn(true);
        ObjectProvider<Environment> envProvider = mock(ObjectProvider.class);
        when(envProvider.getIfAvailable()).thenReturn(env);

        SolidSystemProperties props = new SolidSystemProperties();
        ImportFeedbackStore store = new ImportFeedbackStore(redisProvider, props, envProvider);

        byte[] content = "redis-xlsx-bytes".getBytes(StandardCharsets.UTF_8);
        String token = store.save(10L, 99L, "错误批注.xlsx", content);

        assertThat(redisMemory).containsKey(ImportFeedbackStore.META_KEY_PREFIX + token);
        assertThat(redisMemory).containsKey(ImportFeedbackStore.BIN_KEY_PREFIX + token);
        assertThat(redisMemory.get(ImportFeedbackStore.BIN_KEY_PREFIX + token))
                .isEqualTo(Base64.getEncoder().encodeToString(content));
        assertThat(store.localSize()).isZero();

        FeedbackFile loaded = store.load(token, 10L, 99L);
        assertThat(loaded.content()).isEqualTo(content);

        assertThatThrownBy(() -> store.load(token, 10L, 100L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(CommonResultCode.FORBIDDEN.getCode()));
    }

    @Test
    @DisplayName("Redis 写入瞬态异常降级本地后若 Redis 恢复查空，仍应回查本地有界暂存")
    @SuppressWarnings("unchecked")
    void shouldFallbackToLocalStoreWhenRedisRecoversWithCacheMissAfterWriteFailure() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        doAnswer(inv -> {
            throw new IllegalStateException("redis write timeout");
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(valueOps.get(anyString())).thenReturn(null);

        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);

        Environment env = mock(Environment.class);
        when(env.getProperty("ateng.boot.redis.enabled", Boolean.class, Boolean.TRUE)).thenReturn(true);
        ObjectProvider<Environment> envProvider = mock(ObjectProvider.class);
        when(envProvider.getIfAvailable()).thenReturn(env);

        ImportFeedbackStore store = new ImportFeedbackStore(redisProvider, new SolidSystemProperties(), envProvider);
        byte[] content = "fallback-recovered-xlsx".getBytes(StandardCharsets.UTF_8);
        String token = store.save(55L, 66L, "降级恢复测试.xlsx", content);

        assertThat(store.localSize()).isEqualTo(1);
        FeedbackFile loaded = store.load(token, 55L, 66L);
        assertThat(loaded.filename()).isEqualTo("降级恢复测试.xlsx");
        assertThat(loaded.content()).isEqualTo(content);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        return mock(ObjectProvider.class);
    }
}

package io.github.atengk.solid;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.MDC;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.Assert;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

/**
 * 本地真实中间件（MySQL 8.4 + Redis 8.x + Redisson）专项集成测试公共基类，提供运行级隔离前缀、容器共享配置与安全清理工具。
 *
 * @author Ateng
 * @since 2026-11-27
 */
@SpringBootTest(
        classes = SolidApplication.class,
        properties = {
                "ateng.boot.redis.enabled=true",
                "ateng.boot.redis.two-level.enabled=true",
                "ateng.boot.web.ratelimit.enabled=true",
                "ateng.boot.web.shutdown.enabled=false",
                "ateng.boot.web.shutdown.drain-timeout=0s",
                "ateng.boot.mybatis-plus.sql-log.enabled=false",
                "ateng.boot.mybatis-plus.tenant.enabled=false",
                "ateng.solid.tenant.enabled=false",
                "ateng.solid.auth.captcha-enabled=false",
                "ateng.solid.init.enabled=false"
        }
)
@ActiveProfiles("dev")
@AutoConfigureMockMvc
abstract class LocalMiddlewareTestSupport {

    static final String TEST_RUN_ID = "lt"
            + Long.toHexString(System.currentTimeMillis())
            + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0xFFFF));

    static final String TEST_KEY_PREFIX = "solid:localtest:" + TEST_RUN_ID + ":";
    static final String TEST_RATELIMIT_KEY_PREFIX = TEST_KEY_PREFIX + "rate_limit:";

    @DynamicPropertySource
    static void overrideIsolatedRedisPrefixes(DynamicPropertyRegistry registry) {
        registry.add("ateng.boot.redis.key-prefix", () -> TEST_KEY_PREFIX);
        registry.add("ateng.boot.web.ratelimit.key-prefix", () -> TEST_RATELIMIT_KEY_PREFIX);
    }

    @AfterEach
    void clearThreadContexts() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        DataPermissionContextHolder.clear();
        MDC.clear();
        Thread.interrupted();
    }

    /**
     * 按隔离前缀游标扫描 Redis 中匹配的键，严禁扫描未包含运行隔离前缀的键空间。
     *
     * @param stringRedisTemplate 字符串 Redis 模板
     * @param pattern             必须包含 {@link #TEST_KEY_PREFIX} 的匹配模式
     * @return 匹配的物理键集合
     */
    protected Set<String> scanIsolatedKeys(StringRedisTemplate stringRedisTemplate, String pattern) {
        Assert.notNull(stringRedisTemplate, "stringRedisTemplate 不能为空");
        Assert.hasText(pattern, "pattern 不能为空");
        Assert.isTrue(pattern.contains(TEST_KEY_PREFIX), "仅允许扫描隔离测试前缀下的 Redis 键");

        Set<String> keys = new LinkedHashSet<>();
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(500).build();
        stringRedisTemplate.execute((RedisCallback<Void>) connection -> {
            try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            } catch (Exception ex) {
                throw new IllegalStateException("游标扫描隔离 Redis 键失败: pattern=" + pattern, ex);
            }
            return null;
        });
        return keys;
    }

    /**
     * 按隔离前缀分批删除测试产生的 Redis 键（含 Redisson 哈希标签衍生键）。
     *
     * @param stringRedisTemplate 字符串 Redis 模板
     * @param pattern             必须包含 {@link #TEST_KEY_PREFIX} 的匹配模式
     */
    protected void deleteIsolatedKeysByPattern(StringRedisTemplate stringRedisTemplate, String pattern) {
        Set<String> matchedKeys = scanIsolatedKeys(stringRedisTemplate, pattern);
        if (matchedKeys.isEmpty()) {
            return;
        }
        List<String> batch = new ArrayList<>(100);
        for (String key : matchedKeys) {
            batch.add(key);
            if (batch.size() >= 100) {
                stringRedisTemplate.delete(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            stringRedisTemplate.delete(batch);
        }
    }

    /**
     * 轻量轮询等待直到条件满足或超时，避免依赖脆弱的固定时长休眠。
     *
     * @param timeout        最长等待时长
     * @param condition      目标条件断言
     * @param failureMessage 超时失败提示
     */
    protected void awaitUntil(Duration timeout, BooleanSupplier condition, String failureMessage) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待异步条件时线程被中断: " + failureMessage, ex);
            }
        }
        if (!condition.getAsBoolean()) {
            throw new AssertionError(failureMessage);
        }
    }
}

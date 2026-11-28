package io.github.atengk.solid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.autoconfigure.AtengAsyncAutoConfiguration;
import io.github.atengk.boot.observability.autoconfigure.AtengHealthAutoConfiguration;
import io.github.atengk.boot.observability.autoconfigure.AtengObservabilityAutoConfiguration;
import io.github.atengk.boot.observability.health.DynamicDatasourceHealthIndicator;
import io.github.atengk.boot.observability.health.StorageHealthIndicator;
import io.github.atengk.boot.storage.autoconfigure.AtengStorageAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单端口模式下 Actuator 可观测管理面（健康探针分组隔离、构建元信息、Prometheus 标签、
 * 安全过滤守卫及 {@code [B24]} 存储探针装配顺序）集成测试。
 *
 * @author Ateng
 * @since 2026-11-28
 */
@SpringBootTest(
        classes = SolidApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:observability_actuator_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        }
)
@ActiveProfiles("test")
class ObservabilityActuatorIntegrationTest {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @LocalServerPort
    private int serverPort;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("应当暴露黄金健康探针并严格落实 liveness / readiness 分组隔离与 db 去重")
    void shouldExposeHealthAndEnforceProbeGrouping() throws Exception {
        // 1. 容器 Bean 去重检查：保留防假死 dynamicDatasource，关闭 Spring 原生 db 重复探针
        assertThat(applicationContext.getBeansOfType(DynamicDatasourceHealthIndicator.class)).hasSize(1);
        assertThat(applicationContext.containsBean("dbHealthContributor")).isFalse();

        // 2. /actuator/health 聚合响应验证
        HttpResponse<String> healthResp = sendGet("/actuator/health");
        assertThat(healthResp.statusCode()).isEqualTo(200);
        JsonNode healthJson = objectMapper.readTree(healthResp.body());
        assertThat(healthJson.path("status").asText()).isEqualTo("UP");
        Set<String> healthKeys = fieldNames(healthJson.path("components"));
        assertThat(healthKeys)
                .contains("dynamicDatasource", "livenessState", "readinessState", "ping")
                .doesNotContain("db", "redis", "storage");

        // 3. /actuator/health/liveness 严禁出现任何外部物理依赖探针
        HttpResponse<String> livenessResp = sendGet("/actuator/health/liveness");
        assertThat(livenessResp.statusCode()).isEqualTo(200);
        JsonNode livenessJson = objectMapper.readTree(livenessResp.body());
        assertThat(livenessJson.path("status").asText()).isEqualTo("UP");
        assertThat(fieldNames(livenessJson.path("components")))
                .containsExactlyInAnyOrder("livenessState", "ping");

        // 4. /actuator/health/readiness 在 test Profile 下精准收敛为 readinessState + dynamicDatasource
        HttpResponse<String> readinessResp = sendGet("/actuator/health/readiness");
        assertThat(readinessResp.statusCode()).isEqualTo(200);
        JsonNode readinessJson = objectMapper.readTree(readinessResp.body());
        assertThat(readinessJson.path("status").asText()).isEqualTo("UP");
        assertThat(fieldNames(readinessJson.path("components")))
                .containsExactlyInAnyOrder("readinessState", "dynamicDatasource");
    }

    @Test
    @DisplayName("/actuator/info 应当仅输出 build 构建元信息且屏蔽 env/java/os 泄漏")
    void shouldExposeBuildInfoWithoutEnvironmentLeak() throws Exception {
        assertThat(environment.getProperty("management.info.env.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("management.info.build.enabled", Boolean.class)).isTrue();

        HttpResponse<String> infoResp = sendGet("/actuator/info");
        assertThat(infoResp.statusCode()).isEqualTo(200);

        JsonNode infoJson = objectMapper.readTree(infoResp.body());
        assertThat(infoJson.has("build")).isTrue();
        assertThat(infoJson.path("build").path("artifact").asText()).isEqualTo("ateng-solid-bootstrap");
        assertThat(infoJson.path("build").path("version").asText()).isNotBlank();
        assertThat(infoJson.has("env")).isFalse();
        assertThat(infoJson.has("java")).isFalse();
        assertThat(infoJson.has("os")).isFalse();
    }

    @Test
    @DisplayName("/actuator/prometheus 应当输出全局 application/env 标签及受管线程池指标")
    void shouldExposePrometheusWithCommonTagsAndThreadPoolMetrics() throws Exception {
        HttpResponse<String> promResp = sendGet("/actuator/prometheus");
        assertThat(promResp.statusCode()).isEqualTo(200);

        String body = promResp.body();
        assertThat(body)
                .contains("application=\"ateng-solid-server\"")
                .contains("env=\"test\"")
                .contains("ateng_threadpool_");
    }

    @Test
    @DisplayName("ActuatorEndpointSecurityFilter 应当默认拦截 /actuator 根索引及剔除出白名单的 /actuator/pre-stop")
    void shouldBlockRootIndexAndNonWhitelistedPreStopEndpoint() throws Exception {
        HttpResponse<String> rootResp = sendGet("/actuator");
        assertThat(rootResp.statusCode()).isEqualTo(403);
        assertThat(rootResp.body()).contains("\"code\":403");

        HttpResponse<String> preStopResp = sendGet("/actuator/pre-stop");
        assertThat(preStopResp.statusCode()).isEqualTo(403);
        assertThat(preStopResp.body()).contains("\"code\":403");
    }

    @Test
    @DisplayName("[B24] 同时启用 Storage 与 Observability 时，防假死 StorageHealthIndicator 应可靠胜出")
    void shouldPreferObservabilityStorageHealthIndicatorOverBasicStorageProbe(@TempDir Path tempDir) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AtengStorageAutoConfiguration.class,
                        AtengAsyncAutoConfiguration.class,
                        AtengObservabilityAutoConfiguration.class,
                        AtengHealthAutoConfiguration.class
                ))
                .withPropertyValues(
                        "ateng.boot.storage.enabled=true",
                        "ateng.boot.storage.type=local",
                        "ateng.boot.storage.local.base-path=" + tempDir,
                        "ateng.boot.storage.local.secret-key=solid-b24-test-storage-secret-key"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(StorageHealthIndicator.class);
                    assertThat(context).doesNotHaveBean(io.github.atengk.boot.storage.health.StorageHealthIndicator.class);
                    assertThat(context.getBean("storageHealthIndicator")).isInstanceOf(StorageHealthIndicator.class);

                    Map<String, HealthIndicator> indicators = context.getBeansOfType(HealthIndicator.class);
                    long storageCount = indicators.values().stream()
                            .filter(bean -> bean instanceof StorageHealthIndicator
                                    || bean instanceof io.github.atengk.boot.storage.health.StorageHealthIndicator)
                            .count();
                    assertThat(storageCount).isEqualTo(1L);
                });
    }

    private HttpResponse<String> sendGet(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        Iterator<String> iterator = node.fieldNames();
        while (iterator.hasNext()) {
            names.add(iterator.next());
        }
        return names;
    }
}

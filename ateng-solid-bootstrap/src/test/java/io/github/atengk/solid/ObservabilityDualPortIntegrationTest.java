package io.github.atengk.solid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.observability.event.ActuatorAccessDeniedEvent;
import io.github.atengk.boot.observability.security.ActuatorEndpointSecurityFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产形态双端口物理隔离（{@code management.server.port=0}）、{@code [B25]} 管理端口子上下文纵深防御
 * （404 隐身拦截、Token 核验、审计事件）与 {@code /actuator/pre-stop} 触发就绪探针排空集成测试。
 *
 * @author Ateng
 * @since 2026-11-28
 */
@SpringBootTest(
        classes = {
                SolidApplication.class,
                ObservabilityDualPortIntegrationTest.AccessDeniedRecorderConfig.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:observability_dual_port_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "management.server.port=0",
                "management.server.address=127.0.0.1",
                "management.endpoints.web.exposure.include=health,info,prometheus,preStop",
                "management.endpoint.health.show-details=never",
                "management.endpoint.health.group.liveness.show-details=never",
                "management.endpoint.health.group.readiness.show-details=never",
                "ateng.boot.web.shutdown.enabled=true",
                "ateng.boot.web.shutdown.drain-timeout=0s",
                "ateng.boot.observability.metrics.common-tags.env=prod",
                "ateng.boot.observability.security.stealth-mode=true",
                "ateng.boot.observability.security.token=solid-test-mgmt-token",
                "ateng.boot.observability.security.allowed-ip-ranges=10.254.0.0/16"
        }
)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ObservabilityDualPortIntegrationTest {

    private static final String TEST_TOKEN = "solid-test-mgmt-token";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @LocalServerPort
    private int serverPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccessDeniedRecorder accessDeniedRecorder;

    @Test
    @DisplayName("应当在独立管理端口启用隐身安全守卫，隔离业务端口并闭环 preStop 排空链路")
    void shouldIsolateManagementPortProtectPreStopAndDrainReadiness() throws Exception {
        assertThat(serverPort).isPositive();
        assertThat(managementPort).isPositive().isNotEqualTo(serverPort);

        // 1. 业务端口物理隔离：不挂载 Actuator 端点，请求落入全局 NoResourceFoundException 处理器返回业务 Result 404
        HttpResponse<String> bizHealthResp = sendRequest(serverPort, "GET", "/actuator/health", null);
        JsonNode bizHealthJson = objectMapper.readTree(bizHealthResp.body());
        assertThat(bizHealthJson.path("code").asInt()).isEqualTo(404);
        assertThat(bizHealthJson.has("status")).isFalse();

        // 2. 管理端口黄金探针免密放行且按 prod 基线隐藏详细组件结构
        for (String path : new String[]{"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
            HttpResponse<String> resp = sendRequest(managementPort, "GET", path, null);
            assertThat(resp.statusCode()).as("管理端口白名单探针 %s 应返回 200", path).isEqualTo(200);
            JsonNode json = objectMapper.readTree(resp.body());
            assertThat(json.path("status").asText()).isEqualTo("UP");
            assertThat(json.has("components")).isFalse();
        }

        HttpResponse<String> promResp = sendRequest(managementPort, "GET", "/actuator/prometheus", null);
        assertThat(promResp.statusCode()).isEqualTo(200);
        assertThat(promResp.body())
                .contains("application=\"ateng-solid-server\"")
                .contains("env=\"prod\"");

        // 3. [B25] 双端口子上下文纵深防御：未授权访问 /actuator 与 /actuator/pre-stop 触发 404 隐身拦截并发布审计事件
        accessDeniedRecorder.clear();
        HttpResponse<String> deniedRootResp = sendRequest(managementPort, "GET", "/actuator", null);
        assertThat(deniedRootResp.statusCode()).isEqualTo(404);
        assertThat(deniedRootResp.body()).contains("\"code\":404");

        HttpResponse<String> deniedPreStopResp = sendRequest(managementPort, "POST", "/actuator/pre-stop", null);
        assertThat(deniedPreStopResp.statusCode()).isEqualTo(404);
        assertThat(deniedPreStopResp.body()).contains("\"code\":404");

        assertThat(accessDeniedRecorder.getEvents())
                .extracting(ActuatorAccessDeniedEvent::getUri)
                .contains("/actuator", "/actuator/pre-stop");

        // 4. 携带正确 Token 放行管理索引与 /actuator/pre-stop 主动排空
        HttpResponse<String> authedRootResp = sendRequest(managementPort, "GET", "/actuator", TEST_TOKEN);
        assertThat(authedRootResp.statusCode()).isEqualTo(200);
        assertThat(authedRootResp.body()).contains("preStop");

        HttpResponse<String> authedPreStopResp = sendRequest(managementPort, "POST", "/actuator/pre-stop", TEST_TOKEN);
        assertThat(authedPreStopResp.statusCode()).isEqualTo(200);
        JsonNode drainJson = objectMapper.readTree(authedPreStopResp.body());
        assertThat(drainJson.path("status").asText()).isEqualTo("REFUSING_TRAFFIC");
        assertThat(drainJson.path("executed").asBoolean()).isTrue();

        // 5. 排空后 readiness 立即翻转为 503 OUT_OF_SERVICE，而 liveness 仍维持 200 UP
        HttpResponse<String> readinessAfterDrain = sendRequest(managementPort, "GET", "/actuator/health/readiness", null);
        assertThat(readinessAfterDrain.statusCode()).isEqualTo(503);
        assertThat(objectMapper.readTree(readinessAfterDrain.body()).path("status").asText())
                .isEqualTo("OUT_OF_SERVICE");

        HttpResponse<String> livenessAfterDrain = sendRequest(managementPort, "GET", "/actuator/health/liveness", null);
        assertThat(livenessAfterDrain.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(livenessAfterDrain.body()).path("status").asText()).isEqualTo("UP");
    }

    private static HttpResponse<String> sendRequest(int port, String method, String path, String token)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5));
        if (token != null) {
            builder.header(ActuatorEndpointSecurityFilter.ACTUATOR_TOKEN_HEADER, token);
        }
        if ("POST".equalsIgnoreCase(method)) {
            builder.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            builder.GET();
        }
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 捕获管理端口子上下文发布的安全拒绝审计事件测试配置。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class AccessDeniedRecorderConfig {

        @Bean
        AccessDeniedRecorder accessDeniedRecorder() {
            return new AccessDeniedRecorder();
        }
    }

    /**
     * 安全拒绝审计事件内存收集器。
     */
    static class AccessDeniedRecorder {

        private final List<ActuatorAccessDeniedEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        void onAccessDenied(ActuatorAccessDeniedEvent event) {
            events.add(event);
        }

        List<ActuatorAccessDeniedEvent> getEvents() {
            return List.copyOf(events);
        }

        void clear() {
            events.clear();
        }
    }
}

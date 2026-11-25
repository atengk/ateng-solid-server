package io.github.atengk.solid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.satoken.defense.LoginFailureLockService;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.guard.StorageGuard;
import io.github.atengk.boot.storage.model.PresignedDownloadRequest;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.properties.StorageGuardProperties;
import io.github.atengk.boot.storage.template.StorageOperations;
import io.github.atengk.boot.storage.template.StorageTemplate;
import io.github.atengk.boot.web.captcha.properties.CaptchaProperties;
import io.github.atengk.boot.web.captcha.store.CaptchaStore;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.unit.DataSize;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 图形验证码一次一密（{@code 101001}）、IP 维度防爆破（{@code 101004}）、{@code X-Tenant-Id} 登录头、个人资料唯一性冲突（{@code 102002}）与头像魔数（{@code 100004}）MockMvc 契约测试。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@SpringBootTest(
        classes = {SolidApplication.class, AuthSecurityEdgeMockMvcTest.MockStorageConfig.class},
        properties = {
                "ateng.solid.init.enabled=true",
                "ateng.solid.tenant.enabled=true",
                "ateng.boot.mybatis-plus.tenant.enabled=true",
                "ateng.solid.auth.captcha-enabled=true",
                "ateng.boot.satoken.login-defense.ip-max-retry-count=2",
                "spring.datasource.url=jdbc:h2:mem:auth_security_edge_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        }
)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthSecurityEdgeMockMvcTest {

    private static final long TENANT_ID = 7001L;
    private static final String ATTACKER_IP = "203.0.113.55";
    private static final byte[] VALID_PNG_BYTES = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01
    };

    @TestConfiguration
    static class MockStorageConfig {

        @Bean
        StorageGuard edgeStorageGuard() {
            StorageGuardProperties props = new StorageGuardProperties();
            props.setEnabled(true);
            props.setStrictMagicCheck(true);
            props.setAllowedExtensions(Set.of("jpg", "jpeg", "png", "webp"));
            props.setMaxFileSize(DataSize.ofMegabytes(2));
            return new StorageGuard(props);
        }

        @Bean
        @SuppressWarnings("unchecked")
        StorageDriver edgeStorageDriver() {
            StorageDriver driver = Mockito.mock(StorageDriver.class);
            when(driver.upload(anyString(), any(InputStream.class), anyString(), anyLong()))
                    .thenAnswer(inv -> StorageObject.builder().path(inv.getArgument(0)).build());
            when(driver.upload(anyString(), any(InputStream.class), anyString(), anyLong(), nullable(Map.class)))
                    .thenAnswer(inv -> StorageObject.builder().path(inv.getArgument(0)).build());
            when(driver.upload(anyString(), any(InputStream.class), anyString(), anyLong(), nullable(Map.class), anyBoolean()))
                    .thenAnswer(inv -> StorageObject.builder().path(inv.getArgument(0)).build());
            when(driver.uploadIfAbsent(anyString(), any(InputStream.class), anyString(), anyLong(), nullable(Map.class), anyBoolean()))
                    .thenAnswer(inv -> StorageObject.builder().path(inv.getArgument(0)).build());
            when(driver.getPresignedUrl(any(PresignedDownloadRequest.class)))
                    .thenAnswer(inv -> {
                        PresignedDownloadRequest req = inv.getArgument(0);
                        return PresignedUrl.builder()
                                .url("https://mock-s3.local/" + req.getPath() + "?disposition=" + req.getResponseContentDisposition())
                                .build();
                    });
            when(driver.delete(anyString())).thenReturn(true);
            return driver;
        }

        @Bean
        StorageOperations edgeStorageOperations(StorageDriver driver, StorageGuard storageGuard) {
            StorageTemplate template = new StorageTemplate(driver);
            template.setStorageGuard(storageGuard);
            return template;
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private CaptchaStore captchaStore;
    @Autowired
    private CaptchaProperties captchaProperties;
    @Autowired
    private LoginFailureLockService loginFailureLockService;
    @Autowired
    private SysTenantMapper sysTenantMapper;
    @Autowired
    private SysUserMapper sysUserMapper;

    @BeforeAll
    void setUpFixtures() {
        loginFailureLockService.clearLocalMemory();

        SysTenant tenant = new SysTenant();
        tenant.setId(TENANT_ID);
        tenant.setTenantCode("T7001");
        tenant.setTenantName("边缘安全企业");
        tenant.setAccountCount(-1);
        tenant.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysTenantMapper.insert(tenant);

        TenantContextHolder.runWithTenant(TENANT_ID, () -> {
            insertUser(7101L, "alice_edge", "爱丽丝", "13900007101", "alice.edge@example.com");
            insertUser(7102L, "bob_edge", "鲍勃", "13900007102", "bob.edge@example.com");
        });
    }

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        DataPermissionContextHolder.clear();
        MDC.clear();
    }

    private void insertUser(Long id, String username, String nickname, String phone, String email) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setTenantId(TENANT_ID);
        user.setUsername(username);
        user.setNickname(nickname);
        user.setPassword(PasswordUtil.hash("Ateng@Edge1"));
        user.setPhone(phone);
        user.setEmail(email);
        user.setGender("1");
        user.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysUserMapper.insert(user);
    }

    private String issueKnownCaptcha(String code) {
        String key = "edge-cap-" + UUID.randomUUID();
        String prefix = captchaProperties.getKeyPrefix() != null ? captchaProperties.getKeyPrefix() : "captcha:";
        captchaStore.save(prefix + key, code, Duration.ofMinutes(2));
        return key;
    }

    @Test
    @DisplayName("应当生成真图形验证码、校验一次一密 101001 并支持不扣次数的 IP 维度 101004 防爆破熔断")
    void shouldVerifyOneTimeCaptchaAndEnforceIpDimensionLock() throws Exception {
        MvcResult capRes = mockMvc.perform(get("/admin/auth/captcha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.tenantEnabled").value(true))
                .andExpect(jsonPath("$.data.captchaKey").isNotEmpty())
                .andExpect(jsonPath("$.data.imageBase64").isNotEmpty())
                .andExpect(jsonPath("$.data.expireSeconds").isNumber())
                .andReturn();
        String realKey = objectMapper.readTree(capRes.getResponse().getContentAsString())
                .path("data").path("captchaKey").asText();

        // 1. 错验证码返 101001 且不计 IP 失败次数（连续 3 次 > ip-max-retry-count=2 也不可触发 IP 锁）
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/admin/auth/login")
                            .header("X-Forwarded-For", ATTACKER_IP)
                            .header(SolidConstants.TENANT_HEADER_NAME, String.valueOf(TENANT_ID))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", "alice_edge",
                                    "password", "Wrong@Pass",
                                    "captchaKey", realKey,
                                    "captchaCode", "ZZZZ"
                            ))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(101001));
        }
        assertThat(loginFailureLockService.isIpLocked(ATTACKER_IP)).isFalse();

        // 2. 第 1 次带正确验证码输错密码（ipRetry=1）-> 101002，且原验证码立即原子烧毁不能重放
        String oneTimeKey = issueKnownCaptcha("6688");
        mockMvc.perform(post("/admin/auth/login")
                        .header("X-Forwarded-For", ATTACKER_IP)
                        .header(SolidConstants.TENANT_HEADER_NAME, String.valueOf(TENANT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "alice_edge",
                                "password", "Wrong@Pass",
                                "captchaKey", oneTimeKey,
                                "captchaCode", "6688"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(101002));

        mockMvc.perform(post("/admin/auth/login")
                        .header("X-Forwarded-For", ATTACKER_IP)
                        .header(SolidConstants.TENANT_HEADER_NAME, String.valueOf(TENANT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "alice_edge",
                                "password", "Ateng@Edge1",
                                "captchaKey", oneTimeKey,
                                "captchaCode", "6688"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(101001));

        // 3. 同一 IP 更换用户名第 2 次输错密码（ipRetry=2 达阈值）-> 触发 IP 维度 101004
        String secondKey = issueKnownCaptcha("7799");
        MvcResult lockRes = mockMvc.perform(post("/admin/auth/login")
                        .header("X-Forwarded-For", ATTACKER_IP)
                        .header(SolidConstants.TENANT_HEADER_NAME, String.valueOf(TENANT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "bob_edge",
                                "password", "Wrong@Pass",
                                "captchaKey", secondKey,
                                "captchaCode", "7799"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(101004))
                .andExpect(jsonPath("$.data.lockType").value("IP"))
                .andExpect(jsonPath("$.data.remainSeconds").isNumber())
                .andReturn();
        assertThat(objectMapper.readTree(lockRes.getResponse().getContentAsString())
                .path("data").path("remainSeconds").asLong()).isGreaterThanOrEqualTo(1L);

        // 4. IP 锁定后即使用正确凭据与新验证码登录仍被前置拦截返回 IP 维度 101004
        String thirdKey = issueKnownCaptcha("8800");
        mockMvc.perform(post("/admin/auth/login")
                        .header("X-Forwarded-For", ATTACKER_IP)
                        .header(SolidConstants.TENANT_HEADER_NAME, String.valueOf(TENANT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "alice_edge",
                                "password", "Ateng@Edge1",
                                "captchaKey", thirdKey,
                                "captchaCode", "8800"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(101004))
                .andExpect(jsonPath("$.data.lockType").value("IP"));

        loginFailureLockService.unlockIp(ATTACKER_IP);
        loginFailureLockService.clearFailure(String.valueOf(TENANT_ID), "admin", "alice_edge");
        loginFailureLockService.clearFailure(String.valueOf(TENANT_ID), "admin", "bob_edge");
    }

    @Test
    @DisplayName("应当支持 X-Tenant-Id 头登录并校验个人中心手机/邮箱冲突 102002、参数必填与头像魔数 100004")
    void shouldLoginViaTenantHeaderAndValidateProfileConflictsAndAvatarUpload() throws Exception {
        String capKey = issueKnownCaptcha("4321");
        MvcResult loginRes = mockMvc.perform(post("/admin/auth/login")
                        .header(SolidConstants.TENANT_HEADER_NAME, String.valueOf(TENANT_ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "alice_edge",
                                "password", "Ateng@Edge1",
                                "captchaKey", capKey,
                                "captchaCode", "4321"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.tenantId").value(String.valueOf(TENANT_ID)))
                .andExpect(jsonPath("$.data.tenantName").value("边缘安全企业"))
                .andReturn();

        String aliceToken = objectMapper.readTree(loginRes.getResponse().getContentAsString())
                .path("data").path("tokenValue").asText();

        // 1. 缺少必填 gender 应当由 Bean Validation 拦截返回 400
        mockMvc.perform(put("/admin/auth/profile")
                        .header("satoken-admin", aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "nickname", "爱丽丝"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 2. 手机号冲突（占用 bob_edge 手机）与大小写邮箱冲突（占用 bob_edge 邮箱）均应返回 102002
        mockMvc.perform(put("/admin/auth/profile")
                        .header("satoken-admin", aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "nickname", "爱丽丝",
                                "phone", "13900007102",
                                "email", "alice.edge@example.com",
                                "gender", "1"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(102002));

        mockMvc.perform(put("/admin/auth/profile")
                        .header("satoken-admin", aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "nickname", "爱丽丝",
                                "phone", "13900007101",
                                "email", "  BOB.EDGE@EXAMPLE.COM ",
                                "gender", "1"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(102002));

        // 3. 合法修改资料与读回
        mockMvc.perform(put("/admin/auth/profile")
                        .header("satoken-admin", aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "nickname", "爱丽丝-已变更",
                                "phone", "",
                                "email", "ALICE.NEW@EXAMPLE.COM",
                                "gender", "0"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/admin/auth/profile")
                        .header("satoken-admin", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.nickname").value("爱丽丝-已变更"))
                .andExpect(jsonPath("$.data.phone").isEmpty())
                .andExpect(jsonPath("$.data.email").value("alice.new@example.com"))
                .andExpect(jsonPath("$.data.gender").value("0"));

        // 4. 头像上传：伪造 PNG 魔数拦截 100004；真实 PNG 成功上传返回 objectPath 与 inline URL
        MockMultipartFile fakePng = new MockMultipartFile(
                "file", "fake.png", "image/png", "not-real-png".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/admin/auth/profile/avatar")
                        .file(fakePng)
                        .header("satoken-admin", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(100004));

        MockMultipartFile validPng = new MockMultipartFile(
                "file", "avatar.png", "image/png", VALID_PNG_BYTES);
        mockMvc.perform(multipart("/admin/auth/profile/avatar")
                        .file(validPng)
                        .header("satoken-admin", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.objectPath").isString())
                .andExpect(jsonPath("$.data.avatarUrl").isString());
    }
}

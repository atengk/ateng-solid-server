package io.github.atengk.solid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.boot.satoken.cache.PermissionCacheService;
import io.github.atengk.boot.satoken.defense.LoginFailureLockService;
import io.github.atengk.boot.satoken.util.LoginHelper;
import io.github.atengk.boot.storage.driver.S3Driver;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.guard.StorageGuard;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.properties.StorageProperties;
import io.github.atengk.boot.storage.template.StorageOperations;
import io.github.atengk.boot.storage.template.StorageTemplate;
import io.github.atengk.boot.web.ratelimit.store.RateLimiterStore;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.command.UploadAvatarCommand;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.support.AvatarStorageHelper;
import io.github.atengk.solid.system.support.AvatarUrlResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 本地真实 S3/RustFS 对象存储环境下的文件守卫、2 小时 {@code inline} 预签名直链、
 * 事务回滚防孤儿补偿与个人中心换头像提交后旧图清理集成测试。
 *
 * @author Ateng
 * @since 2026-11-27
 */
@Tag("local")
@EnabledIfSystemProperty(named = "solid.local.test", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalStorageIntegrationTest extends LocalMiddlewareTestSupport {

    private static final byte[] VALID_PNG_BYTES = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    );

    private static final String TEST_USER_NAME = ("lt_st_" + TEST_RUN_ID)
            .substring(0, Math.min(28, ("lt_st_" + TEST_RUN_ID).length()));
    private static final String TEST_USER_PASS = "Ateng@LocalSt1";
    private static final String LOGIN_IP = "198.51.100.95";

    private final Set<String> createdObjectPaths = ConcurrentHashMap.newKeySet();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private StorageOperations storageOperations;
    @Autowired
    private StorageDriver storageDriver;
    @Autowired
    private StorageGuard storageGuard;
    @Autowired
    private StorageProperties storageProperties;
    @Autowired
    private AvatarStorageHelper avatarStorageHelper;
    @Autowired
    private AvatarUrlResolver avatarUrlResolver;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private LoginFailureLockService loginFailureLockService;
    @Autowired
    private PermissionCacheService permissionCacheService;
    @Autowired
    private RateLimiterStore rateLimiterStore;

    private Long testUserId;

    @BeforeAll
    void setUpIsolatedUser() {
        loginFailureLockService.clearLocalMemory();
        TenantContextHolder.runWithTenant(SolidConstants.PLATFORM_TENANT_ID, () -> {
            SysUser user = new SysUser();
            user.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
            user.setDeptId(SolidConstants.DEFAULT_ROOT_DEPT_ID);
            user.setUsername(TEST_USER_NAME);
            user.setNickname("本地存储验证员");
            user.setPassword(PasswordUtil.hash(TEST_USER_PASS));
            user.setGender("1");
            user.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysUserMapper.insert(user);
            testUserId = user.getId();
        });
    }

    @AfterAll
    void cleanUpIsolatedState() {
        for (String objectPath : createdObjectPaths) {
            try {
                storageOperations.delete(objectPath);
            } catch (Exception ignored) {
            }
        }

        if (testUserId != null) {
            try {
                String compositeLoginId = LoginHelper.buildCompositeLoginId(
                        String.valueOf(SolidConstants.PLATFORM_TENANT_ID),
                        String.valueOf(testUserId)
                );
                LoginHelper.logoutByLoginType(SolidConstants.ADMIN_LOGIN_TYPE, compositeLoginId);
            } catch (Exception ignored) {
            }
            try {
                permissionCacheService.clearUserCache(
                        SolidConstants.ADMIN_LOGIN_TYPE,
                        String.valueOf(SolidConstants.PLATFORM_TENANT_ID),
                        String.valueOf(testUserId)
                );
            } catch (Exception ignored) {
            }
        }

        try {
            rateLimiterStore.reset(TEST_RATELIMIT_KEY_PREFIX + "ip:" + LOGIN_IP + ":auth:login");
        } catch (Exception ignored) {
        }
        try {
            deleteIsolatedKeysByPattern(stringRedisTemplate, "*" + TEST_RATELIMIT_KEY_PREFIX + "*");
            deleteIsolatedKeysByPattern(stringRedisTemplate, "*" + TEST_KEY_PREFIX + "idempotent:audit:login:*");
        } catch (Exception ignored) {
        }

        try {
            Thread.sleep(500);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        try {
            jdbcTemplate.update("DELETE FROM sys_login_log WHERE username = ?", TEST_USER_NAME);
            jdbcTemplate.update("DELETE FROM sys_oper_log WHERE user_name = ?", TEST_USER_NAME);
        } catch (Exception ignored) {
        }
        if (testUserId != null) {
            try {
                jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", testUserId);
                jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", testUserId);
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    @DisplayName("dev+local 环境应当真实装配 S3Driver、StorageTemplate 与 StorageGuard 守卫基线")
    void shouldWireRealS3StorageDriverAndGuard() {
        assertThat(storageOperations).isInstanceOf(StorageTemplate.class);
        assertThat(storageDriver).isInstanceOf(S3Driver.class);
        assertThat(storageGuard).isNotNull();
        assertThat(storageGuard.isEnabled()).isTrue();

        assertThat(storageProperties.getType()).isEqualTo("s3");
        assertThat(storageProperties.getS3().isPathStyle()).isTrue();
        assertThat(storageProperties.getGuard().isStrictMagicCheck()).isTrue();
        assertThat(storageProperties.getGuard().getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(2));
    }

    @Test
    @DisplayName("应当完成真实 S3 上传下载、2 小时 inline 预签名直链实测与幂等删除")
    void shouldUploadDownloadPresignInlineAndDeleteRealS3Object() throws Exception {
        String probePath = "localtest/" + TEST_RUN_ID + "/probe.png";
        createdObjectPaths.add(probePath);

        storageOperations.upload(probePath, new ByteArrayInputStream(VALID_PNG_BYTES), "image/png", VALID_PNG_BYTES.length);
        assertThat(storageOperations.exists(probePath)).isTrue();
        assertThat(storageOperations.downloadBytes(probePath)).isEqualTo(VALID_PNG_BYTES);

        StorageObject metadata = storageOperations.getMetadata(probePath);
        assertThat(metadata).isNotNull();
        assertThat(metadata.getSize()).isEqualTo(VALID_PNG_BYTES.length);

        String presignedUrl = avatarUrlResolver.resolve(probePath);
        assertThat(presignedUrl)
                .isNotBlank()
                .satisfies(url -> assertThat(url.startsWith("http://") || url.startsWith("https://")).isTrue())
                .contains("probe.png")
                .containsIgnoringCase("inline");

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(presignedUrl))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode())
                .withFailMessage("预签名直链访问返回非 200 状态码: status=%d", response.statusCode())
                .isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Disposition"))
                .isPresent()
                .get()
                .asString()
                .containsIgnoringCase("inline");
        assertThat(response.body()).isEqualTo(VALID_PNG_BYTES);

        // 验证外链透传与空白安全兜底
        assertThat(avatarUrlResolver.resolve("https://example.com/a.png")).isEqualTo("https://example.com/a.png");
        assertThat(avatarUrlResolver.resolve("  ")).isNull();

        assertThat(storageOperations.delete(probePath)).isTrue();
        assertThat(storageOperations.exists(probePath)).isFalse();
        assertThat(storageOperations.delete(probePath)).isTrue();
        createdObjectPaths.remove(probePath);
    }

    @Test
    @DisplayName("数据库事务回滚时应当自动清理已上传到真实 S3 的头像对象（uploadWithRollbackDelete）")
    void shouldDeleteUploadedObjectAutomaticallyWhenTransactionRollsBack() {
        UploadAvatarCommand cmd = UploadAvatarCommand.builder()
                .originalFilename("rollback.png")
                .contentType("image/png")
                .content(VALID_PNG_BYTES)
                .build();
        AvatarStorageHelper.ValidatedAvatar validated = avatarStorageHelper.validateUpload(cmd);
        AtomicReference<String> uploadedPathRef = new AtomicReference<>();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            String uploadedPath = avatarStorageHelper.uploadWithRollbackCompensation(
                    storageOperations, SolidConstants.PLATFORM_TENANT_ID, testUserId, cmd, validated
            );
            uploadedPathRef.set(uploadedPath);
            createdObjectPaths.add(uploadedPath);
            assertThat(storageOperations.exists(uploadedPath)).isTrue();
            throw new IllegalStateException("simulated db failure");
        })).isInstanceOf(IllegalStateException.class);

        String rolledBackPath = uploadedPathRef.get();
        assertThat(rolledBackPath).isNotBlank();
        assertThat(storageOperations.exists(rolledBackPath)).isFalse();
        createdObjectPaths.remove(rolledBackPath);
    }

    @Test
    @DisplayName("端到端上传与更换头像应当拦截伪造魔数 100004、落库相对对象键并在提交后自动清理旧图")
    void shouldUploadReplaceAvatarEndToEndAndDeleteOldAvatarAfterCommit() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/admin/auth/login")
                        .header("X-Forwarded-For", LOGIN_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", TEST_USER_NAME,
                                "password", TEST_USER_PASS
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .path("data").path("tokenValue").asText();
        assertThat(token).isNotBlank();

        // 1. 伪造魔数与非法扩展名拦截 100004
        MockMultipartFile fakePng = new MockMultipartFile(
                "file", "fake.png", "image/png", "not-a-real-png".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/admin/auth/profile/avatar")
                        .file(fakePng)
                        .header("satoken-admin", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonBizResultCode.FILE_GUARD_REJECTED.getCode()));

        MockMultipartFile illegalSvg = new MockMultipartFile(
                "file", "evil.svg", "image/svg+xml", "<svg></svg>".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/admin/auth/profile/avatar")
                        .file(illegalSvg)
                        .header("satoken-admin", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonBizResultCode.FILE_GUARD_REJECTED.getCode()));

        // 2. 首次上传真实 PNG，校验返回 DTO、数据库相对对象键与个人资料动态预签名
        MvcResult firstUploadRes = mockMvc.perform(multipart("/admin/auth/profile/avatar")
                        .file(new MockMultipartFile("file", "avatar1.png", "image/png", VALID_PNG_BYTES))
                        .header("satoken-admin", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode firstData = objectMapper.readTree(firstUploadRes.getResponse().getContentAsString()).path("data");
        String objectPath1 = firstData.path("objectPath").asText();
        String avatarUrl1 = firstData.path("avatarUrl").asText();
        createdObjectPaths.add(objectPath1);

        assertThat(objectPath1).matches("avatar/0/" + testUserId + "/[a-f0-9]+\\.png");
        assertThat(avatarUrl1).satisfies(url -> assertThat(url.startsWith("http://") || url.startsWith("https://")).isTrue());
        assertThat(storageOperations.exists(objectPath1)).isTrue();

        String dbAvatar1 = jdbcTemplate.queryForObject(
                "SELECT avatar FROM sys_user WHERE id = ?", String.class, testUserId);
        assertThat(dbAvatar1).isEqualTo(objectPath1);

        String firstFilename = objectPath1.substring(objectPath1.lastIndexOf('/') + 1);
        MvcResult profileRes = mockMvc.perform(get("/admin/auth/profile")
                        .header("satoken-admin", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        String profileAvatarUrl = objectMapper.readTree(profileRes.getResponse().getContentAsString())
                .path("data").path("avatar").asText();
        assertThat(profileAvatarUrl)
                .satisfies(url -> assertThat(url.startsWith("http://") || url.startsWith("https://")).isTrue())
                .contains(firstFilename);

        // 3. 更换头像后校验数据库切换新键、新对象存在且旧对象在提交后自动删除
        MvcResult secondUploadRes = mockMvc.perform(multipart("/admin/auth/profile/avatar")
                        .file(new MockMultipartFile("file", "avatar2.png", "image/png", VALID_PNG_BYTES))
                        .header("satoken-admin", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        String objectPath2 = objectMapper.readTree(secondUploadRes.getResponse().getContentAsString())
                .path("data").path("objectPath").asText();
        createdObjectPaths.add(objectPath2);

        assertThat(objectPath2)
                .isNotEqualTo(objectPath1)
                .matches("avatar/0/" + testUserId + "/[a-f0-9]+\\.png");
        assertThat(storageOperations.exists(objectPath2)).isTrue();

        String dbAvatar2 = jdbcTemplate.queryForObject(
                "SELECT avatar FROM sys_user WHERE id = ?", String.class, testUserId);
        assertThat(dbAvatar2).isEqualTo(objectPath2);

        awaitUntil(Duration.ofSeconds(5),
                () -> !storageOperations.exists(objectPath1),
                "等待事务提交后清理旧头像超时");
        createdObjectPaths.remove(objectPath1);
    }
}

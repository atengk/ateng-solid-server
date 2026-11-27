package io.github.atengk.solid;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.satoken.defense.LoginFailureLockService;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全量入参 Bean Validation 补全（{@code @Valid} / {@code List<Long>} 集合与元素 / QueryRequest / 密码上限）、
 * {@code parsePathIds} 100 条上限与时间范围反序统一校验 MockMvc 测试。
 *
 * @author Ateng
 * @since 2026-11-27
 */
@SpringBootTest(
        classes = SolidApplication.class,
        properties = {
                "ateng.solid.init.enabled=true",
                "ateng.solid.tenant.enabled=false",
                "ateng.solid.auth.captcha-enabled=false",
                "spring.datasource.url=jdbc:h2:mem:validation_export_cap_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        }
)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ValidationAndExportCapMockMvcTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private LoginFailureLockService loginFailureLockService;

    @BeforeAll
    void cleanAuthLocks() {
        loginFailureLockService.clearLocalMemory();
    }

    @AfterEach
    void clearContexts() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        DataPermissionContextHolder.clear();
        MDC.clear();
        loginFailureLockService.clearLocalMemory();
    }

    @Test
    @DisplayName("DELETE /admin/system/user/{ids} 传入超过 100 个主键应当触发 100001 限流拦截")
    void shouldRejectOverOneHundredPathIdsWithParamInvalid() throws Exception {
        String token = Spec4TestFixtures.loginAsSuperAdmin(mockMvc, objectMapper);
        String overIds = IntStream.rangeClosed(1, 101)
                .mapToObj(String::valueOf)
                .collect(Collectors.joining(","));

        mockMvc.perform(delete("/admin/system/user/{ids}", overIds)
                        .header("satoken-admin", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonBizResultCode.PARAM_INVALID.getCode()));
    }

    @Test
    @DisplayName("缺失 @Valid 的导出/改密/枚举批量及树与分页 QueryRequest 非法入参应当统一被 Bean Validation 拦截")
    void shouldValidateResetPasswordExportRequestEnumBatchAndQueryRequests() throws Exception {
        String token = Spec4TestFixtures.loginAsSuperAdmin(mockMvc, objectMapper);

        // 1. resetPassword 超过 128 字符密码
        mockMvc.perform(put("/admin/system/user/1/reset-password")
                        .header("satoken-admin", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("password", "A".repeat(129)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.BAD_REQUEST.getCode()));

        // 2. exportUsers @Valid（status 越界、ids 含非正数）
        mockMvc.perform(post("/admin/system/user/export")
                        .header("satoken-admin", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", 9))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.BAD_REQUEST.getCode()));
        mockMvc.perform(post("/admin/system/user/export")
                        .header("satoken-admin", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("ids", List.of(-1)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.BAD_REQUEST.getCode()));

        // 3. enums batch POST & GET 超过 100 个名称
        List<String> tooManyEnums = new ArrayList<>(101);
        MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
        for (int i = 0; i < 101; i++) {
            String name = "enum" + i;
            tooManyEnums.add(name);
            queryParams.add("names", name);
        }
        mockMvc.perform(post("/admin/system/enums/batch")
                        .header("satoken-admin", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tooManyEnums)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.BAD_REQUEST.getCode()));
        mockMvc.perform(get("/admin/system/enums/batch")
                        .header("satoken-admin", token)
                        .params(queryParams))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.BAD_REQUEST.getCode()));

        // 4. user/page beginTime > endTime 抛 100001
        mockMvc.perform(get("/admin/system/user/page")
                        .header("satoken-admin", token)
                        .param("beginTime", "2026-12-01 00:00:00")
                        .param("endTime", "2026-11-01 00:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonBizResultCode.PARAM_INVALID.getCode()));

        // 5. 部门树 / 菜单树 / 参数分页 QueryRequest 校验
        mockMvc.perform(get("/admin/system/dept/tree").header("satoken-admin", token).param("status", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.BAD_REQUEST.getCode()));
        mockMvc.perform(get("/admin/system/menu/tree").header("satoken-admin", token).param("status", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.BAD_REQUEST.getCode()));
        mockMvc.perform(get("/admin/system/config/page").header("satoken-admin", token).param("configName", "k".repeat(101)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.BAD_REQUEST.getCode()));

        // 6. Role / User List<Long> 集合与元素正整数校验
        mockMvc.perform(post("/admin/system/role")
                        .header("satoken-admin", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "roleName", "非法菜单角色",
                                "roleKey", "bad_menu_role",
                                "sort", 1,
                                "menuIds", List.of(-5)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.BAD_REQUEST.getCode()));
        List<Long> overRoles = IntStream.rangeClosed(1, 101).mapToLong(Long::valueOf).boxed().toList();
        mockMvc.perform(post("/admin/system/user")
                        .header("satoken-admin", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "too_many_roles_user",
                                "nickname", "超长角色用户",
                                "roleIds", overRoles
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.BAD_REQUEST.getCode()));
    }
}

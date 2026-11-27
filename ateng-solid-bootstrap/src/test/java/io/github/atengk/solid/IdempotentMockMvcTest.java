package io.github.atengk.solid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.satoken.defense.LoginFailureLockService;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.enums.DataScopeType;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysRoleMenu;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.entity.SysUserRole;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysRoleMenuMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 核心新增与导入接口 {@code @Idempotent} 幂等防重、异常自动释放锁与多用户隔离 MockMvc 集成测试。
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
                "ateng.boot.web.idempotent.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:idempotent_mockmvc_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        }
)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdempotentMockMvcTest {

    private static final long DEPT_ID = 6600L;
    private static final long ROLE_ID = 6601L;
    private static final long USER_A_ID = 6611L;
    private static final long USER_B_ID = 6612L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private LoginFailureLockService loginFailureLockService;
    @Autowired
    private SysDeptMapper sysDeptMapper;
    @Autowired
    private SysRoleMapper sysRoleMapper;
    @Autowired
    private SysRoleMenuMapper sysRoleMenuMapper;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @BeforeAll
    void setUpFixtures() {
        loginFailureLockService.clearLocalMemory();
        TenantContextHolder.runWithTenant(SolidConstants.PLATFORM_TENANT_ID, () -> {
            SysDept dept = new SysDept();
            dept.setId(DEPT_ID);
            dept.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
            dept.setParentId(SolidConstants.DEFAULT_ROOT_DEPT_ID);
            dept.setAncestors("0,100");
            dept.setDeptName("幂等测试部");
            dept.setSort(1);
            dept.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysDeptMapper.insert(dept);

            SysRole role = new SysRole();
            role.setId(ROLE_ID);
            role.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
            role.setRoleName("幂等导入测试员");
            role.setRoleKey("idemp_operator");
            role.setDataScope(DataScopeType.ALL.getCode());
            role.setMenuCheckStrictly(true);
            role.setDeptCheckStrictly(false);
            role.setSort(1);
            role.setStatus(CommonStatusEnum.NORMAL.getCode());
            sysRoleMapper.insert(role);

            for (Long menuId : List.of(1000L, 1100L, 1101L, 1105L)) {
                SysRoleMenu rm = new SysRoleMenu();
                rm.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
                rm.setRoleId(ROLE_ID);
                rm.setMenuId(menuId);
                sysRoleMenuMapper.insert(rm);
            }

            createUser(USER_A_ID, "idemp_a", "Ateng@Idemp1");
            createUser(USER_B_ID, "idemp_b", "Ateng@Idemp2");
        });
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
    @DisplayName("重复 POST 新增部门应当被 @Idempotent 拦截返回 40003，异常触发后应当立即释放锁允许重试")
    void shouldBlockDuplicateDeptCreateWithRepeatSubmitAndReleaseLockOnException() throws Exception {
        String superToken = Spec4TestFixtures.loginAsSuperAdmin(mockMvc, objectMapper);
        String body = objectMapper.writeValueAsString(Map.of(
                "parentId", 100,
                "deptName", "幂等并发新建部",
                "sort", 1,
                "status", 0
        ));

        mockMvc.perform(post("/admin/system/dept")
                        .header("satoken-admin", superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/admin/system/dept")
                        .header("satoken-admin", superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.REPEAT_SUBMIT.getCode()));

        // 验证 delKeyOnException=true：业务异常抛出后锁立刻释放，同请求重试仍能进入业务逻辑返回 102001 而非 40003
        String invalidParentBody = objectMapper.writeValueAsString(Map.of(
                "parentId", 9999999L,
                "deptName", "不存在父级幂等部",
                "sort", 2,
                "status", 0
        ));
        mockMvc.perform(post("/admin/system/dept")
                        .header("satoken-admin", superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidParentBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(SystemResultCode.RECORD_NOT_FOUND.getCode()));
        mockMvc.perform(post("/admin/system/dept")
                        .header("satoken-admin", superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidParentBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(SystemResultCode.RECORD_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("批量导入接口 @Idempotent 应当按用户与文件指纹隔离防重，不同文件或不同用户均不被误拦")
    void shouldIdempotentlyProtectImportUsersPerUserAndFileFingerprint() throws Exception {
        String tokenA = login("idemp_a", "Ateng@Idemp1");
        String tokenB = login("idemp_b", "Ateng@Idemp2");
        byte[] xlsxBytes = buildValidImportWorkbookBytes("idemp_row_1", "幂等导入行一");
        byte[] otherXlsxBytes = buildValidImportWorkbookBytes("idemp_row_2", "幂等导入行二");

        mockMvc.perform(multipart("/admin/system/user/import")
                        .file(new MockMultipartFile(
                                "file", "dup_import.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsxBytes))
                        .header("satoken-admin", tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(multipart("/admin/system/user/import")
                        .file(new MockMultipartFile(
                                "file", "dup_import.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsxBytes))
                        .header("satoken-admin", tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.REPEAT_SUBMIT.getCode()));

        mockMvc.perform(multipart("/admin/system/user/import")
                        .file(new MockMultipartFile(
                                "file", "dup_import.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsxBytes))
                        .header("satoken-admin", tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(multipart("/admin/system/user/import")
                        .file(new MockMultipartFile(
                                "file", "other_import.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", otherXlsxBytes))
                        .header("satoken-admin", tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("导入前置校验异常应当释放幂等锁，同文件名不同内容不应误拦截，且新增岗位受 @Idempotent 防重保护")
    void shouldReleaseImportLockOnValidationExceptionDifferentiateSameFilenameContentAndProtectPostCreate() throws Exception {
        String tokenA = login("idemp_a", "Ateng@Idemp1");
        byte[] csvPayload = "username,nickname\ncsv_u1,CSV用户\n".getBytes(StandardCharsets.UTF_8);

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(multipart("/admin/system/user/import")
                            .file(new MockMultipartFile("file", "bad_format.csv", "text/csv", csvPayload))
                            .header("satoken-admin", tokenA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(CommonBizResultCode.EXCEL_VALIDATION_FAILED.getCode()));
        }

        byte[] contentV1 = buildValidImportWorkbookBytes("same_file_u1", "同文件名用户一");
        byte[] contentV2 = buildValidImportWorkbookBytes("same_file_u2", "同文件名用户二");

        mockMvc.perform(multipart("/admin/system/user/import")
                        .file(new MockMultipartFile(
                                "file", "same_name.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", contentV1))
                        .header("satoken-admin", tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(multipart("/admin/system/user/import")
                        .file(new MockMultipartFile(
                                "file", "same_name.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", contentV2))
                        .header("satoken-admin", tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        String superToken = Spec4TestFixtures.loginAsSuperAdmin(mockMvc, objectMapper);
        String postBody = objectMapper.writeValueAsString(Map.of(
                "postCode", "idemp_post_68",
                "postName", "幂等验证岗位",
                "sort", 99,
                "status", CommonStatusEnum.NORMAL.getCode()
        ));

        mockMvc.perform(post("/admin/system/post")
                        .header("satoken-admin", superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/admin/system/post")
                        .header("satoken-admin", superToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.REPEAT_SUBMIT.getCode()));
    }

    private void createUser(Long id, String username, String password) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
        user.setDeptId(DEPT_ID);
        user.setUsername(username);
        user.setNickname(username);
        user.setPassword(PasswordUtil.hash(password));
        user.setGender("1");
        user.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysUserMapper.insert(user);

        SysUserRole ur = new SysUserRole();
        ur.setTenantId(SolidConstants.PLATFORM_TENANT_ID);
        ur.setUserId(id);
        ur.setRoleId(ROLE_ID);
        sysUserRoleMapper.insert(ur);
    }

    private String login(String username, String password) throws Exception {
        MvcResult res = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode root = objectMapper.readTree(res.getResponse().getContentAsString());
        return root.path("data").path("tokenValue").asText();
    }

    private static byte[] buildValidImportWorkbookBytes(String username, String nickname) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("用户导入");
            Row header = sheet.createRow(0);
            String[] cols = {"用户账号", "用户昵称", "归属部门", "岗位编码", "角色编码", "手机号码", "电子邮箱", "性别", "状态"};
            for (int i = 0; i < cols.length; i++) {
                header.createCell(i).setCellValue(cols[i]);
            }
            Row dataRow = sheet.createRow(1);
            dataRow.createCell(0).setCellValue(username);
            dataRow.createCell(1).setCellValue(nickname);
            dataRow.createCell(2).setCellValue("幂等测试部");
            dataRow.createCell(8).setCellValue("正常");
            wb.write(out);
            return out.toByteArray();
        }
    }
}

package io.github.atengk.solid;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.satoken.defense.LoginFailureLockService;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.enums.DataScopeType;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysPost;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysRoleMenu;
import io.github.atengk.solid.system.entity.SysTenant;
import io.github.atengk.solid.system.entity.SysTenantPackage;
import io.github.atengk.solid.system.entity.SysTenantPackageMenu;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.entity.SysUserPost;
import io.github.atengk.solid.system.entity.SysUserRole;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysPostMapper;
import io.github.atengk.solid.system.mapper.SysRoleMapper;
import io.github.atengk.solid.system.mapper.SysRoleMenuMapper;
import io.github.atengk.solid.system.mapper.SysTenantMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMapper;
import io.github.atengk.solid.system.mapper.SysTenantPackageMenuMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserPostMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import io.github.atengk.solid.system.service.UserBatchPersistHelper;
import io.github.atengk.solid.system.service.UserBatchPersistHelper.PreparedImportUser;
import io.github.atengk.solid.system.service.UserImportValidator.ValidatedImportRow;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户管理 9 列 Excel 模板下载、500 行限制、整批原子导入（批注暂存与 403/404 隔离）、
 * 并行独立随机盐哈希、429 信号量限流与 {@code @DataScope} 流式脱敏导出集成测试。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@SpringBootTest(
        classes = SolidApplication.class,
        properties = {
                "ateng.solid.init.enabled=true",
                "ateng.solid.tenant.enabled=true",
                "ateng.boot.mybatis-plus.tenant.enabled=true",
                "ateng.solid.auth.captcha-enabled=false",
                "ateng.solid.user.max-concurrent-imports=1",
                "spring.datasource.url=jdbc:h2:mem:sys_user_excel_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        }
)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SysUserExcelImportIntegrationTest {

    private static final long PACKAGE_ID = 32001L;
    private static final long TENANT_ID = 32002L;
    private static final long QUOTA_TENANT_ID = 32003L;

    private static final long DEPT_HQ = 32100L;
    private static final long DEPT_EAST = 32101L;
    private static final long DEPT_SOUTH = 32102L;
    private static final long DEPT_EAST_RD = 32103L;
    private static final long DEPT_SOUTH_RD = 32104L;
    private static final long DEPT_DISABLED = 32105L;

    private static final long POST_DEV = 32201L;
    private static final long POST_QA = 32202L;
    private static final long POST_DISABLED = 32203L;

    private static final long ROLE_ADMIN = 32301L;
    private static final long ROLE_IMP_ONE = 32302L;
    private static final long ROLE_IMP_TWO = 32303L;
    private static final long ROLE_DISABLED = 32304L;
    private static final long ROLE_EAST_RD_SCOPE = 32305L;

    private static final long USER_ADMIN_A = 32401L;
    private static final long USER_ADMIN_B = 32402L;
    private static final long USER_EAST_EXP = 32403L;
    private static final long USER_QUOTA_SEED = 32901L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private LoginFailureLockService loginFailureLockService;
    @Autowired
    private SysTenantPackageMapper sysTenantPackageMapper;
    @Autowired
    private SysTenantPackageMenuMapper sysTenantPackageMenuMapper;
    @Autowired
    private SysTenantMapper sysTenantMapper;
    @Autowired
    private SysDeptMapper sysDeptMapper;
    @Autowired
    private SysPostMapper sysPostMapper;
    @Autowired
    private SysRoleMapper sysRoleMapper;
    @Autowired
    private SysRoleMenuMapper sysRoleMenuMapper;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private SysUserPostMapper sysUserPostMapper;
    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;
    @Autowired
    private UserBatchPersistHelper userBatchPersistHelper;

    @BeforeAll
    void setUpFixtures() {
        loginFailureLockService.clearLocalMemory();

        SysTenantPackage pkg = new SysTenantPackage();
        pkg.setId(PACKAGE_ID);
        pkg.setPackageName("Excel导入导出测试套餐");
        pkg.setMenuCheckStrictly(true);
        pkg.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysTenantPackageMapper.insert(pkg);

        for (Long mid : List.of(1000L, 1100L, 1101L, 1105L, 1106L)) {
            SysTenantPackageMenu pm = new SysTenantPackageMenu();
            pm.setPackageId(PACKAGE_ID);
            pm.setMenuId(mid);
            sysTenantPackageMenuMapper.insert(pm);
        }

        insertTenant(TENANT_ID, "T32002", "Excel全功能测试租户", PACKAGE_ID, -1);
        insertTenant(QUOTA_TENANT_ID, "T32003", "Excel配额拦截租户", PACKAGE_ID, 2);

        TenantContextHolder.runWithTenant(TENANT_ID, () -> {
            insertDept(TENANT_ID, DEPT_HQ, 0L, "0", "集团总部", CommonStatusEnum.NORMAL);
            insertDept(TENANT_ID, DEPT_EAST, DEPT_HQ, "0," + DEPT_HQ, "华东区", CommonStatusEnum.NORMAL);
            insertDept(TENANT_ID, DEPT_SOUTH, DEPT_HQ, "0," + DEPT_HQ, "华南区", CommonStatusEnum.NORMAL);
            insertDept(TENANT_ID, DEPT_EAST_RD, DEPT_EAST, "0," + DEPT_HQ + "," + DEPT_EAST, "研发组", CommonStatusEnum.NORMAL);
            insertDept(TENANT_ID, DEPT_SOUTH_RD, DEPT_SOUTH, "0," + DEPT_HQ + "," + DEPT_SOUTH, "研发组", CommonStatusEnum.NORMAL);
            insertDept(TENANT_ID, DEPT_DISABLED, DEPT_HQ, "0," + DEPT_HQ, "已撤销部门", CommonStatusEnum.DISABLED);

            insertPost(TENANT_ID, POST_DEV, "dev", "研发工程师", CommonStatusEnum.NORMAL);
            insertPost(TENANT_ID, POST_QA, "qa", "测试工程师", CommonStatusEnum.NORMAL);
            insertPost(TENANT_ID, POST_DISABLED, "old_qa", "历史质检", CommonStatusEnum.DISABLED);

            List<Long> importExportMenus = List.of(1000L, 1100L, 1101L, 1105L, 1106L);
            insertRole(TENANT_ID, ROLE_ADMIN, "用户导入管理员", "imp_admin", DataScopeType.ALL,
                    CommonStatusEnum.NORMAL, importExportMenus);
            insertRole(TENANT_ID, ROLE_IMP_ONE, "业务专员", "biz_staff", DataScopeType.SELF,
                    CommonStatusEnum.NORMAL, List.of(1000L, 1100L, 1101L));
            insertRole(TENANT_ID, ROLE_IMP_TWO, "数据分析员", "data_analyst", DataScopeType.SELF,
                    CommonStatusEnum.NORMAL, List.of(1000L, 1100L, 1101L));
            insertRole(TENANT_ID, ROLE_DISABLED, "已停用角色", "old_role", DataScopeType.SELF,
                    CommonStatusEnum.DISABLED, List.of());
            insertRole(TENANT_ID, ROLE_EAST_RD_SCOPE, "华东研发观察员", "east_rd_mgr", DataScopeType.DEPT,
                    CommonStatusEnum.NORMAL, List.of(1000L, 1100L, 1101L, 1106L));

            insertUser(TENANT_ID, USER_ADMIN_A, DEPT_HQ, "imp_admin_a", "导入员甲",
                    "Ateng@Imp1", "13832401001", "existing.user@example.com", ROLE_ADMIN);
            insertUser(TENANT_ID, USER_ADMIN_B, DEPT_HQ, "imp_admin_b", "导入员乙",
                    "Ateng@Imp2", "13832401002", "admin.b@example.com", ROLE_ADMIN);
            insertUser(TENANT_ID, USER_EAST_EXP, DEPT_EAST_RD, "east_rd_lead", "华东研发主管",
                    "Ateng@Imp3", "13832401003", "east.lead@example.com", ROLE_EAST_RD_SCOPE);
        });

        TenantContextHolder.runWithTenant(QUOTA_TENANT_ID, () ->
                insertUser(QUOTA_TENANT_ID, USER_QUOTA_SEED, null, "quota_only_one", "唯一配额用户",
                        "Ateng@Quota1", null, null, null)
        );
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
    @DisplayName("应当下载 9 列带批注与下拉约束的标准模板，并在非 .xlsx / 缺表头 / 超 500 行时抛出 100005")
    void shouldDownloadNineColumnTemplateAndRejectInvalidFileHeaderOrRowOverflow() throws Exception {
        String tokenA = loginAndGetToken("T32002", "imp_admin_a", "Ateng@Imp1");

        MvcResult tplRes = mockMvc.perform(get("/admin/system/user/import/template")
                        .header("satoken-admin", tokenA))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(tplRes.getResponse().getHeader("Content-Disposition")).contains(".xlsx");

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(tplRes.getResponse().getContentAsByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            Row header = sheet.getRow(0);
            List<String> headers = new ArrayList<>();
            for (int c = 0; c < 9; c++) {
                headers.add(header.getCell(c).getStringCellValue());
            }
            assertThat(headers).containsExactly(
                    "用户账号", "用户昵称", "归属部门", "岗位编码", "角色编码", "手机号码", "电子邮箱", "性别", "状态"
            );
            assertThat(header.getCell(0).getCellComment()).isNotNull();
            List<? extends DataValidation> validations = sheet.getDataValidations();
            assertThat(validations).hasSizeGreaterThanOrEqualTo(2);
        }

        // 1. 非 .xlsx 扩展名抛出 100005
        MockMultipartFile csvFile = new MockMultipartFile(
                "file", "users.csv", "text/csv", "a,b\n".getBytes(StandardCharsets.UTF_8)
        );
        mockMvc.perform(multipart("/admin/system/user/import")
                        .file(csvFile)
                        .header("satoken-admin", tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonBizResultCode.EXCEL_VALIDATION_FAILED.getCode()));

        // 2. 缺失必填表头抛出 100005
        byte[] missingHeaderBytes = buildWorkbook(
                List.of("用户账号", "归属部门"),
                List.of(List.of("header_miss_1", "华东区"))
        );
        mockMvc.perform(multipart("/admin/system/user/import")
                        .file(xlsxMultipart("missing_header.xlsx", missingHeaderBytes))
                        .header("satoken-admin", tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonBizResultCode.EXCEL_VALIDATION_FAILED.getCode()));

        // 3. 超过 500 行（501 行）Fail-Fast 抛出 100005 且 0 条入库
        List<List<String>> rows501 = new ArrayList<>(501);
        for (int i = 1; i <= 501; i++) {
            rows501.add(List.of("overflow_" + i, "超限用户" + i, "", "", "", "", "", "", ""));
        }
        byte[] overLimitBytes = buildStandardWorkbook(rows501);
        mockMvc.perform(multipart("/admin/system/user/import")
                        .file(xlsxMultipart("over_500.xlsx", overLimitBytes))
                        .header("satoken-admin", tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonBizResultCode.EXCEL_VALIDATION_FAILED.getCode()));

        // 底座 MultipartException 细分拦截（ateng-boot#1178）：非 multipart 请求与缺失分片均映射为 400 而非 500
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/system/user/import")
                        .header("satoken-admin", tokenA)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"file\":\"not-multipart\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("文件上传请求格式不合法或内容解析失败"));

        mockMvc.perform(multipart("/admin/system/user/import")
                        .header("satoken-admin", tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("混合错误整批原子回滚、精准 1-based 行号、无 rowData、标红批注下载与 403/404 隔离及配额校验")
    void shouldRollbackWholeBatchRenderFeedbackAndEnforceOwnershipIsolation() throws Exception {
        String tokenA = loginAndGetToken("T32002", "imp_admin_a", "Ateng@Imp1");
        String tokenB = loginAndGetToken("T32002", "imp_admin_b", "Ateng@Imp2");
        String superToken = loginAndGetToken("000000", "superadmin", "Ateng@Test2026");

        List<List<String>> mixedRows = List.of(
                // row 2: 完全合法行（检验整批原子回滚时不会入库）
                List.of("atomic_ok_row", "合法陪跑行", "华东区", "dev", "biz_staff", "13700001111", "ok.row@example.com", "男", "正常"),
                // row 3: JSR-303 账号不合法（验证 totalCount 并集不丢 JSR-303 行）
                List.of("x", "账号过短", "华东区", "", "", "", "", "", ""),
                // row 4: 库内账号、加密手机与大写邮箱均冲突
                List.of("imp_admin_a", "库内冲突", "华东区", "", "", "13832401001", "EXISTING.USER@example.com", "", ""),
                // row 5: 同名部门歧义提示改用 / 全路径
                List.of("ambig_dept_u", "部门歧义", "研发组", "", "", "13700002222", "ambig@example.com", "", ""),
                // row 6: 停用岗位 + 禁填 super_admin 越权
                List.of("sa_illegal_u", "越权角色", "华东区/研发组", "old_qa", "super_admin", "13700003333", "sa@example.com", "男", "正常"),
                // row 7: 批内与第 2 行手机号重复
                List.of("dup_phone_u", "批内手机重", "华东区", "", "", "13700001111", "dup@example.com", "女", "正常")
        );
        byte[] mixedBytes = buildStandardWorkbook(mixedRows);

        MvcResult importRes = mockMvc.perform(multipart("/admin/system/user/import")
                        .file(xlsxMultipart("mixed_errors.xlsx", mixedBytes))
                        .header("satoken-admin", tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalCount").value(6))
                .andExpect(jsonPath("$.data.successCount").value(0))
                .andReturn();

        String rawJson = importRes.getResponse().getContentAsString();
        assertThat(rawJson).doesNotContain("rowData");

        JsonNode data = objectMapper.readTree(rawJson).path("data");
        assertThat(data.path("failCount").asInt()).isGreaterThanOrEqualTo(7);
        String feedbackToken = data.path("feedbackToken").asText();
        assertThat(feedbackToken).hasSize(32);

        List<Integer> errorRowNums = new ArrayList<>();
        for (JsonNode err : data.path("errors")) {
            errorRowNums.add(err.path("rowNum").asInt());
        }
        assertThat(errorRowNums).contains(3, 4, 5, 6, 7);

        // 确认第 2 行合法用户并未入库（整批 0 入库）
        Long okUserCount = TenantContextHolder.runWithTenant(TENANT_ID, () -> sysUserMapper.selectCount(
                Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getTenantId, TENANT_ID)
                        .eq(SysUser::getUsername, "atomic_ok_row")
        ));
        assertThat(okUserCount).isZero();

        // 操作人甲正常下载标红批注工作簿，且第 3~7 行单元格包含批注
        MvcResult fbRes = mockMvc.perform(get("/admin/system/user/import/feedback/{token}", feedbackToken)
                        .header("satoken-admin", tokenA))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(fbRes.getResponse().getHeader("Content-Disposition")).contains(".xlsx");
        try (Workbook fbWb = WorkbookFactory.create(new ByteArrayInputStream(fbRes.getResponse().getContentAsByteArray()))) {
            Sheet sheet = fbWb.getSheetAt(0);
            assertThat(sheet.getRow(2).getCell(0).getCellComment()).isNotNull();
            assertThat(sheet.getRow(4).getCell(2).getCellComment()).isNotNull();
            assertThat(sheet.getRow(5).getCell(4).getCellComment()).isNotNull();
        }

        // 同租户其他用户乙与跨租户超管下载该 token 返回 403，未知 token 返回 404
        mockMvc.perform(get("/admin/system/user/import/feedback/{token}", feedbackToken)
                        .header("satoken-admin", tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.FORBIDDEN.getCode()));

        mockMvc.perform(get("/admin/system/user/import/feedback/{token}", feedbackToken)
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(TENANT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.FORBIDDEN.getCode()));

        mockMvc.perform(get("/admin/system/user/import/feedback/{token}", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                        .header("satoken-admin", tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(CommonResultCode.NOT_FOUND.getCode()));

        // 配额不足（QUOTA_TENANT_ID account_count=2，已用 1，尝试导入 2 行）整批拒绝并返回 feedbackToken
        byte[] quotaOverBytes = buildStandardWorkbook(List.of(
                List.of("q_u1", "配额一", "", "", "", "", "", "", ""),
                List.of("q_u2", "配额二", "", "", "", "", "", "", "")
        ));
        mockMvc.perform(multipart("/admin/system/user/import")
                        .file(xlsxMultipart("quota_over.xlsx", quotaOverBytes))
                        .header("satoken-admin", superToken)
                        .header("X-Tenant-Id", String.valueOf(QUOTA_TENANT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.successCount").value(0))
                .andExpect(jsonPath("$.data.failCount").value(1))
                .andExpect(jsonPath("$.data.feedbackToken").isNotEmpty());
    }

    @Test
    @DisplayName("全合法批量导入应当并行生成独立随机盐哈希、置空 pwd_update_time，且支持 @DataScope 脱敏流式导出")
    void shouldImportValidBatchWithDistinctSaltsAndExportWithDataScopeAndMasking() throws Exception {
        String tokenA = loginAndGetToken("T32002", "imp_admin_a", "Ateng@Imp1");

        List<List<String>> validRows = List.of(
                List.of("imp_user_1", "导入员工一", "集团总部/华东区/研发组", "dev,qa", "biz_staff,data_analyst",
                        "13632001001", "Imp.One@Example.COM", "男", "正常"),
                List.of("imp_user_2", "导入员工二", "华南区/研发组", "dev", "biz_staff",
                        "13632001002", "imp.two@example.com", "女", "停用"),
                List.of("imp_user_3", "导入员工三", "华东区", "", "", "", "", "", "")
        );
        byte[] validBytes = buildStandardWorkbook(validRows);

        mockMvc.perform(multipart("/admin/system/user/import")
                        .file(xlsxMultipart("valid_batch.xlsx", validBytes))
                        .header("satoken-admin", tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andExpect(jsonPath("$.data.successCount").value(3))
                .andExpect(jsonPath("$.data.failCount").value(0))
                .andExpect(jsonPath("$.data.errors").isEmpty())
                .andExpect(jsonPath("$.data.feedbackToken").isEmpty());

        List<SysUser> imported = TenantContextHolder.runWithTenant(TENANT_ID, () -> sysUserMapper.selectList(
                Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getTenantId, TENANT_ID)
                        .in(SysUser::getUsername, List.of("imp_user_1", "imp_user_2", "imp_user_3"))
                        .orderByAsc(SysUser::getUsername)
        ));
        assertThat(imported).hasSize(3);

        // 每位用户独立随机盐且均可匹配默认初始口令 Ateng@2026，且 pwd_update_time 为 null
        Set<String> hashes = new HashSet<>();
        for (SysUser u : imported) {
            assertThat(u.getPwdUpdateTime()).isNull();
            assertThat(PasswordUtil.matches("Ateng@2026", u.getPassword())).isTrue();
            hashes.add(u.getPassword());
        }
        assertThat(hashes).hasSize(3);

        SysUser u1 = imported.get(0);
        assertThat(u1.getDeptId()).isEqualTo(DEPT_EAST_RD);
        assertThat(u1.getEmail()).isEqualTo("imp.one@example.com");
        assertThat(u1.getGender()).isEqualTo("0");
        assertThat(u1.getStatus()).isEqualTo(0);

        SysUser u2 = imported.get(1);
        assertThat(u2.getDeptId()).isEqualTo(DEPT_SOUTH_RD);
        assertThat(u2.getStatus()).isEqualTo(1);

        TenantContextHolder.runWithTenant(TENANT_ID, () -> {
            assertThat(sysUserPostMapper.selectPostIdsByUserId(TENANT_ID, u1.getId()))
                    .containsExactlyInAnyOrder(POST_DEV, POST_QA);
            assertThat(sysUserRoleMapper.selectRoleIdsByUserId(TENANT_ID, u1.getId()))
                    .containsExactlyInAnyOrder(ROLE_IMP_ONE, ROLE_IMP_TWO);
        });

        // 验证仅具备 DEPT(DEPT_EAST_RD) 数据权限的 east_rd_lead 导出仅包含华东研发组用户且敏感列已脱敏、无密码列
        String eastLeadToken = loginAndGetToken("T32002", "east_rd_lead", "Ateng@Imp3");
        MvcResult exportRes = mockMvc.perform(post("/admin/system/user/export")
                        .header("satoken-admin", eastLeadToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(exportRes.getResponse().getHeader("Content-Disposition")).contains(".xlsx");
        try (Workbook exportWb = WorkbookFactory.create(new ByteArrayInputStream(exportRes.getResponse().getContentAsByteArray()))) {
            Sheet sheet = exportWb.getSheetAt(0);
            Row header = sheet.getRow(0);
            List<String> exportHeaders = new ArrayList<>();
            for (int c = 0; c < header.getLastCellNum(); c++) {
                exportHeaders.add(header.getCell(c).getStringCellValue());
            }
            assertThat(exportHeaders).containsExactly(
                    "用户ID", "用户账号", "用户昵称", "归属部门", "手机号码", "电子邮箱", "性别", "状态", "创建时间"
            );
            assertThat(exportHeaders).doesNotContain("密码", "password");

            List<String> exportedUsernames = new ArrayList<>();
            List<String> exportedPhones = new ArrayList<>();
            List<String> exportedEmails = new ArrayList<>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                exportedUsernames.add(row.getCell(1).getStringCellValue());
                exportedPhones.add(row.getCell(4).getStringCellValue());
                exportedEmails.add(row.getCell(5).getStringCellValue());
            }
            // east_rd_lead 只能看到同属 DEPT_EAST_RD 的 east_rd_lead 与 imp_user_1，看不到 imp_user_2（华南）或 imp_user_3（华东区）
            assertThat(exportedUsernames).containsExactlyInAnyOrder("east_rd_lead", "imp_user_1");
            assertThat(exportedPhones).noneMatch(p -> p.contains("13632001001") || p.contains("13832401003") || p.startsWith("v1:"));
            assertThat(exportedPhones).anyMatch(p -> p.startsWith("136****") || p.startsWith("138****"));
            assertThat(exportedEmails).noneMatch(e -> e.equals("imp.one@example.com") || e.startsWith("v1:"));
        }
    }

    @Test
    @DisplayName("并发导入超过 maxConcurrentImports 且等待超过 3s 时应当触发 429 TOO_MANY_REQUESTS")
    void shouldRejectExcessiveConcurrentImportsWithTooManyRequests() throws Exception {
        String tokenA = loginAndGetToken("T32002", "imp_admin_a", "Ateng@Imp1");
        String tokenB = loginAndGetToken("T32002", "imp_admin_b", "Ateng@Imp2");

        // 构造 120 行合法用户（6 线程并行 PBKDF2 耗时 ~7s），使第 2 个并发请求在 tryAcquire(3s) 必然触发 429
        List<List<String>> heavyRows = new ArrayList<>(120);
        for (int i = 1; i <= 120; i++) {
            heavyRows.add(List.of("conc_heavy_" + i, "并发压力" + i, "华东区", "", "", "", "", "", ""));
        }
        byte[] heavyBytes = buildStandardWorkbook(heavyRows);
        byte[] lightBytes = buildStandardWorkbook(List.of(
                List.of("conc_blocked_1", "排队受阻", "华东区", "", "", "", "", "", "")
        ));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch firstStarted = new CountDownLatch(1);
        try {
            Callable<Integer> firstTask = () -> {
                firstStarted.countDown();
                MvcResult res = mockMvc.perform(multipart("/admin/system/user/import")
                                .file(xlsxMultipart("heavy.xlsx", heavyBytes))
                                .header("satoken-admin", tokenA))
                        .andExpect(status().isOk())
                        .andReturn();
                return objectMapper.readTree(res.getResponse().getContentAsString()).path("code").asInt();
            };

            Future<Integer> future1 = pool.submit(firstTask);
            firstStarted.await(5, TimeUnit.SECONDS);
            // 略微错开 250ms 确保请求 1 已经通过阶段 1 校验并持有唯一信号量许可
            Thread.sleep(250);

            Future<Integer> future2 = pool.submit(() -> {
                MvcResult res = mockMvc.perform(multipart("/admin/system/user/import")
                                .file(xlsxMultipart("blocked.xlsx", lightBytes))
                                .header("satoken-admin", tokenB))
                        .andExpect(status().isOk())
                        .andReturn();
                return objectMapper.readTree(res.getResponse().getContentAsString()).path("code").asInt();
            });

            assertThat(future1.get(60, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(future2.get(60, TimeUnit.SECONDS)).isEqualTo(CommonResultCode.TOO_MANY_REQUESTS.getCode());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("非 ALL 角色导入受 @DataScope 部门约束，且阶段 3 落库事务会拦截期间被停用的部门/岗位/角色")
    void shouldEnforceDataScopeOnImportAndRejectDisabledReferencesInPersistStage() throws Exception {
        long scopedRoleId = 32391L;
        long scopedUserId = 32491L;
        TenantContextHolder.runWithTenant(TENANT_ID, () -> {
            insertRole(TENANT_ID, scopedRoleId, "华东导入主管", "east_imp_mgr", DataScopeType.DEPT_AND_CHILD,
                    CommonStatusEnum.NORMAL, List.of(1000L, 1100L, 1101L, 1105L));
            insertUser(TENANT_ID, scopedUserId, DEPT_EAST, "east_importer", "华东导入员",
                    "Ateng@ImpEast", null, null, scopedRoleId);
        });

        try {
            String scopedToken = loginAndGetToken("T32002", "east_importer", "Ateng@ImpEast");
            byte[] mixedScopeBytes = buildStandardWorkbook(List.of(
                    // row 2: 属于 DEPT_EAST_RD（华东区子部门），在 @DataScope 范围内
                    List.of("scope_ok_1", "华东合法", "华东区/研发组", "", "", "", "", "", ""),
                    // row 3: 属于 DEPT_SOUTH_RD（华南区子部门），越界
                    List.of("scope_bad_1", "华南越界", "华南区/研发组", "", "", "", "", "", ""),
                    // row 4: 未指定部门，受限操作人禁止留空
                    List.of("scope_bad_2", "空部门越界", "", "", "", "", "", "", "")
            ));

            MvcResult res = mockMvc.perform(multipart("/admin/system/user/import")
                            .file(xlsxMultipart("scope_guard.xlsx", mixedScopeBytes))
                            .header("satoken-admin", scopedToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.successCount").value(0))
                    .andExpect(jsonPath("$.data.failCount").value(2))
                    .andReturn();

            JsonNode errorsNode = objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .path("data").path("errors");
            assertThat(errorsNode).hasSize(2);
            assertThat(errorsNode.get(0).path("rowNum").asInt()).isEqualTo(3);
            assertThat(errorsNode.get(1).path("rowNum").asInt()).isEqualTo(4);

            // RBAC 防提权：受限导入人尝试通过 Excel 赋予超出自身权限/数据范围的角色 imp_admin（ALL + 1106）必须在阶段 1 被拦截
            byte[] roleEscBytes = buildStandardWorkbook(List.of(
                    List.of("role_esc_u", "角色越权导入", "华东区/研发组", "", "imp_admin", "", "", "", "")
            ));
            MvcResult roleEscRes = mockMvc.perform(multipart("/admin/system/user/import")
                            .file(xlsxMultipart("role_escalation.xlsx", roleEscBytes))
                            .header("satoken-admin", scopedToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.successCount").value(0))
                    .andExpect(jsonPath("$.data.failCount").value(1))
                    .andReturn();
            assertThat(objectMapper.readTree(roleEscRes.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .path("data").path("errors").get(0).path("errorMessage").asText())
                    .contains("超出当前操作人边界");

            // 阶段 3 TOCTOU 复核：引入已停用部门 DEPT_DISABLED 必须抛出 100001 且整批不落库
            String hash = PasswordUtil.hash("Ateng@Stage3Check");
            PreparedImportUser staleDeptUser = new PreparedImportUser(
                    new ValidatedImportRow("toctou_bad_u", "并发停用测试", DEPT_DISABLED,
                            List.of(), List.of(), null, null, "2", CommonStatusEnum.NORMAL.getCode()),
                    hash
            );
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            userBatchPersistHelper.persistBatch(TENANT_ID, List.of(staleDeptUser)))
                    .isInstanceOf(io.github.atengk.boot.core.exception.BusinessException.class)
                    .extracting("code")
                    .isEqualTo(CommonBizResultCode.PARAM_INVALID.getCode());
        } finally {
            TenantContextHolder.runWithTenant(TENANT_ID, () -> {
                sysUserRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, scopedUserId));
                sysUserMapper.deleteById(scopedUserId);
                sysRoleMenuMapper.delete(Wrappers.<SysRoleMenu>lambdaQuery().eq(SysRoleMenu::getRoleId, scopedRoleId));
                sysRoleMapper.deleteById(scopedRoleId);
            });
        }
    }

    private static MockMultipartFile xlsxMultipart(String filename, byte[] bytes) {
        return new MockMultipartFile(
                "file",
                filename,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                bytes
        );
    }

    private static byte[] buildStandardWorkbook(List<List<String>> rows) throws Exception {
        return buildWorkbook(
                List.of("用户账号", "用户昵称", "归属部门", "岗位编码", "角色编码", "手机号码", "电子邮箱", "性别", "状态"),
                rows
        );
    }

    private static byte[] buildWorkbook(List<String> headers, List<List<String>> rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("用户导入");
            Row headRow = sheet.createRow(0);
            for (int c = 0; c < headers.size(); c++) {
                headRow.createCell(c).setCellValue(headers.get(c));
            }
            for (int r = 0; r < rows.size(); r++) {
                Row dataRow = sheet.createRow(r + 1);
                List<String> values = rows.get(r);
                for (int c = 0; c < values.size(); c++) {
                    dataRow.createCell(c).setCellValue(values.get(c));
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private String loginAndGetToken(String tenantCode, String username, String password) throws Exception {
        MvcResult res = mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", tenantCode,
                                "username", username,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode root = objectMapper.readTree(res.getResponse().getContentAsString());
        return root.path("data").path("tokenValue").asText();
    }

    private void insertTenant(Long id, String code, String name, Long packageId, int accountCount) {
        SysTenant tenant = new SysTenant();
        tenant.setId(id);
        tenant.setTenantCode(code);
        tenant.setTenantName(name);
        tenant.setPackageId(packageId);
        tenant.setAccountCount(accountCount);
        tenant.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysTenantMapper.insert(tenant);
    }

    private void insertDept(Long tenantId, Long id, Long parentId, String ancestors, String name, CommonStatusEnum status) {
        SysDept dept = new SysDept();
        dept.setId(id);
        dept.setTenantId(tenantId);
        dept.setParentId(parentId);
        dept.setAncestors(ancestors);
        dept.setDeptName(name);
        dept.setSort(1);
        dept.setStatus(status.getCode());
        sysDeptMapper.insert(dept);
    }

    private void insertPost(Long tenantId, Long id, String code, String name, CommonStatusEnum status) {
        SysPost post = new SysPost();
        post.setId(id);
        post.setTenantId(tenantId);
        post.setPostCode(code);
        post.setPostName(name);
        post.setSort(1);
        post.setStatus(status.getCode());
        sysPostMapper.insert(post);
    }

    private void insertRole(
            Long tenantId,
            Long id,
            String name,
            String key,
            DataScopeType scope,
            CommonStatusEnum status,
            List<Long> menuIds
    ) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setTenantId(tenantId);
        role.setRoleName(name);
        role.setRoleKey(key);
        role.setDataScope(scope.getCode());
        role.setMenuCheckStrictly(true);
        role.setDeptCheckStrictly(false);
        role.setSort(1);
        role.setStatus(status.getCode());
        sysRoleMapper.insert(role);

        for (Long menuId : menuIds) {
            SysRoleMenu rm = new SysRoleMenu();
            rm.setTenantId(tenantId);
            rm.setRoleId(id);
            rm.setMenuId(menuId);
            sysRoleMenuMapper.insert(rm);
        }
    }

    private void insertUser(
            Long tenantId,
            Long id,
            Long deptId,
            String username,
            String nickname,
            String rawPassword,
            String phone,
            String email,
            Long roleId
    ) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setTenantId(tenantId);
        user.setDeptId(deptId);
        user.setUsername(username);
        user.setNickname(nickname);
        user.setPassword(PasswordUtil.hash(rawPassword));
        user.setPhone(phone);
        user.setEmail(email);
        user.setGender("2");
        user.setStatus(CommonStatusEnum.NORMAL.getCode());
        sysUserMapper.insert(user);

        if (roleId != null) {
            SysUserRole ur = new SysUserRole();
            ur.setTenantId(tenantId);
            ur.setUserId(id);
            ur.setRoleId(roleId);
            sysUserRoleMapper.insert(ur);
        }
    }
}

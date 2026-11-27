package io.github.atengk.solid.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.excel.util.ExcelWebUtil;
import io.github.atengk.boot.web.annotation.OperateLog;
import io.github.atengk.boot.web.idempotent.annotation.Idempotent;
import io.github.atengk.boot.web.ratelimit.annotation.LimitType;
import io.github.atengk.boot.web.ratelimit.annotation.RateLimiter;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.excel.UserExportVO;
import io.github.atengk.solid.system.excel.UserImportDTO;
import io.github.atengk.solid.system.request.UserExportRequest;
import io.github.atengk.solid.system.service.SysUserExcelService;
import io.github.atengk.solid.system.service.SysUserExcelService.ExportContext;
import io.github.atengk.solid.system.service.SysUserQueryService;
import io.github.atengk.solid.system.support.ImportFeedbackStore.FeedbackFile;
import io.github.atengk.solid.system.vo.ImportResultVO;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * 用户管理 Excel 模板下载、三段式 500 行并发导入、批注暂存下载与流式脱敏导出控制器
 * （路由前缀：{@code /admin/system/user}）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Slf4j
@RestController
@RequestMapping("/admin/system/user")
@RequiredArgsConstructor
public class SysUserImportExportController {

    private static final int EXPORT_PAGE_SIZE = 500;
    private static final DateTimeFormatter FILENAME_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final SysUserExcelService sysUserExcelService;
    private final SysUserQueryService sysUserQueryService;

    /**
     * 动态下载带表头批注与性别/状态下拉约束的 9 列用户导入 Excel 模板。
     *
     * @param response HTTP 响应对象
     */
    @SaCheckPermission("system:user:import")
    @GetMapping("/import/template")
    public void downloadImportTemplate(HttpServletResponse response) {
        ExcelWebUtil.exportTemplate(response, "用户导入模板", "用户导入", UserImportDTO.class);
    }

    /**
     * 上传 {@code .xlsx}（<= 2MB，最多 500 行）执行用户批量整批原子导入。
     *
     * @param file 上传的 Excel 文件
     * @return 导入汇总结果（含失败行明细及 15 分钟归属隔离批注下载令牌）
     */
    @SaCheckPermission("system:user:import")
    @Idempotent(
            prefix = "user:import:",
            key = "T(io.github.atengk.solid.system.controller.SysUserImportExportController).buildImportIdempotentKey(#file)"
    )
    @RateLimiter(limitType = LimitType.USER, key = "'system:user:import'", count = 5, time = 60)
    @OperateLog(module = "用户管理", type = OperateTypeEnum.IMPORT, description = "批量导入用户", saveParams = false, saveResult = false)
    @PostMapping("/import")
    public Result<ImportResultVO> importUsers(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(CommonBizResultCode.EXCEL_VALIDATION_FAILED, "上传的 Excel 文件不能为空");
        }
        if (file.getSize() > SysUserExcelService.MAX_IMPORT_FILE_BYTES) {
            throw new BusinessException(CommonBizResultCode.EXCEL_VALIDATION_FAILED, "上传的 Excel 文件大小不能超过 2MB");
        }
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException(CommonBizResultCode.EXCEL_VALIDATION_FAILED, "读取上传 Excel 文件流失败", ex);
        }
        return Result.success(sysUserExcelService.importUsers(file.getOriginalFilename(), fileBytes));
    }

    /**
     * 按暂存令牌下载带标红批注的导入错误 Excel 工作簿（校验租户与操作人归属，过期返回 404，越权返回 403）。
     *
     * @param feedbackToken 32 位批注访问令牌
     * @return 带 RFC 5987 下载头的 {@code .xlsx} 二进制流
     */
    @SaCheckPermission("system:user:import")
    @GetMapping("/import/feedback/{feedbackToken}")
    public ResponseEntity<byte[]> downloadFeedback(@PathVariable String feedbackToken) {
        FeedbackFile feedback = sysUserExcelService.loadImportFeedback(feedbackToken);
        return ResponseEntity.ok()
                .headers(ExcelWebUtil.createDownloadHeaders(feedback.filename()))
                .body(feedback.content());
    }

    /**
     * 按当前筛选或勾选条件流式分页导出受 {@code @DataScope} 约束且手机/邮箱脱敏的用户数据。
     *
     * @param request  导出筛选条件（可选）
     * @param response HTTP 响应对象
     */
    @SaCheckPermission("system:user:export")
    @RateLimiter(limitType = LimitType.USER, key = "'system:user:export'", count = 10, time = 60)
    @OperateLog(module = "用户管理", type = OperateTypeEnum.EXPORT, description = "导出用户数据", excludeParams = {"phone", "email"}, saveResult = false)
    @PostMapping("/export")
    public void exportUsers(
            @Valid @RequestBody(required = false) UserExportRequest request,
            HttpServletResponse response
    ) {
        ExportContext context = sysUserExcelService.prepareExportContext(request);
        String filename = "用户数据_" + LocalDateTime.now().format(FILENAME_TIME_FORMATTER);
        ExcelWebUtil.exportStreaming(
                response,
                filename,
                "用户数据",
                UserExportVO.class,
                pageNum -> sysUserQueryService.pageUsersForExport(
                        context.tenantId(),
                        context.request(),
                        context.deptSubtreeIds(),
                        pageNum,
                        EXPORT_PAGE_SIZE,
                        context.genderCodeToLabel()
                )
        );
    }

    /**
     * 为批量用户导入生成兼顾租户、操作人与文件指纹的幂等防重键。
     *
     * @param file 上传的 Excel 文件
     * @return 用于 {@link Idempotent#key()} 的规范化指纹键值
     */
    public static String buildImportIdempotentKey(MultipartFile file) {
        String userId = StringUtils.hasText(UserContextHolder.get()) ? UserContextHolder.get() : "anonymous";
        Long tenantId = TenantContextHolder.getTenantIdAsLong();
        String tenantKey = tenantId != null ? String.valueOf(tenantId) : "default";
        if (file == null) {
            return tenantKey + ":" + userId + ":null";
        }
        String rawFilename = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename().trim() : "unknown";
        String filenameDigest = sha256Hex(rawFilename.getBytes(StandardCharsets.UTF_8));
        if (file.isEmpty() || file.getSize() > SysUserExcelService.MAX_IMPORT_FILE_BYTES) {
            return tenantKey + ":" + userId + ":" + filenameDigest + ":size:" + file.getSize();
        }
        try {
            String contentDigest = sha256Hex(file.getBytes());
            return tenantKey + ":" + userId + ":" + filenameDigest + ":" + contentDigest;
        } catch (IOException ex) {
            log.warn("读取上传文件字节计算幂等指纹失败，降级使用文件体积指纹: filename={}", rawFilename, ex);
            return tenantKey + ":" + userId + ":" + filenameDigest + ":size:" + file.getSize();
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK SHA-256 摘要算法不可用", ex);
        }
    }
}

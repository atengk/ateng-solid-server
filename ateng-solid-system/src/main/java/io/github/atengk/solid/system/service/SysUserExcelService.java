package io.github.atengk.solid.system.service;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.AsyncUtil;
import io.github.atengk.boot.core.util.PasswordUtil;
import io.github.atengk.boot.excel.enums.HeaderValidationStrategy;
import io.github.atengk.boot.excel.model.ExcelErrorRow;
import io.github.atengk.boot.excel.model.ExcelReadResult;
import io.github.atengk.boot.excel.util.ExcelFeedbackUtil;
import io.github.atengk.boot.excel.util.ExcelUtil;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.excel.UserImportDTO;
import io.github.atengk.solid.system.properties.SolidSystemProperties;
import io.github.atengk.solid.system.request.UserExportRequest;
import io.github.atengk.solid.system.security.SolidDataPermissionHandler;
import io.github.atengk.solid.system.security.SolidDataPermissionHandler.ScopeResolution;
import io.github.atengk.solid.system.service.UserBatchPersistHelper.PreparedImportUser;
import io.github.atengk.solid.system.service.UserImportValidator.ValidatedImportRow;
import io.github.atengk.solid.system.service.UserImportValidator.ValidationOutcome;
import io.github.atengk.solid.system.support.ImportFeedbackStore;
import io.github.atengk.solid.system.support.ImportFeedbackStore.FeedbackFile;
import io.github.atengk.solid.system.support.RbacPrivilegeGuard;
import io.github.atengk.solid.system.support.RbacPrivilegeGuard.ActorPrivilegeContext;
import io.github.atengk.solid.system.support.RoleViewSupport;
import io.github.atengk.solid.system.support.SystemContextSupport;
import io.github.atengk.solid.system.support.UserContactAndDictSupport;
import io.github.atengk.solid.system.support.UserImportSemaphoreCoordinator;
import io.github.atengk.solid.system.vo.ImportErrorVO;
import io.github.atengk.solid.system.vo.ImportResultVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 用户 Excel 500 行三段式并发原子导入与流式受权导出服务。
 * <p>
 * 导入严格遵循“阶段 1 无事务全量校验（出错即渲染批注并零哈希返回）➔ 阶段 2 信号量限流与有界平台池
 * {@code password-hash} 并行 PBKDF2 计算 ➔ 阶段 3 短事务行锁复核配额与 {@code BatchUtil.insertBatch} 落库”三段式解耦；
 * 严格保持对 {@code jakarta.servlet} 的零依赖。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Service
@RequiredArgsConstructor
public class SysUserExcelService {

    /**
     * 导入文件体积硬上限（2MB）
     */
    public static final int MAX_IMPORT_FILE_BYTES = 2 * 1024 * 1024;

    /**
     * 单次流式导出总行数硬上限
     */
    public static final long MAX_EXPORT_ROWS = 50_000L;

    private static final int DEFAULT_IMPORT_MAX_ROWS = 500;
    private static final long HASH_TIMEOUT_SECONDS = 90L;
    private static final String DEFAULT_FEEDBACK_STEM = "用户导入";
    private static final String FEEDBACK_SUFFIX = "_错误批注.xlsx";
    private static final int MAX_FEEDBACK_FILENAME_LENGTH = 64;
    private static final java.util.regex.Pattern CONTROL_CHAR_PATTERN = java.util.regex.Pattern.compile("\\p{Cntrl}+");
    private static final java.util.regex.Pattern UNSAFE_FILENAME_CHAR_PATTERN = java.util.regex.Pattern.compile("[\\\\/:*?\"<>|;'%&=#,]+");

    private final SystemContextSupport contextSupport;
    private final SolidSystemProperties systemProperties;
    private final UserImportValidator userImportValidator;
    private final UserBatchPersistHelper userBatchPersistHelper;
    private final ImportFeedbackStore importFeedbackStore;
    private final UserContactAndDictSupport userContactAndDictSupport;
    private final UserImportSemaphoreCoordinator userImportSemaphoreCoordinator;
    private final SolidDataPermissionHandler dataPermissionHandler;
    private final RbacPrivilegeGuard rbacPrivilegeGuard;
    private final SysUserQueryService sysUserQueryService;

    /**
     * 执行三段式 500 行用户 Excel 整批原子导入。
     *
     * @param originalFilename 上传原始文件名（必须为 {@code .xlsx}）
     * @param fileBytes        一次性读入内存的 Excel 字节数组（<= 2MB）
     * @return 导入汇总结果（全量通过返回入库数量，存在错误则整批回滚并附带 {@code feedbackToken}）
     */
    public ImportResultVO importUsers(String originalFilename, byte[] fileBytes) {
        validateImportFile(originalFilename, fileBytes);
        Long tenantId = contextSupport.requireEffectiveTenantId();
        Long operatorUserId = contextSupport.requireCurrentActorUserId();

        int importMaxRows = resolveImportMaxRows();
        ExcelReadResult<UserImportDTO> readResult = parseExcelSafely(fileBytes, importMaxRows);
        boolean allowSuperAdminAssign = RoleViewSupport.isPlatformSuperAdminView(tenantId);
        ScopeResolution actorScope = dataPermissionHandler.resolveActorScope(tenantId, operatorUserId);
        ActorPrivilegeContext actorPrivilege = rbacPrivilegeGuard.resolveActorContext(tenantId, operatorUserId);

        // 1. 阶段 1（无事务）：全量校验并统计总数据行数，任一出错立即渲染标红批注后返回，绝不触发耗时 PBKDF2 计算
        ValidationOutcome outcome = userImportValidator.validate(
                tenantId, allowSuperAdminAssign, actorScope, actorPrivilege, readResult, importMaxRows
        );
        if (!outcome.errors().isEmpty()) {
            return buildErrorFeedbackResult(
                    tenantId, operatorUserId, originalFilename, fileBytes, outcome.totalCount(), outcome.errors()
            );
        }

        // 2. 阶段 2（无事务）：先读取强口令，再通过信号量限流与有界平台池并行计算每行独立随机盐哈希
        String initPassword = userContactAndDictSupport.resolveAndValidatePassword(tenantId, null);
        List<PreparedImportUser> preparedUsers = computeHashesInParallel(outcome.validRows(), initPassword);

        // 3. 阶段 3（短事务）：加租户悲观行锁复核配额并批量落库
        int successCount = userBatchPersistHelper.persistBatch(tenantId, preparedUsers);
        return ImportResultVO.builder()
                .totalCount(outcome.totalCount())
                .successCount(successCount)
                .failCount(0)
                .errors(Collections.emptyList())
                .feedbackToken(null)
                .build();
    }

    /**
     * 按令牌加载导入错误批注工作簿（严格校验租户与操作人归属）。
     *
     * @param feedbackToken 32 位批注访问令牌
     * @return 批注文件载体
     */
    public FeedbackFile loadImportFeedback(String feedbackToken) {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        Long operatorUserId = contextSupport.requireCurrentActorUserId();
        return importFeedbackStore.load(feedbackToken, tenantId, operatorUserId);
    }

    /**
     * 在导出流输出启动前解析并校验当前请求租户、预解析部门子树、导出总条数上限及性别字典映射。
     *
     * @param request 导出筛选条件
     * @return 只读导出上下文快照
     */
    public ExportContext prepareExportContext(UserExportRequest request) {
        return prepareExportContext(request, MAX_EXPORT_ROWS);
    }

    ExportContext prepareExportContext(UserExportRequest request, long maxExportRows) {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        UserExportRequest safeRequest = normalizeExportRequest(request);
        SystemContextSupport.assertValidTimeRange(safeRequest.getBeginTime(), safeRequest.getEndTime());

        List<Long> deptSubtreeIds = CollectionUtils.isEmpty(safeRequest.getIds())
                ? sysUserQueryService.resolveDeptSubtreeIds(tenantId, safeRequest.getDeptId())
                : Collections.emptyList();
        long totalRows = sysUserQueryService.countUsersForExport(tenantId, safeRequest, deptSubtreeIds);
        if (totalRows > maxExportRows) {
            throw new BusinessException(
                    CommonBizResultCode.PARAM_INVALID,
                    "单次导出数据量（" + totalRows + " 条）超过上限 " + maxExportRows + " 条，请缩小筛选条件后分批导出"
            );
        }

        Map<String, String> genderMap = userContactAndDictSupport.loadGenderDictionary().codeToLabel();
        return new ExportContext(tenantId, safeRequest, Collections.unmodifiableList(deptSubtreeIds), genderMap);
    }

    private static UserExportRequest normalizeExportRequest(UserExportRequest request) {
        UserExportRequest copy = new UserExportRequest();
        if (request == null) {
            return copy;
        }
        copy.setUsername(request.getUsername());
        copy.setPhone(request.getPhone());
        copy.setStatus(request.getStatus());
        copy.setDeptId(request.getDeptId());
        copy.setBeginTime(request.getBeginTime());
        copy.setEndTime(request.getEndTime());
        if (!CollectionUtils.isEmpty(request.getIds())) {
            LinkedHashSet<Long> cleanedIds = new LinkedHashSet<>(request.getIds().size());
            for (Long id : request.getIds()) {
                if (id != null && id > 0) {
                    cleanedIds.add(id);
                }
            }
            copy.setIds(List.copyOf(cleanedIds));
        }
        return copy;
    }

    private ExcelReadResult<UserImportDTO> parseExcelSafely(byte[] fileBytes, int importMaxRows) {
        int maxErrorRows = Math.max(DEFAULT_IMPORT_MAX_ROWS, importMaxRows);
        try {
            return ExcelUtil.readWithResult(
                    new ByteArrayInputStream(fileBytes),
                    UserImportDTO.class,
                    HeaderValidationStrategy.LOOSE,
                    maxErrorRows,
                    importMaxRows
            );
        } catch (BusinessException ex) {
            if (CommonBizResultCode.EXCEL_VALIDATION_FAILED.getCode().equals(ex.getCode())) {
                throw ex;
            }
            throw new BusinessException(CommonBizResultCode.EXCEL_VALIDATION_FAILED, ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            throw new BusinessException(
                    CommonBizResultCode.EXCEL_VALIDATION_FAILED,
                    "Excel 文件解析失败，请确认文件未损坏且符合 9 列模板格式",
                    ex
            );
        }
    }

    private ImportResultVO buildErrorFeedbackResult(
            Long tenantId,
            Long operatorUserId,
            String originalFilename,
            byte[] fileBytes,
            int totalCount,
            List<ExcelErrorRow> rawErrors
    ) {
        byte[] feedbackBytes = renderFeedbackBytes(fileBytes, rawErrors);
        String token = importFeedbackStore.save(
                tenantId, operatorUserId, buildFeedbackFilename(originalFilename), feedbackBytes
        );
        List<ImportErrorVO> errorVOs = new ArrayList<>(rawErrors.size());
        for (ExcelErrorRow error : rawErrors) {
            if (error == null) {
                continue;
            }
            String columnName = StringUtils.hasText(error.getColumnName()) ? error.getColumnName() : "整行";
            String rawValue = error.getInvalidValue() != null ? String.valueOf(error.getInvalidValue()) : null;
            // 严格剔除 rowData 防止将整行敏感实体属性暴露给前端 JSON
            errorVOs.add(ImportErrorVO.builder()
                    .rowNum(error.getRowIndex())
                    .columnName(columnName)
                    .errorMessage(error.getMessage())
                    .rawValue(rawValue)
                    .build());
        }
        return ImportResultVO.builder()
                .totalCount(totalCount)
                .successCount(0)
                .failCount(errorVOs.size())
                .errors(Collections.unmodifiableList(errorVOs))
                .feedbackToken(token)
                .build();
    }

    private static byte[] renderFeedbackBytes(byte[] fileBytes, List<ExcelErrorRow> errors) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(fileBytes);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ExcelFeedbackUtil.renderErrorFeedback(in, out, errors);
            return out.toByteArray();
        } catch (IOException | RuntimeException ex) {
            throw new BusinessException(CommonBizResultCode.EXCEL_VALIDATION_FAILED, "生成导入错误批注文件失败", ex);
        }
    }

    private List<PreparedImportUser> computeHashesInParallel(List<ValidatedImportRow> rows, String initPassword) {
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        return userImportSemaphoreCoordinator.executeWithPermit(() -> executeParallelHashTasks(rows, initPassword));
    }

    private List<PreparedImportUser> executeParallelHashTasks(List<ValidatedImportRow> rows, String initPassword) {
        List<CompletableFuture<String>> futures = new ArrayList<>(rows.size());
        try {
            for (int i = 0; i < rows.size(); i++) {
                futures.add(AsyncUtil.supply(
                        SolidConstants.PASSWORD_HASH_POOL_NAME,
                        () -> PasswordUtil.hash(initPassword)
                ));
            }
            return awaitHashTasks(rows, futures, HASH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            cancelIncompleteFutures(futures);
        }
    }

    static List<PreparedImportUser> awaitHashTasks(
            List<ValidatedImportRow> rows,
            List<CompletableFuture<String>> futures,
            long timeout,
            TimeUnit unit
    ) {
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        boolean allCompleted = false;
        try {
            CompletableFuture<Void> coordinator = new CompletableFuture<>();
            java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(futures.size());
            for (CompletableFuture<String> future : futures) {
                future.whenComplete((res, ex) -> {
                    if (ex != null) {
                        if (coordinator.completeExceptionally(ex)) {
                            cancelIncompleteFutures(futures);
                        }
                        return;
                    }
                    if (remaining.decrementAndGet() == 0) {
                        coordinator.complete(null);
                    }
                });
            }
            coordinator.get(timeout, unit);

            List<PreparedImportUser> prepared = new ArrayList<>(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                prepared.add(new PreparedImportUser(rows.get(i), futures.get(i).join()));
            }
            allCompleted = true;
            return prepared;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "密码加密并行任务被中断", ex);
        } catch (TimeoutException ex) {
            throw new BusinessException(CommonResultCode.SERVICE_UNAVAILABLE, "批量导入密码加密超时，请拆分更小批次重试", ex);
        } catch (ExecutionException ex) {
            Throwable cause = unwrapExecutionCause(ex);
            if (cause instanceof BusinessException bizEx) {
                throw bizEx;
            }
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "批量导入密码加密计算失败", cause);
        } finally {
            if (!allCompleted) {
                cancelIncompleteFutures(futures);
            }
        }
    }

    private static Throwable unwrapExecutionCause(Throwable error) {
        Throwable cursor = error;
        while (cursor instanceof java.util.concurrent.CompletionException || cursor instanceof ExecutionException) {
            if (cursor.getCause() == null) {
                break;
            }
            cursor = cursor.getCause();
        }
        return cursor;
    }

    private static void cancelIncompleteFutures(List<CompletableFuture<String>> futures) {
        for (CompletableFuture<String> future : futures) {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private static void validateImportFile(String originalFilename, byte[] fileBytes) {
        if (!StringUtils.hasText(originalFilename)
                || !originalFilename.trim().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new BusinessException(CommonBizResultCode.EXCEL_VALIDATION_FAILED, "仅支持上传 .xlsx 格式的 Excel 文件");
        }
        if (fileBytes == null || fileBytes.length == 0) {
            throw new BusinessException(CommonBizResultCode.EXCEL_VALIDATION_FAILED, "上传的 Excel 文件不能为空");
        }
        if (fileBytes.length > MAX_IMPORT_FILE_BYTES) {
            throw new BusinessException(CommonBizResultCode.EXCEL_VALIDATION_FAILED, "上传的 Excel 文件大小不能超过 2MB");
        }
    }

    static String buildFeedbackFilename(String originalFilename) {
        String baseName = DEFAULT_FEEDBACK_STEM;
        if (StringUtils.hasText(originalFilename)) {
            String noControl = CONTROL_CHAR_PATTERN.matcher(originalFilename).replaceAll("").trim();
            int lastSlash = Math.max(noControl.lastIndexOf('/'), noControl.lastIndexOf('\\'));
            String filenameOnly = lastSlash >= 0 ? noControl.substring(lastSlash + 1) : noControl;
            int dotIndex = filenameOnly.lastIndexOf('.');
            String stem = dotIndex >= 0 ? filenameOnly.substring(0, dotIndex) : filenameOnly;
            stem = UNSAFE_FILENAME_CHAR_PATTERN.matcher(stem).replaceAll("");
            while (stem.contains("..")) {
                stem = stem.replace("..", "");
            }
            stem = trimDotsAndWhitespace(stem);
            int maxStemLen = MAX_FEEDBACK_FILENAME_LENGTH - FEEDBACK_SUFFIX.length();
            if (stem.length() > maxStemLen) {
                int endIndex = maxStemLen;
                if (Character.isHighSurrogate(stem.charAt(endIndex - 1))) {
                    endIndex--;
                }
                stem = trimDotsAndWhitespace(stem.substring(0, Math.max(0, endIndex)));
            }
            if (StringUtils.hasText(stem)) {
                baseName = stem;
            }
        }
        return baseName + FEEDBACK_SUFFIX;
    }

    private static String trimDotsAndWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && (Character.isWhitespace(value.charAt(start)) || value.charAt(start) == '.')) {
            start++;
        }
        while (end > start && (Character.isWhitespace(value.charAt(end - 1)) || value.charAt(end - 1) == '.')) {
            end--;
        }
        return value.substring(start, end);
    }

    private int resolveImportMaxRows() {
        int configured = systemProperties != null && systemProperties.getUser() != null
                ? systemProperties.getUser().getImportMaxRows()
                : DEFAULT_IMPORT_MAX_ROWS;
        return configured > 0 ? configured : DEFAULT_IMPORT_MAX_ROWS;
    }

    /**
     * 用户流式导出上下文快照。
     *
     * @param tenantId          生效工作租户主键
     * @param request           标准化后的导出筛选条件
     * @param deptSubtreeIds    导出准备期预解析的部门子树主键集合
     * @param genderCodeToLabel 性别字典编码到展示文本映射
     */
    public record ExportContext(
            Long tenantId,
            UserExportRequest request,
            List<Long> deptSubtreeIds,
            Map<String, String> genderCodeToLabel
    ) {
    }
}

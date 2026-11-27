package io.github.atengk.solid.system.service;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.excel.model.ExcelErrorRow;
import io.github.atengk.boot.excel.model.ExcelReadResult;
import io.github.atengk.boot.excel.model.ExcelRowData;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.excel.UserImportDTO;
import io.github.atengk.solid.system.security.SolidDataPermissionHandler.ScopeResolution;
import io.github.atengk.solid.system.support.ImportDeptPathResolver;
import io.github.atengk.solid.system.support.ImportReferenceCatalogSupport;
import io.github.atengk.solid.system.support.ImportReferenceCatalogSupport.ReferenceCatalog;
import io.github.atengk.solid.system.support.SystemContextSupport;
import io.github.atengk.solid.system.support.TenantQuotaGuard;
import io.github.atengk.solid.system.support.UserContactAndDictSupport;
import io.github.atengk.solid.system.support.UserContactAndDictSupport.GenderDictionary;
import io.github.atengk.solid.system.support.ContactFieldCrypto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 用户批量导入阶段 1 无事务全量校验器。
 * <p>
 * 依托 1-based 物理行号执行格式规整、批内去重、批量 {@code IN} 确定性密文查重、部门名称与 {@code /} 全路径消歧解析、
 * 岗位/角色编码存在性与防分配 {@code super_admin} 拦截、动态性别字典映射及无锁配额预检，杜绝循环 N+1 查询与长事务持锁。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Component
@RequiredArgsConstructor
public class UserImportValidator {

    private static final int FIRST_DATA_ROW_INDEX = 2;

    private final TenantQuotaGuard tenantQuotaGuard;
    private final ImportReferenceCatalogSupport catalogSupport;

    /**
     * 对 Excel 解析结果执行阶段 1 全量只读业务校验（无写事务、无密码哈希计算）。
     *
     * @param tenantId              当前工作租户主键
     * @param allowSuperAdminAssign 是否处于平台超管视角
     * @param actorScope            当前操作人数据权限范围快照（为空时按空可见集合 Fail-Closed 处理）
     * @param readResult            Fesod 读取与 JSR-303 校验结果
     * @param importMaxRows         允许导入的最大物理数据行数
     * @return 校验汇总结论（含总行数、可入库规范化行列表及错误明细列表）
     */
    public ValidationOutcome validate(
            Long tenantId,
            boolean allowSuperAdminAssign,
            ScopeResolution actorScope,
            ExcelReadResult<UserImportDTO> readResult,
            int importMaxRows
    ) {
        ScopeResolution effectiveScope = actorScope != null ? actorScope : ScopeResolution.EMPTY;
        List<ExcelRowData<UserImportDTO>> rowRecords = readResult != null && readResult.getRowRecords() != null
                ? readResult.getRowRecords()
                : Collections.emptyList();
        List<ExcelErrorRow> rawErrors = readResult != null && readResult.getErrors() != null
                ? readResult.getErrors()
                : Collections.emptyList();

        // totalCount 取有效行与 JSR-303 报错行的物理行号并集，避免全部校验失败时误判为空文件
        Set<Integer> distinctDataRows = collectDistinctRowIndices(rowRecords, rawErrors);
        int totalCount = distinctDataRows.size();
        assertValidRowCount(totalCount, importMaxRows);

        List<ExcelErrorRow> errors = new ArrayList<>(rawErrors);
        int firstRowNum = distinctDataRows.stream().findFirst().orElse(FIRST_DATA_ROW_INDEX);

        // 1. 无锁配额预检：配额不足时记录整行错误并返回，确保不进入耗时哈希阶段
        checkQuotaPreview(tenantId, totalCount, firstRowNum, errors);
        if (rowRecords.isEmpty()) {
            return new ValidationOutcome(totalCount, Collections.emptyList(), errors);
        }

        // 2. 一次性批量装载部门树索引、岗位/角色编码字典、动态性别字典及库内既有唯一键集合（零 N+1）
        ReferenceCatalog catalog = catalogSupport.loadCatalog(tenantId, rowRecords);

        // 3. 逐行执行规范化、批内查重、库内唯一冲突校验与关联语义解析
        List<ValidatedImportRow> validRows = new ArrayList<>(rowRecords.size());
        Map<String, Integer> seenUsernames = new HashMap<>(rowRecords.size());
        Map<String, Integer> seenPhones = new HashMap<>(rowRecords.size());
        Map<String, Integer> seenEmails = new HashMap<>(rowRecords.size());
        for (ExcelRowData<UserImportDTO> record : rowRecords) {
            if (record == null || record.data() == null) {
                continue;
            }
            ValidatedImportRow validated = validateSingleRow(
                    record.rowIndex(), record.data(), allowSuperAdminAssign, effectiveScope, catalog,
                    seenUsernames, seenPhones, seenEmails, errors
            );
            if (validated != null) {
                validRows.add(validated);
            }
        }
        return new ValidationOutcome(totalCount, errors.isEmpty() ? validRows : Collections.emptyList(), errors);
    }

    private void checkQuotaPreview(Long tenantId, int totalCount, int firstRowNum, List<ExcelErrorRow> errors) {
        try {
            tenantQuotaGuard.checkQuotaPreview(tenantId, totalCount);
        } catch (BusinessException ex) {
            if (SystemResultCode.TENANT_QUOTA_OR_SCOPE_EXCEEDED.getCode().equals(ex.getCode())) {
                errors.add(ExcelErrorRow.ofRow(firstRowNum, ex.getMessage()));
            } else {
                throw ex;
            }
        }
    }

    private static void assertValidRowCount(int totalCount, int importMaxRows) {
        if (totalCount == 0) {
            throw new BusinessException(CommonBizResultCode.EXCEL_VALIDATION_FAILED, "导入文件未包含任何有效用户数据行");
        }
        if (importMaxRows > 0 && totalCount > importMaxRows) {
            throw new BusinessException(
                    CommonBizResultCode.EXCEL_VALIDATION_FAILED,
                    "单次同步导入上限为 " + importMaxRows + " 行（当前解析到 " + totalCount + " 行），请拆分后导入"
            );
        }
    }

    private ValidatedImportRow validateSingleRow(
            int rowIndex,
            UserImportDTO dto,
            boolean allowSuperAdminAssign,
            ScopeResolution actorScope,
            ReferenceCatalog catalog,
            Map<String, Integer> seenUsernames,
            Map<String, Integer> seenPhones,
            Map<String, Integer> seenEmails,
            List<ExcelErrorRow> errors
    ) {
        int initialErrorCount = errors.size();
        String username = SystemContextSupport.trimToNull(dto.getUsername());
        String nickname = SystemContextSupport.trimToNull(dto.getNickname());
        if (nickname != null && (nickname.length() < 2 || nickname.length() > 30)) {
            errors.add(ExcelErrorRow.of(UserImportDTO.class, rowIndex, UserImportDTO::getNickname,
                    dto.getNickname(), "用户昵称长度须在 2~30 个非空白字符之间"));
        }

        validateUsernameUniqueness(rowIndex, username, catalog, seenUsernames, errors);
        String phone = validatePhone(rowIndex, dto.getPhone(), catalog, seenPhones, errors);
        String email = validateEmail(rowIndex, dto.getEmail(), catalog, seenEmails, errors);
        int deptErrorBefore = errors.size();
        Long deptId = ImportDeptPathResolver.resolveDeptId(rowIndex, dto.getDeptName(), catalog.deptIndex(), errors);
        if (errors.size() == deptErrorBefore && !actorScope.isDeptAllowed(deptId)) {
            String message = deptId == null
                    ? "受数据权限约束的操作人导入用户时必须指定归属部门"
                    : "归属部门不在当前操作人的数据权限范围内";
            errors.add(ExcelErrorRow.of(UserImportDTO.class, rowIndex, UserImportDTO::getDeptName,
                    dto.getDeptName(), message));
        }
        List<Long> postIds = ImportReferenceCatalogSupport.resolvePostIds(
                rowIndex, dto.getPostCodes(), catalog.activePostsByCode(), errors
        );
        List<Long> roleIds = ImportReferenceCatalogSupport.resolveRoleIds(
                rowIndex, dto.getRoleCodes(), allowSuperAdminAssign, catalog.activeRolesByCode(), errors
        );
        String gender = resolveGenderCode(rowIndex, dto.getGenderLabel(), catalog.genderDict(), errors);
        Integer status = resolveStatusCode(rowIndex, dto.getStatusLabel(), errors);

        if (errors.size() > initialErrorCount) {
            return null;
        }
        return new ValidatedImportRow(username, nickname, deptId, postIds, roleIds, phone, email, gender, status);
    }

    private static void validateUsernameUniqueness(
            int rowIndex,
            String username,
            ReferenceCatalog catalog,
            Map<String, Integer> seenUsernames,
            List<ExcelErrorRow> errors
    ) {
        if (username == null) {
            return;
        }
        Integer firstRow = seenUsernames.putIfAbsent(username, rowIndex);
        if (firstRow != null) {
            errors.add(ExcelErrorRow.of(UserImportDTO.class, rowIndex, UserImportDTO::getUsername,
                    username, "第 " + rowIndex + " 行与第 " + firstRow + " 行用户账号 " + username + " 文件内重复"));
            return;
        }
        if (catalog.existingUsernames().contains(username)) {
            errors.add(ExcelErrorRow.of(UserImportDTO.class, rowIndex, UserImportDTO::getUsername,
                    username, "用户账号 " + username + " 在当前租户下已存在"));
        }
    }

    private static String validatePhone(
            int rowIndex,
            String rawPhone,
            ReferenceCatalog catalog,
            Map<String, Integer> seenPhones,
            List<ExcelErrorRow> errors
    ) {
        String phone = ContactFieldCrypto.normalizePhone(rawPhone);
        if (phone == null) {
            return null;
        }
        if (!UserContactAndDictSupport.isValidMobilePhone(phone)) {
            errors.add(ExcelErrorRow.of(UserImportDTO.class, rowIndex, UserImportDTO::getPhone,
                    rawPhone, "手机号码格式不合法，须为 11 位中国大陆手机号"));
            return null;
        }
        Integer firstRow = seenPhones.putIfAbsent(phone, rowIndex);
        if (firstRow != null) {
            errors.add(ExcelErrorRow.of(UserImportDTO.class, rowIndex, UserImportDTO::getPhone,
                    rawPhone, "第 " + rowIndex + " 行与第 " + firstRow + " 行手机号码 " + phone + " 文件内重复"));
            return null;
        }
        String encrypted = ContactFieldCrypto.encryptPhoneForQuery(phone);
        if (encrypted != null && catalog.existingEncryptedPhones().contains(encrypted)) {
            errors.add(ExcelErrorRow.of(UserImportDTO.class, rowIndex, UserImportDTO::getPhone,
                    rawPhone, "手机号码 " + phone + " 在当前租户下已被占用"));
            return null;
        }
        return phone;
    }

    private static String validateEmail(
            int rowIndex,
            String rawEmail,
            ReferenceCatalog catalog,
            Map<String, Integer> seenEmails,
            List<ExcelErrorRow> errors
    ) {
        String email = ContactFieldCrypto.normalizeEmail(rawEmail);
        if (email == null) {
            return null;
        }
        if (!UserContactAndDictSupport.isValidEmail(email)) {
            errors.add(ExcelErrorRow.of(UserImportDTO.class, rowIndex, UserImportDTO::getEmail,
                    rawEmail, "电子邮箱格式不合法或长度超过 64 个字符"));
            return null;
        }
        Integer firstRow = seenEmails.putIfAbsent(email, rowIndex);
        if (firstRow != null) {
            errors.add(ExcelErrorRow.of(UserImportDTO.class, rowIndex, UserImportDTO::getEmail,
                    rawEmail, "第 " + rowIndex + " 行与第 " + firstRow + " 行电子邮箱 " + email + " 文件内重复"));
            return null;
        }
        String encrypted = ContactFieldCrypto.encryptEmailForQuery(email);
        if (encrypted != null && catalog.existingEncryptedEmails().contains(encrypted)) {
            errors.add(ExcelErrorRow.of(UserImportDTO.class, rowIndex, UserImportDTO::getEmail,
                    rawEmail, "电子邮箱 " + email + " 在当前租户下已被占用"));
            return null;
        }
        return email;
    }

    private static String resolveGenderCode(
            int rowIndex,
            String rawGenderLabel,
            GenderDictionary genderDict,
            List<ExcelErrorRow> errors
    ) {
        String label = SystemContextSupport.trimToNull(rawGenderLabel);
        if (label == null) {
            return genderDict.defaultCode();
        }
        String code = genderDict.labelToCode().get(label);
        if (code == null) {
            errors.add(ExcelErrorRow.of(UserImportDTO.class, rowIndex, UserImportDTO::getGenderLabel,
                    rawGenderLabel, "性别【" + label + "】不合法，可选值：" + String.join(" / ", genderDict.labelToCode().keySet())));
            return null;
        }
        return code;
    }

    private static Integer resolveStatusCode(
            int rowIndex,
            String rawStatusLabel,
            List<ExcelErrorRow> errors
    ) {
        String label = SystemContextSupport.trimToNull(rawStatusLabel);
        if (label == null || "正常".equals(label)) {
            return CommonStatusEnum.NORMAL.getCode();
        }
        if ("停用".equals(label)) {
            return CommonStatusEnum.DISABLED.getCode();
        }
        errors.add(ExcelErrorRow.of(UserImportDTO.class, rowIndex, UserImportDTO::getStatusLabel,
                rawStatusLabel, "状态【" + label + "】不合法，仅支持：正常 / 停用"));
        return null;
    }

    private static Set<Integer> collectDistinctRowIndices(
            List<ExcelRowData<UserImportDTO>> rowRecords,
            List<ExcelErrorRow> errors
    ) {
        Set<Integer> indices = new TreeSet<>();
        for (ExcelRowData<UserImportDTO> record : rowRecords) {
            if (record != null && record.rowIndex() > 0) {
                indices.add(record.rowIndex());
            }
        }
        for (ExcelErrorRow error : errors) {
            if (error != null && error.getRowIndex() != null && error.getRowIndex() > 0) {
                indices.add(error.getRowIndex());
            }
        }
        return indices;
    }

    /**
     * 阶段 1 校验通过后的单行规范化用户记录。
     */
    public record ValidatedImportRow(
            String username,
            String nickname,
            Long deptId,
            List<Long> postIds,
            List<Long> roleIds,
            String phone,
            String email,
            String gender,
            Integer status
    ) {
    }

    /**
     * 阶段 1 全量校验输出汇总。
     */
    public record ValidationOutcome(
            int totalCount,
            List<ValidatedImportRow> validRows,
            List<ExcelErrorRow> errors
    ) {
    }
}

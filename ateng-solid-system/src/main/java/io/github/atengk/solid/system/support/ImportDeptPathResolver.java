package io.github.atengk.solid.system.support;

import io.github.atengk.boot.excel.model.ExcelErrorRow;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.excel.UserImportDTO;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 批量导入部门名称与 {@code /} 层级全路径索引及歧义消解无状态工具。
 *
 * @author Ateng
 * @since 2026-11-25
 */
public final class ImportDeptPathResolver {

    private ImportDeptPathResolver() {
    }

    /**
     * 基于全租户未删除部门列表构建正常启用态部门的名称及 {@code /} 全路径查询索引。
     *
     * @param allDepts 租户全量未删除部门列表
     * @return 部门查找索引
     */
    public static DeptLookupIndex buildIndex(List<SysDept> allDepts) {
        if (CollectionUtils.isEmpty(allDepts)) {
            return new DeptLookupIndex(Collections.emptyMap(), Collections.emptyMap());
        }
        Map<Long, SysDept> byId = new HashMap<>(allDepts.size());
        for (SysDept dept : allDepts) {
            if (dept.getId() != null) {
                byId.put(dept.getId(), dept);
            }
        }
        Map<String, List<Long>> activeByName = new HashMap<>();
        Map<String, List<Long>> activeByFullPath = new HashMap<>();
        for (SysDept dept : allDepts) {
            if (!CommonStatusEnum.NORMAL.getCode().equals(dept.getStatus()) || dept.getDeptName() == null) {
                continue;
            }
            String name = dept.getDeptName().trim();
            activeByName.computeIfAbsent(name, k -> new ArrayList<>()).add(dept.getId());

            String fullPath = buildFullPath(dept, byId);
            if (fullPath != null) {
                activeByFullPath.computeIfAbsent(fullPath, k -> new ArrayList<>()).add(dept.getId());
            }
        }
        return new DeptLookupIndex(activeByName, activeByFullPath);
    }

    /**
     * 解析单行导入记录中的部门名称或 {@code /} 分隔路径；未命中、已停用或存在歧义时向 {@code errors} 追加行定位错误。
     *
     * @param rowIndex 1-based 物理行号
     * @param rawDept  原始单元格部门文本
     * @param index    预建部门查找索引
     * @param errors   错误收集容器
     * @return 唯一匹配的启用部门主键；留空或匹配失败返回 {@code null}
     */
    public static Long resolveDeptId(
            int rowIndex,
            String rawDept,
            DeptLookupIndex index,
            List<ExcelErrorRow> errors
    ) {
        String trimmed = SystemContextSupport.trimToNull(rawDept);
        if (trimmed == null) {
            return null;
        }
        if (trimmed.contains("/")) {
            return resolveBySlashPath(rowIndex, rawDept, trimmed, index, errors);
        }
        List<Long> nameMatches = index.activeByName().get(trimmed);
        if (nameMatches == null || nameMatches.isEmpty()) {
            errors.add(ExcelErrorRow.of(UserImportDTO.class, rowIndex, UserImportDTO::getDeptName,
                    rawDept, "归属部门【" + trimmed + "】不存在或已停用"));
            return null;
        }
        if (nameMatches.size() > 1) {
            errors.add(ExcelErrorRow.of(UserImportDTO.class, rowIndex, UserImportDTO::getDeptName,
                    rawDept, "部门名称【" + trimmed + "】存在多个同名分支，请改用 / 级联全路径"));
            return null;
        }
        return nameMatches.get(0);
    }

    private static Long resolveBySlashPath(
            int rowIndex,
            String rawDept,
            String trimmed,
            DeptLookupIndex index,
            List<ExcelErrorRow> errors
    ) {
        String normalizedPath = normalizeDeptPath(trimmed);
        if (normalizedPath == null) {
            errors.add(ExcelErrorRow.of(UserImportDTO.class, rowIndex, UserImportDTO::getDeptName,
                    rawDept, "部门层级路径格式不合法：" + rawDept));
            return null;
        }
        List<Long> exactMatches = index.activeByFullPath().get(normalizedPath);
        if (exactMatches != null && exactMatches.size() == 1) {
            return exactMatches.get(0);
        }
        if (exactMatches != null && exactMatches.size() > 1) {
            errors.add(ExcelErrorRow.of(UserImportDTO.class, rowIndex, UserImportDTO::getDeptName,
                    rawDept, "存在多个同名层级路径【" + normalizedPath + "】，无法唯一确认"));
            return null;
        }
        List<Long> suffixMatches = new ArrayList<>();
        String suffix = "/" + normalizedPath;
        for (Map.Entry<String, List<Long>> entry : index.activeByFullPath().entrySet()) {
            if (entry.getKey().endsWith(suffix)) {
                suffixMatches.addAll(entry.getValue());
            }
        }
        if (suffixMatches.size() == 1) {
            return suffixMatches.get(0);
        }
        if (suffixMatches.size() > 1) {
            errors.add(ExcelErrorRow.of(UserImportDTO.class, rowIndex, UserImportDTO::getDeptName,
                    rawDept, "部门路径【" + rawDept + "】存在多个匹配分支，请改用完整 / 路径"));
            return null;
        }
        errors.add(ExcelErrorRow.of(UserImportDTO.class, rowIndex, UserImportDTO::getDeptName,
                rawDept, "部门路径【" + rawDept + "】不存在或已停用"));
        return null;
    }

    private static String buildFullPath(SysDept target, Map<Long, SysDept> byId) {
        List<String> segments = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        SysDept cursor = target;
        while (cursor != null && cursor.getId() != null && visited.add(cursor.getId())) {
            String name = SystemContextSupport.trimToNull(cursor.getDeptName());
            if (name != null) {
                segments.add(name);
            }
            Long parentId = cursor.getParentId();
            if (parentId == null || parentId <= 0L) {
                break;
            }
            cursor = byId.get(parentId);
        }
        if (segments.isEmpty()) {
            return null;
        }
        Collections.reverse(segments);
        return String.join("/", segments);
    }

    private static String normalizeDeptPath(String rawPath) {
        String[] parts = rawPath.split("/", -1);
        List<String> cleaned = new ArrayList<>(parts.length);
        for (String part : parts) {
            String trimmed = SystemContextSupport.trimToNull(part);
            if (trimmed == null) {
                return null;
            }
            cleaned.add(trimmed);
        }
        return cleaned.isEmpty() ? null : String.join("/", cleaned);
    }

    /**
     * 预建的正常启用部门名称与全路径查找索引。
     *
     * @param activeByName     按单节点名称分组的启用部门主键列表
     * @param activeByFullPath 按斜杠全路径分组的启用部门主键列表
     */
    public record DeptLookupIndex(
            Map<String, List<Long>> activeByName,
            Map<String, List<Long>> activeByFullPath
    ) {
    }
}

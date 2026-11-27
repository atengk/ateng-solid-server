package io.github.atengk.solid.system.support;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.vo.DeptTreeVO;
import io.github.atengk.solid.system.vo.DeptVO;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 部门树装配、祖先链回溯筛选、祖级路径拼接与联系人字段格式规范化辅助工具。
 *
 * @author Ateng
 * @since 2026-11-25
 */
public final class DeptTreeSupport {

    public static final long ROOT_PARENT_ID = 0L;
    public static final String ROOT_ANCESTORS = "0";
    public static final int MAX_ANCESTORS_LENGTH = 500;
    public static final int MAX_DEPT_DEPTH = 10;
    private static final Pattern PHONE_PATTERN = Pattern.compile("^[0-9+\\-()]{5,20}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private DeptTreeSupport() {
    }

    /**
     * 按名称关键词与状态过滤部门并自动保留命中节点的全部祖先部门。
     *
     * @param allDepts     全量有序部门列表
     * @param keyword      名称关键字（可空，不区分大小写）
     * @param statusFilter 状态筛选（可空）
     * @return 保留祖先链后的有序部门子集
     */
    public static List<SysDept> retainMatchedWithAncestors(
            List<SysDept> allDepts,
            String keyword,
            Integer statusFilter
    ) {
        if (keyword == null && statusFilter == null) {
            return allDepts;
        }
        String normalizedKeyword = keyword != null ? keyword.toLowerCase(Locale.ROOT) : null;
        Map<Long, SysDept> byId = new LinkedHashMap<>(allDepts.size());
        for (SysDept dept : allDepts) {
            byId.put(dept.getId(), dept);
        }
        Set<Long> retainedIds = new HashSet<>();
        for (SysDept dept : allDepts) {
            boolean nameMatch = normalizedKeyword == null
                    || (dept.getDeptName() != null && dept.getDeptName().toLowerCase(Locale.ROOT).contains(normalizedKeyword));
            boolean statusMatch = statusFilter == null || Objects.equals(dept.getStatus(), statusFilter);
            if (!nameMatch || !statusMatch) {
                continue;
            }
            SysDept cursor = dept;
            while (cursor != null && retainedIds.add(cursor.getId())) {
                Long parentId = cursor.getParentId();
                cursor = (parentId != null && parentId > ROOT_PARENT_ID) ? byId.get(parentId) : null;
            }
        }
        if (retainedIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<SysDept> filtered = new ArrayList<>(retainedIds.size());
        for (SysDept dept : allDepts) {
            if (retainedIds.contains(dept.getId())) {
                filtered.add(dept);
            }
        }
        return filtered;
    }

    /**
     * 将有序部门列表装配为部门树视图集合（保留完整联系明文，供有管理权限页面使用）。
     *
     * @param depts     有序部门实体列表
     * @param leaderMap 负责人映射（key 为用户主键）
     * @return 部门树根节点集合
     */
    public static List<DeptTreeVO> buildDeptTree(List<SysDept> depts, Map<Long, SysUser> leaderMap) {
        return buildDeptTreeInternal(depts, leaderMap, true);
    }

    /**
     * 将有序部门列表装配为轻量选择框树（屏蔽联系手机号与邮箱字段，供 {@code @SaCheckLogin} 下拉选项接口使用）。
     *
     * @param depts 有序部门实体列表
     * @return 脱敏后的部门树根节点集合
     */
    public static List<DeptTreeVO> buildDeptOptionTree(List<SysDept> depts) {
        return buildDeptTreeInternal(depts, Collections.emptyMap(), false);
    }

    /**
     * 转换部门详情视图对象。
     *
     * @param dept   部门实体
     * @param leader 部门负责人实体（可空）
     * @return 部门详情视图对象
     */
    public static DeptVO toDeptVO(SysDept dept, SysUser leader) {
        return DeptVO.builder()
                .id(dept.getId())
                .parentId(dept.getParentId())
                .ancestors(dept.getAncestors())
                .deptName(dept.getDeptName())
                .sort(dept.getSort())
                .leaderUserId(dept.getLeaderUserId())
                .leaderNickname(leader != null ? leader.getNickname() : null)
                .leaderUsername(leader != null ? leader.getUsername() : null)
                .phone(dept.getPhone())
                .email(dept.getEmail())
                .status(dept.getStatus())
                .createTime(dept.getCreateTime())
                .build();
    }

    /**
     * 基于父部门实体拼接直接子部门的 {@code ancestors} 链字符串。
     *
     * @param parent 父部门实体
     * @return 子部门祖级字符串
     */
    public static String buildChildAncestors(SysDept parent) {
        String base = StringUtils.hasText(parent.getAncestors()) ? parent.getAncestors().trim() : ROOT_ANCESTORS;
        return base + "," + parent.getId();
    }

    /**
     * 校验部门 {@code ancestors} 字符串长度与树深度未突破持久化与架构安全上限（{@code <= 500} 字符、{@code <= 10} 层）。
     *
     * @param ancestors 部门祖级路径链
     */
    public static void validateAncestorsBounds(String ancestors) {
        if (!StringUtils.hasText(ancestors) || ancestors.length() > MAX_ANCESTORS_LENGTH) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "部门祖级路径长度不能超过 500 个字符");
        }
        int depth = 1;
        for (int i = 0; i < ancestors.length(); i++) {
            if (ancestors.charAt(i) == ',') {
                depth++;
            }
        }
        if (depth > MAX_DEPT_DEPTH) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "部门树层级深度不能超过 10 层");
        }
    }

    /**
     * 裁剪并校验部门名称长度边界（2~50 字符，且禁止包含 Excel 全路径分隔符 {@code /}）。
     *
     * @param rawName 原始部门名称
     * @return 规范化后的部门名称
     */
    public static String requireNormalizedDeptName(String rawName) {
        String trimmed = SystemContextSupport.trimToNull(rawName);
        if (trimmed == null || trimmed.length() < 2 || trimmed.length() > 50) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "部门名称长度须在 2~50 个字符之间");
        }
        if (trimmed.contains("/")) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "部门名称不能包含路径分隔符 /");
        }
        return trimmed;
    }

    /**
     * 清洗租户名称作为初始化根部门名称（替换路径分隔符 {@code /}，避免阻断导入全路径分割）。
     *
     * @param tenantName 租户名称
     * @return 安全根部门名称
     */
    public static String normalizeRootDeptName(String tenantName) {
        String trimmed = SystemContextSupport.trimToNull(tenantName);
        if (trimmed == null) {
            return "默认根部门";
        }
        String sanitized = trimmed.replace('/', '-');
        return sanitized.length() > 50 ? sanitized.substring(0, 50) : sanitized;
    }

    /**
     * 规范化并校验部门联系电话与电子邮箱格式。
     *
     * @param rawPhone 原始电话
     * @param rawEmail 原始邮箱
     * @return 校验后的联系方式值对象
     */
    public static ContactPair normalizeAndValidateContact(String rawPhone, String rawEmail) {
        String phone = ContactFieldCrypto.normalizePhone(rawPhone);
        if (phone != null && !PHONE_PATTERN.matcher(phone).matches()) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "联系电话格式不合法");
        }
        String email = ContactFieldCrypto.normalizeEmail(rawEmail);
        if (email != null && (email.length() > 64 || !EMAIL_PATTERN.matcher(email).matches())) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "电子邮箱格式不合法或长度超出 64 位");
        }
        return new ContactPair(phone, email);
    }

    private static List<DeptTreeVO> buildDeptTreeInternal(
            List<SysDept> depts,
            Map<Long, SysUser> leaderMap,
            boolean includeContact
    ) {
        Set<Long> existingIds = new HashSet<>(depts.size());
        Map<Long, List<SysDept>> grouped = new LinkedHashMap<>();
        for (SysDept dept : depts) {
            existingIds.add(dept.getId());
            grouped.computeIfAbsent(dept.getParentId(), ignored -> new ArrayList<>()).add(dept);
        }
        Set<Long> visited = new HashSet<>(depts.size());
        List<DeptTreeVO> roots = new ArrayList<>();
        for (SysDept dept : depts) {
            if (dept == null || dept.getId() == null) {
                continue;
            }
            if ((dept.getParentId() == null || Objects.equals(dept.getParentId(), ROOT_PARENT_ID)
                    || !existingIds.contains(dept.getParentId()))
                    && visited.add(dept.getId())) {
                roots.add(assembleNode(dept, grouped, leaderMap, includeContact, visited));
            }
        }
        return roots.isEmpty() ? Collections.emptyList() : roots;
    }

    private static DeptTreeVO assembleNode(
            SysDept dept,
            Map<Long, List<SysDept>> grouped,
            Map<Long, SysUser> leaderMap,
            boolean includeContact,
            Set<Long> visited
    ) {
        List<SysDept> childrenEntities = grouped.get(dept.getId());
        List<DeptTreeVO> children;
        if (CollectionUtils.isEmpty(childrenEntities)) {
            children = Collections.emptyList();
        } else {
            List<DeptTreeVO> assembled = new ArrayList<>(childrenEntities.size());
            for (SysDept child : childrenEntities) {
                if (child != null && child.getId() != null && visited.add(child.getId())) {
                    assembled.add(assembleNode(child, grouped, leaderMap, includeContact, visited));
                }
            }
            children = assembled.isEmpty() ? Collections.emptyList() : assembled;
        }
        SysUser leader = dept.getLeaderUserId() != null ? leaderMap.get(dept.getLeaderUserId()) : null;
        return DeptTreeVO.builder()
                .id(dept.getId())
                .parentId(dept.getParentId())
                .ancestors(dept.getAncestors())
                .deptName(dept.getDeptName())
                .sort(dept.getSort())
                .leaderUserId(dept.getLeaderUserId())
                .leaderNickname(leader != null ? leader.getNickname() : null)
                .leaderUsername(leader != null ? leader.getUsername() : null)
                .phone(includeContact ? dept.getPhone() : null)
                .email(includeContact ? dept.getEmail() : null)
                .status(dept.getStatus())
                .createTime(dept.getCreateTime())
                .children(children)
                .build();
    }

    /**
     * 规范化后的部门联系方式不可变值对象。
     *
     * @param phone 标准化联系电话（可空）
     * @param email 标准化电子邮箱（可空）
     */
    public record ContactPair(String phone, String email) {
    }
}

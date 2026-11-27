package io.github.atengk.solid.system.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.mybatisplus.util.PageUtil;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.result.SystemResultCode;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.excel.UserExportVO;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserPostMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import io.github.atengk.solid.system.mapper.UserQueryCriteria;
import io.github.atengk.solid.system.request.UserExportRequest;
import io.github.atengk.solid.system.request.UserQueryRequest;
import io.github.atengk.solid.system.support.AvatarUrlResolver;
import io.github.atengk.solid.system.support.ContactFieldCrypto;
import io.github.atengk.solid.system.support.SystemContextSupport;
import io.github.atengk.solid.system.vo.UserDetailVO;
import io.github.atengk.solid.system.vo.UserOptionVO;
import io.github.atengk.solid.system.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户只读查询与视图组装服务（承载 5 级 {@code @DataScope} 分页检索、部门子树过滤与关联详情回显）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Service
@RequiredArgsConstructor
public class SysUserQueryService {

    private static final Long NON_EXISTENT_DEPT_SENTINEL = -1L;

    private final SystemContextSupport contextSupport;
    private final SysUserMapper sysUserMapper;
    private final SysDeptMapper sysDeptMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysUserPostMapper sysUserPostMapper;
    private final AvatarUrlResolver avatarUrlResolver;

    /**
     * 按租户隔离与 5 级 {@code @DataScope} 数据权限分页查询用户列表。
     *
     * @param query 分页与筛选参数
     * @return 含明文联系方式及部门名称、超管标识的分页视图结果
     */
    public PageResult<UserVO> pageUsers(UserQueryRequest query) {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        UserQueryRequest safeQuery = query != null ? query : new UserQueryRequest();
        Page<SysUser> pageRequest = PageUtil.toPage(
                safeQuery, SysUser.class, true, SystemContextSupport.DEFAULT_MAX_PAGE_SIZE
        );

        List<Long> deptSubtreeIds = resolveDeptSubtreeIds(tenantId, safeQuery.getDeptId());
        UserQueryCriteria criteria = new UserQueryCriteria(
                tenantId,
                SystemContextSupport.trimToNull(safeQuery.getUsername()),
                ContactFieldCrypto.encryptPhoneForQuery(safeQuery.getPhone()),
                safeQuery.getStatus(),
                deptSubtreeIds,
                null,
                safeQuery.getBeginTime(),
                safeQuery.getEndTime(),
                CollectionUtils.isEmpty(pageRequest.orders())
        );

        Page<SysUser> resultPage = TenantContextHolder.runWithTenant(
                tenantId,
                () -> sysUserMapper.selectUserPageWithDataScope(pageRequest, criteria)
        );
        List<SysUser> records = resultPage.getRecords();
        if (CollectionUtils.isEmpty(records)) {
            return SystemContextSupport.toPageResult(resultPage, user -> toUserVO(user, null, false));
        }

        Map<Long, String> deptNameMap = batchLoadDeptNames(tenantId, records);
        Set<Long> superAdminUserIds = batchFindSuperAdminIds(tenantId, records);
        return SystemContextSupport.toPageResult(resultPage, user -> toUserVO(
                user,
                user.getDeptId() != null ? deptNameMap.get(user.getDeptId()) : null,
                superAdminUserIds.contains(user.getId())
        ));
    }

    /**
     * 获取当前工作租户下正常启用状态的用户轻量化下拉选项列表（面向主管选择器等受权场景）。
     *
     * @return 启用中用户选项列表
     */
    public List<UserOptionVO> listActiveUserOptions() {
        return listActiveUserOptions(null);
    }

    /**
     * 按可选关键字检索当前工作租户下满足 5 级 {@code @DataScope} 与正常启用状态的用户轻量化下拉选项列表（匹配账号或昵称，单次上限 200 条）。
     *
     * @param keyword 账号或昵称模糊关键字（可为空）
     * @return 启用中用户选项列表
     */
    public List<UserOptionVO> listActiveUserOptions(String keyword) {
        Long tenantId = contextSupport.requireEffectiveTenantId();
        String trimmedKeyword = SystemContextSupport.trimToNull(keyword);
        Page<SysUser> page = TenantContextHolder.runWithTenant(tenantId, () -> sysUserMapper.selectActiveUserOptionsWithDataScope(
                new Page<>(1, 200, false),
                tenantId,
                CommonStatusEnum.NORMAL.getCode(),
                trimmedKeyword
        ));
        List<SysUser> users = page != null ? page.getRecords() : Collections.emptyList();
        if (CollectionUtils.isEmpty(users)) {
            return Collections.emptyList();
        }
        return users.stream()
                .map(user -> UserOptionVO.builder()
                        .userId(user.getId())
                        .username(user.getUsername())
                        .nickname(user.getNickname())
                        .build())
                .toList();
    }

    /**
     * 按主键查询用户详情（强制施加 5 级 {@code @DataScope} 数据权限校验，未命中抛出 {@code 102001}）。
     *
     * @param id 用户主键
     * @return 用户详情视图对象（包含明文联系方式与已授权角色/岗位主键列表）
     */
    public UserDetailVO getUserDetail(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "用户主键必须为正整数");
        }
        Long tenantId = contextSupport.requireEffectiveTenantId();
        SysUser user = TenantContextHolder.runWithTenant(
                tenantId,
                () -> sysUserMapper.selectUserByIdWithDataScope(tenantId, id)
        );
        if (user == null) {
            throw new BusinessException(SystemResultCode.RECORD_NOT_FOUND, "用户不存在或无访问权限");
        }

        String deptName = loadSingleDeptName(tenantId, user.getDeptId());
        List<Long> roleIds = TenantContextHolder.runWithTenant(
                tenantId,
                () -> SystemContextSupport.safeList(sysUserRoleMapper.selectRoleIdsByUserId(tenantId, id))
        );
        List<Long> postIds = TenantContextHolder.runWithTenant(
                tenantId,
                () -> SystemContextSupport.safeList(sysUserPostMapper.selectPostIdsByUserId(tenantId, id))
        );
        boolean builtinSuperAdmin = !batchFindSuperAdminIds(tenantId, List.of(user)).isEmpty();

        return UserDetailVO.builder()
                .id(user.getId())
                .tenantId(user.getTenantId())
                .deptId(user.getDeptId())
                .deptName(deptName)
                .username(user.getUsername())
                .nickname(user.getNickname())
                .email(user.getEmail())
                .phone(user.getPhone())
                .gender(user.getGender())
                .avatar(avatarUrlResolver.resolve(user.getAvatar()))
                .status(user.getStatus())
                .loginIp(user.getLoginIp())
                .loginDate(user.getLoginDate())
                .pwdUpdateTime(user.getPwdUpdateTime())
                .remark(user.getRemark())
                .createTime(user.getCreateTime())
                .builtinSuperAdmin(builtinSuperAdmin)
                .roleIds(roleIds)
                .postIds(postIds)
                .build();
    }

    /**
     * 展开指定部门主键及其全量未删除下级子孙部门主键集合，供分页查询与 Excel 导出共用。
     *
     * @param tenantId   工作租户主键
     * @param rootDeptId 目标部门主键（为空返回空集合，表示不按部门过滤）
     * @return 子树部门主键列表；目标部门不存在时返回哨兵集合 {@code [-1]}
     */
    public List<Long> resolveDeptSubtreeIds(Long tenantId, Long rootDeptId) {
        if (rootDeptId == null) {
            return Collections.emptyList();
        }
        List<SysDept> allDepts = DataPermissionContextHolder.runWithoutPermission(() ->
                TenantContextHolder.runWithTenant(tenantId, () -> sysDeptMapper.selectList(
                        Wrappers.<SysDept>lambdaQuery()
                                .select(SysDept::getId, SysDept::getParentId)
                                .eq(SysDept::getTenantId, tenantId)
                ))
        );
        if (CollectionUtils.isEmpty(allDepts)) {
            return List.of(NON_EXISTENT_DEPT_SENTINEL);
        }

        boolean rootExists = false;
        Map<Long, List<Long>> childrenByParent = new HashMap<>();
        for (SysDept dept : allDepts) {
            if (rootDeptId.equals(dept.getId())) {
                rootExists = true;
            }
            if (dept.getParentId() != null) {
                childrenByParent.computeIfAbsent(dept.getParentId(), k -> new ArrayList<>()).add(dept.getId());
            }
        }
        if (!rootExists) {
            return List.of(NON_EXISTENT_DEPT_SENTINEL);
        }

        Set<Long> visited = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(rootDeptId);
        visited.add(rootDeptId);
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            List<Long> children = childrenByParent.get(current);
            if (children == null) {
                continue;
            }
            for (Long childId : children) {
                if (visited.add(childId)) {
                    queue.add(childId);
                }
            }
        }
        return List.copyOf(visited);
    }

    /**
     * 批量加载用户集合关联的未删除部门名称映射（杜绝列表序列化阶段产生 N+1 查询）。
     *
     * @param tenantId 工作租户主键
     * @param users    用户实体列表
     * @return 部门主键到部门名称的只读映射
     */
    public Map<Long, String> batchLoadDeptNames(Long tenantId, List<SysUser> users) {
        if (CollectionUtils.isEmpty(users)) {
            return Collections.emptyMap();
        }
        Set<Long> deptIds = users.stream()
                .map(SysUser::getDeptId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (deptIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<SysDept> depts = DataPermissionContextHolder.runWithoutPermission(() ->
                TenantContextHolder.runWithTenant(tenantId, () -> sysDeptMapper.selectList(
                        Wrappers.<SysDept>lambdaQuery()
                                .select(SysDept::getId, SysDept::getDeptName)
                                .eq(SysDept::getTenantId, tenantId)
                                .in(SysDept::getId, deptIds)
                ))
        );
        if (CollectionUtils.isEmpty(depts)) {
            return Collections.emptyMap();
        }
        Map<Long, String> deptNameMap = new LinkedHashMap<>(depts.size());
        for (SysDept dept : depts) {
            deptNameMap.put(dept.getId(), dept.getDeptName());
        }
        return deptNameMap;
    }

    private String loadSingleDeptName(Long tenantId, Long deptId) {
        if (deptId == null || deptId <= 0) {
            return null;
        }
        SysDept dept = DataPermissionContextHolder.runWithoutPermission(() ->
                TenantContextHolder.runWithTenant(tenantId, () -> sysDeptMapper.selectOne(
                        Wrappers.<SysDept>lambdaQuery()
                                .select(SysDept::getDeptName)
                                .eq(SysDept::getTenantId, tenantId)
                                .eq(SysDept::getId, deptId)
                ))
        );
        return dept != null ? dept.getDeptName() : null;
    }

    private Set<Long> batchFindSuperAdminIds(Long tenantId, List<SysUser> users) {
        if (!SolidConstants.PLATFORM_TENANT_ID.equals(tenantId) || CollectionUtils.isEmpty(users)) {
            return Collections.emptySet();
        }
        List<Long> userIds = users.stream().map(SysUser::getId).toList();
        List<Long> matched = TenantContextHolder.runWithTenant(
                tenantId,
                () -> sysUserRoleMapper.selectSuperAdminUserIds(tenantId, userIds)
        );
        return CollectionUtils.isEmpty(matched) ? Collections.emptySet() : new HashSet<>(matched);
    }

    /**
     * 按页拉取受 5 级 {@code @DataScope} 数据权限约束的用户流式导出视图切片。
     *
     * @param tenantId          当前生效工作租户主键
     * @param request           导出筛选条件（支持勾选 {@code ids} 或筛选条件组合）
     * @param pageNum           当前页码（1-based）
     * @param pageSize          每页流式批量拉取大小
     * @param genderCodeToLabel 性别字典编码到展示文本映射
     * @return 受权用户导出分页切片
     */
    public PageResult<UserExportVO> pageUsersForExport(
            Long tenantId,
            UserExportRequest request,
            int pageNum,
            int pageSize,
            Map<String, String> genderCodeToLabel
    ) {
        UserExportRequest safeRequest = request != null ? request : new UserExportRequest();
        int effectiveSize = Math.min(Math.max(1, pageSize), 1000);
        Page<SysUser> pageRequest = new Page<>(Math.max(1, pageNum), effectiveSize);

        List<Long> deptSubtreeIds = CollectionUtils.isEmpty(safeRequest.getIds())
                ? resolveDeptSubtreeIds(tenantId, safeRequest.getDeptId())
                : Collections.emptyList();
        UserQueryCriteria criteria = new UserQueryCriteria(
                tenantId,
                SystemContextSupport.trimToNull(safeRequest.getUsername()),
                ContactFieldCrypto.encryptPhoneForQuery(safeRequest.getPhone()),
                safeRequest.getStatus(),
                deptSubtreeIds,
                safeRequest.getIds(),
                safeRequest.getBeginTime(),
                safeRequest.getEndTime(),
                true
        );

        Page<SysUser> resultPage = TenantContextHolder.runWithTenant(
                tenantId,
                () -> sysUserMapper.selectUserPageWithDataScope(pageRequest, criteria)
        );
        List<SysUser> records = resultPage.getRecords();
        if (CollectionUtils.isEmpty(records)) {
            return SystemContextSupport.toPageResult(resultPage, user -> UserExportVO.from(user, null, genderCodeToLabel));
        }

        Map<Long, String> deptNameMap = batchLoadDeptNames(tenantId, records);
        return SystemContextSupport.toPageResult(resultPage, user -> UserExportVO.from(
                user,
                user.getDeptId() != null ? deptNameMap.get(user.getDeptId()) : null,
                genderCodeToLabel
        ));
    }

    private UserVO toUserVO(SysUser user, String deptName, boolean builtinSuperAdmin) {
        return UserVO.builder()
                .id(user.getId())
                .tenantId(user.getTenantId())
                .deptId(user.getDeptId())
                .deptName(deptName)
                .username(user.getUsername())
                .nickname(user.getNickname())
                .email(user.getEmail())
                .phone(user.getPhone())
                .gender(user.getGender())
                .avatar(avatarUrlResolver.resolve(user.getAvatar()))
                .status(user.getStatus())
                .loginIp(user.getLoginIp())
                .loginDate(user.getLoginDate())
                .pwdUpdateTime(user.getPwdUpdateTime())
                .remark(user.getRemark())
                .createTime(user.getCreateTime())
                .builtinSuperAdmin(builtinSuperAdmin)
                .build();
    }
}

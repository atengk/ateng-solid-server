package io.github.atengk.solid.system.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.mybatisplus.annotation.DataScope;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.mybatisplus.permission.DataPermissionHandler;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.api.enums.DataScopeType;
import io.github.atengk.solid.system.entity.SysDept;
import io.github.atengk.solid.system.entity.SysRole;
import io.github.atengk.solid.system.entity.SysRoleDept;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysRoleDeptMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import io.github.atengk.solid.system.properties.SolidSystemProperties;
import io.github.atengk.solid.system.support.SystemContextSupport;
import lombok.RequiredArgsConstructor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 基于角色 5 级数据范围（ALL/CUSTOM/DEPT/DEPT_AND_CHILD/SELF）的细粒度数据权限处理器。
 * <p>
 * 通过 {@link ObjectProvider} 延迟解析持久层 Mapper，打破 {@code SqlSessionFactory -> MybatisPlusInterceptor ->
 * DataPermissionHandler -> Mapper} 启动期循环依赖；内部查询统一包裹在
 * {@link DataPermissionContextHolder#runWithoutPermission} 中防止拦截器自递归，无有效角色或无部门时严格 Fail-Closed 注入 {@code 1 = 0}。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Component
@RequiredArgsConstructor
public class SolidDataPermissionHandler implements DataPermissionHandler {

    private static final int DEFAULT_MAX_DEPT_IN_SIZE = 1000;
    private static final String DEFAULT_DEPT_COLUMN = "dept_id";
    private static final String DEFAULT_USER_COLUMN = "user_id";
    private static final String CREATE_BY_COLUMN = "create_by";

    private final ObjectProvider<SysUserMapper> sysUserMapperProvider;
    private final ObjectProvider<SysUserRoleMapper> sysUserRoleMapperProvider;
    private final ObjectProvider<SysRoleDeptMapper> sysRoleDeptMapperProvider;
    private final ObjectProvider<SysDeptMapper> sysDeptMapperProvider;
    private final SolidSystemProperties solidSystemProperties;

    @Override
    public Expression getSqlSegment(Table table, Expression where, String mappedStatementId, DataScope dataScope) {
        if (dataScope == null || dataScope.ignore()
                || DataPermissionContextHolder.isIgnore()
                || DataPermissionContextHolder.isSuperAdmin()) {
            return null;
        }

        Long userId = resolveCurrentUserId();
        Long tenantId = TenantContextHolder.getTenantIdAsLong();
        if (userId == null || tenantId == null) {
            return denyAll();
        }

        // 1. 在关闭数据权限且锁定当前工作租户的隔离上下文内解析角色与可见部门范围，防自拦截递归
        ScopeResolution resolution = resolveActorScope(tenantId, userId);
        if (resolution.allowAll()) {
            return null;
        }
        if (resolution.visibleDeptIds().isEmpty() && !resolution.includeSelf()) {
            return denyAll();
        }

        // 2. 依据目标表别名及列配置生成 AST，部门与本人条件并存时显式加括号防优先级穿透
        return buildScopeExpression(table, dataScope, userId, resolution);
    }

    /**
     * 解析指定操作人在目标租户下的 5 级数据权限生效范围快照（供写路径、权限子集校验与批量导入复用）。
     * <p>
     * 仅平台超级管理员返回 {@link ScopeResolution#ALLOW_ALL}；不受外层 {@link DataPermissionContextHolder#runWithoutPermission}
     * 查询屏蔽上下文误触发为全量权限。
     * </p>
     *
     * @param tenantId 目标租户主键
     * @param userId   操作人用户主键
     * @return 只读数据权限范围快照
     */
    public ScopeResolution resolveActorScope(Long tenantId, Long userId) {
        if (tenantId == null || userId == null) {
            return ScopeResolution.EMPTY;
        }
        if (DataPermissionContextHolder.isSuperAdmin()) {
            return ScopeResolution.ALLOW_ALL;
        }
        return DataPermissionContextHolder.runWithoutPermission(
                () -> TenantContextHolder.runWithTenant(tenantId, () -> resolveScope(tenantId, userId))
        );
    }

    private ScopeResolution resolveScope(Long tenantId, Long userId) {
        SysUserRoleMapper userRoleMapper = sysUserRoleMapperProvider.getIfAvailable();
        if (userRoleMapper == null) {
            return ScopeResolution.EMPTY;
        }
        List<SysRole> activeRoles = userRoleMapper.selectActiveRolesByUserId(tenantId, userId);
        if (CollectionUtils.isEmpty(activeRoles)) {
            return ScopeResolution.EMPTY;
        }

        ScopeAccumulator accumulator = collectRoleScopes(activeRoles);
        if (accumulator.allowAll()) {
            return ScopeResolution.ALLOW_ALL;
        }

        Set<Long> visibleDeptIds = resolveVisibleDeptIds(tenantId, userId, accumulator);
        enforceMaxDeptLimit(visibleDeptIds.size());
        return new ScopeResolution(false, Collections.unmodifiableSet(visibleDeptIds), accumulator.includeSelf());
    }

    private ScopeAccumulator collectRoleScopes(List<SysRole> activeRoles) {
        List<Long> customRoleIds = new ArrayList<>();
        boolean needDept = false;
        boolean needDeptAndChild = false;
        boolean includeSelf = false;

        for (SysRole role : activeRoles) {
            DataScopeType scopeType = role != null ? DataScopeType.fromCode(role.getDataScope()) : null;
            if (scopeType == null) {
                continue;
            }
            switch (scopeType) {
                case ALL -> {
                    return ScopeAccumulator.ALL;
                }
                case CUSTOM -> {
                    if (role.getId() != null) {
                        customRoleIds.add(role.getId());
                    }
                }
                case DEPT -> needDept = true;
                case DEPT_AND_CHILD -> needDeptAndChild = true;
                case SELF -> includeSelf = true;
            }
        }
        return new ScopeAccumulator(false, customRoleIds, needDept, needDeptAndChild, includeSelf);
    }

    private Set<Long> resolveVisibleDeptIds(Long tenantId, Long userId, ScopeAccumulator accumulator) {
        Set<Long> visibleDeptIds = new LinkedHashSet<>();
        if (!accumulator.customRoleIds().isEmpty()) {
            collectCustomDeptIds(tenantId, accumulator.customRoleIds(), visibleDeptIds);
        }
        if (accumulator.needDept() || accumulator.needDeptAndChild()) {
            Long currentDeptId = loadCurrentDeptId(tenantId, userId);
            if (currentDeptId != null) {
                if (accumulator.needDept()) {
                    visibleDeptIds.add(currentDeptId);
                }
                if (accumulator.needDeptAndChild()) {
                    collectDeptAndChildIds(tenantId, currentDeptId, visibleDeptIds);
                }
            }
        }
        return visibleDeptIds;
    }

    private record ScopeAccumulator(
            boolean allowAll,
            List<Long> customRoleIds,
            boolean needDept,
            boolean needDeptAndChild,
            boolean includeSelf
    ) {
        private static final ScopeAccumulator ALL = new ScopeAccumulator(true, List.of(), false, false, false);
    }

    private void collectCustomDeptIds(Long tenantId, List<Long> customRoleIds, Set<Long> collector) {
        SysRoleDeptMapper roleDeptMapper = sysRoleDeptMapperProvider.getIfAvailable();
        if (roleDeptMapper == null) {
            return;
        }
        List<SysRoleDept> roleDepts = roleDeptMapper.selectList(
                Wrappers.<SysRoleDept>lambdaQuery()
                        .select(SysRoleDept::getDeptId)
                        .eq(SysRoleDept::getTenantId, tenantId)
                        .in(SysRoleDept::getRoleId, customRoleIds)
        );
        if (CollectionUtils.isEmpty(roleDepts)) {
            return;
        }
        for (SysRoleDept roleDept : roleDepts) {
            if (roleDept != null && roleDept.getDeptId() != null) {
                collector.add(roleDept.getDeptId());
            }
        }
    }

    private Long loadCurrentDeptId(Long tenantId, Long userId) {
        SysUserMapper userMapper = sysUserMapperProvider.getIfAvailable();
        if (userMapper == null) {
            return null;
        }
        SysUser user = userMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery()
                        .select(SysUser::getDeptId)
                        .eq(SysUser::getTenantId, tenantId)
                        .eq(SysUser::getId, userId)
        );
        return user != null ? user.getDeptId() : null;
    }

    private void collectDeptAndChildIds(Long tenantId, Long currentDeptId, Set<Long> collector) {
        SysDeptMapper deptMapper = sysDeptMapperProvider.getIfAvailable();
        if (deptMapper == null) {
            return;
        }
        SysDept currentDept = deptMapper.selectOne(
                Wrappers.<SysDept>lambdaQuery()
                        .select(SysDept::getId, SysDept::getAncestors)
                        .eq(SysDept::getTenantId, tenantId)
                        .eq(SysDept::getId, currentDeptId)
        );
        if (currentDept == null) {
            return;
        }
        collector.add(currentDeptId);
        // 使用精确 ancestors 前缀条件命中 idx_dept_tenant_ancestors，禁用 FIND_IN_SET
        String prefix = StringUtils.hasText(currentDept.getAncestors())
                ? currentDept.getAncestors().trim() + "," + currentDeptId
                : String.valueOf(currentDeptId);
        List<SysDept> treeDepts = deptMapper.selectList(
                Wrappers.<SysDept>lambdaQuery()
                        .select(SysDept::getId)
                        .eq(SysDept::getTenantId, tenantId)
                        .and(w -> w.eq(SysDept::getAncestors, prefix)
                                .or(nested -> SystemContextSupport.applyPrefixLike(nested, SysDept::getAncestors, prefix + ",")))
        );
        if (CollectionUtils.isEmpty(treeDepts)) {
            return;
        }
        for (SysDept dept : treeDepts) {
            if (dept != null && dept.getId() != null) {
                collector.add(dept.getId());
            }
        }
    }

    private void enforceMaxDeptLimit(int deptCount) {
        int maxDeptInSize = resolveMaxDeptInSize();
        if (deptCount > maxDeptInSize) {
            throw new BusinessException(
                    CommonBizResultCode.DATA_SCOPE_ERROR,
                    "可见部门数量超出安全上限 (" + maxDeptInSize + ")"
            );
        }
    }

    private int resolveMaxDeptInSize() {
        if (solidSystemProperties == null || solidSystemProperties.getDataScope() == null) {
            return DEFAULT_MAX_DEPT_IN_SIZE;
        }
        int configured = solidSystemProperties.getDataScope().getMaxDeptInSize();
        return configured > 0 ? configured : DEFAULT_MAX_DEPT_IN_SIZE;
    }

    private Expression buildScopeExpression(
            Table table,
            DataScope dataScope,
            Long userId,
            ScopeResolution resolution
    ) {
        String columnPrefix = (table != null && table.getAlias() != null
                && StringUtils.hasText(table.getAlias().getName()))
                ? table.getAlias().getName().trim() + "."
                : "";
        String deptColumn = StringUtils.hasText(dataScope.deptColumn())
                ? dataScope.deptColumn().trim()
                : DEFAULT_DEPT_COLUMN;
        String userColumn = StringUtils.hasText(dataScope.userColumn())
                ? dataScope.userColumn().trim()
                : DEFAULT_USER_COLUMN;

        Expression deptExpr = buildDeptExpression(columnPrefix + deptColumn, resolution.visibleDeptIds());
        Expression selfExpr = resolution.includeSelf()
                ? buildSelfExpression(columnPrefix, userColumn, userId)
                : null;

        if (deptExpr != null && selfExpr != null) {
            return new ParenthesedExpressionList<>(new OrExpression(deptExpr, selfExpr));
        }
        return deptExpr != null ? deptExpr : selfExpr;
    }

    private static Expression buildDeptExpression(String qualifiedDeptColumn, Set<Long> visibleDeptIds) {
        if (visibleDeptIds.isEmpty()) {
            return null;
        }
        Column column = new Column(qualifiedDeptColumn);
        if (visibleDeptIds.size() == 1) {
            return new EqualsTo(column, new LongValue(visibleDeptIds.iterator().next()));
        }
        List<LongValue> values = visibleDeptIds.stream()
                .map(LongValue::new)
                .toList();
        return new InExpression(column, new ParenthesedExpressionList<>(values));
    }

    private static Expression buildSelfExpression(String columnPrefix, String userColumn, Long userId) {
        Column column = new Column(columnPrefix + userColumn);
        Expression value = CREATE_BY_COLUMN.equalsIgnoreCase(userColumn)
                ? new StringValue(String.valueOf(userId))
                : new LongValue(userId);
        return new EqualsTo(column, value);
    }

    private static Long resolveCurrentUserId() {
        try {
            return UserContextHolder.getUserIdAsLong();
        } catch (IllegalStateException ex) {
            return null;
        }
    }

    private static EqualsTo denyAll() {
        return new EqualsTo(new LongValue(1L), new LongValue(0L));
    }

    /**
     * 操作人数据权限生效范围快照。
     *
     * @param allowAll       是否具备全量数据权限（含 {@code ALL} 角色与超级管理员）
     * @param visibleDeptIds 数据权限范围内可见的部门主键集合
     * @param includeSelf    是否具备本人数据权限
     */
    public record ScopeResolution(boolean allowAll, Set<Long> visibleDeptIds, boolean includeSelf) {
        public static final ScopeResolution ALLOW_ALL =
                new ScopeResolution(true, Collections.emptySet(), false);
        public static final ScopeResolution EMPTY =
                new ScopeResolution(false, Collections.emptySet(), false);

        /**
         * 判断指定归属部门是否落入当前操作人的部门可见范围内。
         *
         * @param deptId 目标部门主键
         * @return 具备全部权限或部门落入可见集合时返回 {@code true}
         */
        public boolean isDeptAllowed(Long deptId) {
            return allowAll || (deptId != null && visibleDeptIds != null && visibleDeptIds.contains(deptId));
        }
    }
}

package io.github.atengk.solid.system.support;

import cn.dev33.satoken.SaManager;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.LambdaUtils;
import com.baomidou.mybatisplus.core.toolkit.support.ColumnCache;
import com.baomidou.mybatisplus.core.toolkit.support.LambdaMeta;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.satoken.util.LoginHelper;
import io.github.atengk.solid.common.constant.SolidConstants;
import io.github.atengk.solid.common.enums.CommonStatusEnum;
import io.github.atengk.solid.common.properties.SolidTenantProperties;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.property.PropertyNamer;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 系统管理域共享上下文与基础校验协作者，统一收口工作租户解析、平台租户强校验、路径主键解析与状态边界检查。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemContextSupport {

    /**
     * 常规管理分页查询单页安全上限
     */
    public static final long DEFAULT_MAX_PAGE_SIZE = 100L;

    /**
     * 路径批量操作逗号分隔主键数量硬上限
     */
    public static final int MAX_PATH_ID_COUNT = 100;

    /**
     * SQL LIKE 查询跨数据库统一转义符（同时兼容 MySQL 与 H2 MODE=MySQL）
     */
    public static final char LIKE_ESCAPE_CHAR = '!';

    private static final String SAFE_ERROR_PLACEHOLDER = "系统内部处理异常，详情请检索服务端日志";
    private static final int MAX_AUDIT_ERROR_MSG_LEN = 2000;
    private static final Pattern STACK_OR_TECH_EXCEPTION_PATTERN = Pattern.compile(
            "(?is)(\\r?\\n\\s*at\\s+|Caused\\s+by:|org\\.springframework\\.|com\\.baomidou\\.|org\\.apache\\.ibatis\\.|org\\.mybatis\\.|java\\.lang\\.|java\\.sql\\.|org\\.h2\\.|com\\.mysql\\.|###\\s*(Error|SQL:)|SQLSyntaxError|BadSqlGrammar|SQLException)"
    );
    private static final Pattern RAW_SQL_STATEMENT_PATTERN = Pattern.compile(
            "(?is)\\b(SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|TRUNCATE)\\b[\\s\\S]{0,120}\\b(FROM|INTO|SET|TABLE|WHERE|VALUES)\\b"
    );

    private final SolidTenantProperties tenantProperties;

    /**
     * 判断系统当前是否开启多租户模式。
     *
     * @return 开启返回 {@code true}，单租户默认模式返回 {@code false}
     */
    public boolean isMultiTenantEnabled() {
        return tenantProperties != null && tenantProperties.isEnabled();
    }

    /**
     * 获取当前请求生效的工作租户主键；单租户模式未显式绑定上下文时安全回退默认租户 {@code 0L}，多租户模式缺失时抛出 {@code 401}。
     *
     * @return 当前工作租户主键
     * @throws BusinessException 多租户模式下缺失工作租户上下文时抛出 {@code 401}
     */
    public Long requireEffectiveTenantId() {
        Long tenantId = TenantContextHolder.getTenantIdAsLong();
        if (tenantId != null) {
            return tenantId;
        }
        if (!isMultiTenantEnabled()) {
            return SolidConstants.PLATFORM_TENANT_ID;
        }
        throw new BusinessException(CommonResultCode.UNAUTHORIZED, "未识别当前工作租户上下文");
    }

    /**
     * 校验并返回当前生效租户为平台默认租户 {@code 0L}；非平台视角统一抛出 {@code 403}。
     *
     * @return 平台默认租户主键 {@code 0L}
     * @throws BusinessException 当前生效租户非平台默认租户时抛出 {@code 403}
     */
    public Long requirePlatformTenantId() {
        Long tenantId = requireEffectiveTenantId();
        if (!Objects.equals(tenantId, SolidConstants.PLATFORM_TENANT_ID)) {
            throw new BusinessException(CommonResultCode.FORBIDDEN, "仅平台默认租户允许维护该系统级资源");
        }
        return tenantId;
    }

    /**
     * 校验系统已开启多租户模式且当前生效租户为平台默认租户 {@code 0L}；未开启或切至普通租户时统一抛出 {@code 403}。
     *
     * @return 平台默认租户主键 {@code 0L}
     * @throws BusinessException 未开启多租户模式或非平台默认租户视角时抛出 {@code 403}
     */
    public Long requireMultiTenantPlatformAccess() {
        if (!isMultiTenantEnabled()) {
            throw new BusinessException(CommonResultCode.FORBIDDEN, "当前系统未开启多租户模式");
        }
        return requirePlatformTenantId();
    }

    /**
     * 校验平台超管切换租户选项查询权限（要求开启多租户且当前会话归属平台租户 {@code 0} 并具备 {@code super_admin} 角色）。
     *
     * @throws BusinessException 未开启多租户或非平台超管身份时抛出 {@code 403}
     */
    public void requirePlatformSuperAdminSwitchAccess() {
        if (!isMultiTenantEnabled()) {
            throw new BusinessException(CommonResultCode.FORBIDDEN, "当前系统未开启多租户模式");
        }
        if (SaManager.getSaTokenContext().isValid()) {
            String ownerTenantId = LoginHelper.getTenantId(SolidConstants.ADMIN_LOGIN_TYPE);
            boolean superAdmin = LoginHelper.isSuperAdmin(SolidConstants.ADMIN_LOGIN_TYPE);
            if (!Objects.equals(SolidConstants.PLATFORM_TENANT_ID_STR, ownerTenantId) || !superAdmin) {
                throw new BusinessException(CommonResultCode.FORBIDDEN, "仅平台超级管理员允许查询租户切换选项");
            }
            return;
        }
        requirePlatformTenantId();
    }

    /**
     * 解析路径传入的逗号分隔雪花主键串并去重。
     *
     * @param ids 逗号分隔的主键列表字符串（如 {@code "1,2,3"}）
     * @return 去重后的有效主键列表
     * @throws BusinessException 字符串为空或包含非法/非正主键值时抛出 {@code 400}
     */
    public static List<Long> parsePathIds(String ids) {
        return parsePathIds(ids, false);
    }

    /**
     * 解析路径传入的逗号分隔主键串并去重，支持显式控制是否允许平台保留主键 {@code 0}（用于触发 {@code 102003} 语义拦截）。
     *
     * @param ids       逗号分隔的主键列表字符串
     * @param allowZero 是否允许解析主键 {@code 0}
     * @return 去重后的有效主键列表
     */
    public static List<Long> parsePathIds(String ids, boolean allowZero) {
        if (!StringUtils.hasText(ids)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "目标主键标识列表不能为空");
        }
        String[] segments = ids.split(",");
        if (segments.length > MAX_PATH_ID_COUNT) {
            throw new BusinessException(
                    CommonBizResultCode.PARAM_INVALID,
                    "单次批量操作主键数量不能超过 " + MAX_PATH_ID_COUNT + " 个"
            );
        }
        Set<Long> parsed = new LinkedHashSet<>(segments.length);
        for (String segment : segments) {
            if (!StringUtils.hasText(segment)) {
                throw new BusinessException(CommonResultCode.BAD_REQUEST, "目标主键标识格式无效");
            }
            long value;
            try {
                value = Long.parseLong(segment.trim());
            } catch (NumberFormatException ex) {
                throw new BusinessException(CommonResultCode.BAD_REQUEST, "目标主键标识必须为有效数字", ex);
            }
            if (allowZero ? value < 0L : value <= 0L) {
                throw new BusinessException(CommonResultCode.BAD_REQUEST, "目标主键标识格式不合法");
            }
            parsed.add(value);
        }
        return new ArrayList<>(parsed);
    }

    /**
     * 校验查询或导出时间起止边界，防止起始时间晚于截止时间。
     *
     * @param beginTime 起始时间（可为空）
     * @param endTime   截止时间（可为空）
     */
    public static void assertValidTimeRange(LocalDateTime beginTime, LocalDateTime endTime) {
        if (beginTime != null && endTime != null && beginTime.isAfter(endTime)) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "创建时间起止范围不合法");
        }
    }

    /**
     * 对 SQL {@code LIKE} 检索模式字符串执行通配符转义（空白串归 {@code null}，并对 {@code !}、{@code %}、{@code _} 追加 {@code !} 转义前缀）。
     *
     * @param raw 原始检索关键字
     * @return 可安全拼接通配符的转义串；入参为空或纯空白时返回 {@code null}
     */
    public static String escapeLikePattern(String raw) {
        String trimmed = trimToNull(raw);
        if (trimmed == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(trimmed.length() + 4);
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch == LIKE_ESCAPE_CHAR || ch == '%' || ch == '_') {
                sb.append(LIKE_ESCAPE_CHAR);
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    /**
     * 为 MyBatis-Plus {@link LambdaQueryWrapper} 追加安全包含匹配（{@code column LIKE '%escaped%' ESCAPE '!'}）。
     *
     * @param wrapper  查询包装器
     * @param column   实体列 Lambda 引用
     * @param rawValue 原始模糊关键字（为空或空白时不追加条件）
     * @param <T>      实体类型
     */
    public static <T> void applyContainsLike(
            LambdaQueryWrapper<T> wrapper,
            SFunction<T, ?> column,
            String rawValue
    ) {
        String escaped = escapeLikePattern(rawValue);
        if (wrapper == null || column == null || escaped == null) {
            return;
        }
        String columnName = resolveColumnName(column);
        wrapper.apply(columnName + " LIKE {0} ESCAPE '" + LIKE_ESCAPE_CHAR + "'", "%" + escaped + "%");
    }

    /**
     * 为 MyBatis-Plus {@link LambdaQueryWrapper} 追加安全右前缀匹配（{@code column LIKE 'escaped%' ESCAPE '!'}）。
     *
     * @param wrapper   查询包装器
     * @param column    实体列 Lambda 引用
     * @param rawPrefix 原始前缀字符串（为空或空白时不追加条件）
     * @param <T>       实体类型
     */
    public static <T> void applyPrefixLike(
            LambdaQueryWrapper<T> wrapper,
            SFunction<T, ?> column,
            String rawPrefix
    ) {
        String escaped = escapeLikePattern(rawPrefix);
        if (wrapper == null || column == null || escaped == null) {
            return;
        }
        String columnName = resolveColumnName(column);
        wrapper.apply(columnName + " LIKE {0} ESCAPE '" + LIKE_ESCAPE_CHAR + "'", escaped + "%");
    }

    private static <T> String resolveColumnName(SFunction<T, ?> column) {
        LambdaMeta meta = LambdaUtils.extract(column);
        String fieldName = PropertyNamer.methodToProperty(meta.getImplMethodName());
        Map<String, ColumnCache> columnMap = LambdaUtils.getColumnMap(meta.getInstantiatedClass());
        ColumnCache columnCache = columnMap != null ? columnMap.get(LambdaUtils.formatKey(fieldName)) : null;
        if (columnCache == null) {
            throw new IllegalArgumentException(
                    "无法解析实体数据库列名: " + meta.getInstantiatedClass().getName() + "#" + fieldName
            );
        }
        return columnCache.getColumn();
    }

    /**
     * 校验并规范化启停状态值，空值按默认正常态 {@code 0} 处理。
     *
     * @param status 输入状态值
     * @return 规范化状态（{@code 0} 或 {@code 1}）
     * @throws BusinessException 状态值非 {@code 0}/{@code 1} 时抛出 {@code 100001}
     */
    public static Integer normalizeStatus(Integer status) {
        if (status == null) {
            return CommonStatusEnum.NORMAL.getCode();
        }
        if (!CommonStatusEnum.NORMAL.getCode().equals(status)
                && !CommonStatusEnum.DISABLED.getCode().equals(status)) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "状态值仅允许为 0（正常）或 1（停用）");
        }
        return status;
    }

    /**
     * 校验并返回严格非空的状态值（专供状态切换接口使用）。
     *
     * @param status 目标状态值
     * @return 已校验的状态值（{@code 0} 或 {@code 1}）
     * @throws BusinessException 为空或非合法状态值时抛出 {@code 100001}
     */
    public static Integer requireValidStatus(Integer status) {
        if (status == null) {
            throw new BusinessException(CommonBizResultCode.PARAM_INVALID, "状态参数不能为空");
        }
        return normalizeStatus(status);
    }

    /**
     * 去除字符串首尾空白，空串或纯空白统一转换为 {@code null}。
     *
     * @param value 原始字符串
     * @return 裁剪后字符串或 {@code null}
     */
    public static String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    /**
     * 去除字符串首尾空白，并在超过指定长度时进行截断。
     *
     * @param value     原始字符串
     * @param maxLength 最大允许长度
     * @return 裁剪截断后的字符串或 {@code null}
     */
    public static String truncate(String value, int maxLength) {
        String trimmed = trimToNull(value);
        if (trimmed == null || maxLength <= 0) {
            return null;
        }
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    /**
     * 脱敏审计错误摘要，严禁将原始 Java 堆栈或 SQL 语句落库及透传至管理端接口。
     *
     * @param rawErrorMsg 原始异常摘要
     * @return 安全处理后的错误摘要（为空时返回 {@code null}）
     */
    public static String sanitizeAuditErrorMsg(String rawErrorMsg) {
        String trimmed = trimToNull(rawErrorMsg);
        if (trimmed == null) {
            return null;
        }
        if (STACK_OR_TECH_EXCEPTION_PATTERN.matcher(trimmed).find()
                || RAW_SQL_STATEMENT_PATTERN.matcher(trimmed).find()) {
            return SAFE_ERROR_PLACEHOLDER;
        }
        return truncate(trimmed, MAX_AUDIT_ERROR_MSG_LEN);
    }

    /**
     * 将 MyBatis-Plus 分页结果统一转换为标准 {@link PageResult}，空页保底返回不可变空集合。
     *
     * @param page   持久层分页结果
     * @param mapper 实体转视图对象映射函数
     * @param <E>    持久层实体类型
     * @param <V>    视图对象类型
     * @return 标准分页结果
     */
    public static <E, V> PageResult<V> toPageResult(Page<E> page, Function<E, V> mapper) {
        if (page == null) {
            return PageResult.empty();
        }
        List<E> source = page.getRecords();
        List<V> mapped = CollectionUtils.isEmpty(source)
                ? Collections.emptyList()
                : source.stream().map(mapper).toList();
        return PageResult.of(mapped, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    /**
     * 安全解析当前操作人用户主键（优先读取 {@link UserContextHolder#getExplicit()}，再在 Sa-Token 上下文有效时读取登录态）。
     *
     * @return 当前操作人用户主键；无有效登录态时返回 {@code null}
     */
    public Long resolveCurrentActorUserId() {
        String explicitUserId = UserContextHolder.getExplicit();
        if (StringUtils.hasText(explicitUserId)) {
            try {
                return Long.parseLong(explicitUserId.trim());
            } catch (NumberFormatException ex) {
                log.debug("忽略无法解析为长整型的 ThreadLocal 用户上下文值: {}", explicitUserId, ex);
                return null;
            }
        }
        if (SaManager.getSaTokenContext().isValid()) {
            try {
                return LoginHelper.getUserIdAsLong(SolidConstants.ADMIN_LOGIN_TYPE);
            } catch (RuntimeException ex) {
                log.debug("当前 Sa-Token 上下文未绑定有效管理员会话: {}", ex.getMessage());
                return null;
            }
        }
        return null;
    }

    /**
     * 强制解析当前登录操作人用户主键，未登录时统一抛出 {@code 401}。
     *
     * @return 当前登录用户主键
     * @throws BusinessException 当前请求未登录时抛出 {@code 401}
     */
    public Long requireCurrentActorUserId() {
        Long userId = resolveCurrentActorUserId();
        if (userId == null) {
            throw new BusinessException(CommonResultCode.UNAUTHORIZED, "未识别当前登录操作人身份");
        }
        return userId;
    }

    /**
     * 空安全列表兜底，避免返回 {@code null}。
     *
     * @param values 原始列表
     * @param <T>    元素类型
     * @return 原列表或不可变空列表
     */
    public static <T> List<T> safeList(List<T> values) {
        return CollectionUtils.isEmpty(values) ? Collections.emptyList() : values;
    }

    /**
     * 对主键集合执行去重与正整数校验（空集合返回不可变空列表，任一项非正抛出 {@code 100001}）。
     *
     * @param rawIds     原始主键集合
     * @param invalidMsg 含无效主键时的提示信息
     * @return 去重且保序的正整数主键列表
     */
    public static List<Long> deduplicatePositiveIds(Collection<Long> rawIds, String invalidMsg) {
        if (CollectionUtils.isEmpty(rawIds)) {
            return Collections.emptyList();
        }
        Set<Long> unique = new LinkedHashSet<>(rawIds.size());
        for (Long id : rawIds) {
            if (id == null || id <= 0L) {
                throw new BusinessException(CommonBizResultCode.PARAM_INVALID, invalidMsg);
            }
            unique.add(id);
        }
        return new ArrayList<>(unique);
    }
}

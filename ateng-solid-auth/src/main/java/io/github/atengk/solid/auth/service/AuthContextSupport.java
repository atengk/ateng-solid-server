package io.github.atengk.solid.auth.service;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.satoken.util.LoginHelper;
import io.github.atengk.solid.common.constant.SolidConstants;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

/**
 * 认证域会话上下文与集合安全辅助工具，统一收敛当前登录用户、归属租户、生效租户解析及空安全集合转换口径。
 *
 * @author Ateng
 * @since 2026-11-25
 */
final class AuthContextSupport {

    private AuthContextSupport() {
    }

    /**
     * 获取当前管理端登录态会话归属租户主键（平台租户或普通租户原所属租户）。
     *
     * @return 归属租户主键
     * @throws BusinessException 未登录或会话租户上下文损坏时抛出 {@code 401}
     */
    static Long requireOwnerTenantId() {
        String rawTenantId = LoginHelper.getTenantId(SolidConstants.ADMIN_LOGIN_TYPE);
        if (!StringUtils.hasText(rawTenantId)) {
            throw new BusinessException(CommonResultCode.UNAUTHORIZED, "未登录或会话租户上下文无效");
        }
        Long tenantId = parseLongOrNull(rawTenantId);
        if (tenantId == null) {
            throw new BusinessException(CommonResultCode.UNAUTHORIZED, "会话租户标识格式无效");
        }
        return tenantId;
    }

    /**
     * 解析当前请求的工作生效租户主键（优先取超管跨租户切换后的上下文租户，否则回退会话归属租户）。
     *
     * @param ownerTenantId 会话归属租户主键
     * @return 当前请求生效租户主键
     */
    static Long resolveEffectiveTenantId(Long ownerTenantId) {
        Long contextTenantId = TenantContextHolder.getTenantIdAsLong();
        return contextTenantId != null ? contextTenantId : ownerTenantId;
    }

    /**
     * 获取当前生效租户标识字符串（供在线会话按工作租户隔离查询与句柄签密使用）。
     *
     * @return 生效租户字符串
     * @throws BusinessException 未登录或无法解析租户时抛出 {@code 401}
     */
    static String resolveEffectiveTenantIdString() {
        String explicit = TenantContextHolder.getExplicit();
        if (StringUtils.hasText(explicit)) {
            return explicit.trim();
        }
        return String.valueOf(requireOwnerTenantId());
    }

    /**
     * 获取当前登录管理端用户主键。
     *
     * @return 当前用户主键
     * @throws BusinessException 未登录或无法识别用户标识时抛出 {@code 401}
     */
    static Long requireCurrentUserId() {
        Long userId = LoginHelper.getUserIdAsLong(SolidConstants.ADMIN_LOGIN_TYPE);
        if (userId == null) {
            throw new BusinessException(CommonResultCode.UNAUTHORIZED, "未登录或会话已失效");
        }
        return userId;
    }

    /**
     * 安全解析正负长整型字符串。
     *
     * @param value 待解析字符串
     * @return 解析结果；为空白或非法数字时返回 {@code null}
     */
    static Long parseLongOrNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 兜底转换空集合。
     *
     * @param values 原始列表
     * @param <T>    元素类型
     * @return 非空不可变/安全列表
     */
    static <T> List<T> safeList(List<T> values) {
        return CollectionUtils.isEmpty(values) ? Collections.emptyList() : values;
    }

    /**
     * 清洗字符串列表中的空白项并去重，无匹配时返回空集合。
     *
     * @param values 原始字符串列表
     * @return 去重后的有效字符串列表
     */
    static List<String> safeDistinctList(List<String> values) {
        if (CollectionUtils.isEmpty(values)) {
            return Collections.emptyList();
        }
        List<String> filtered = values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        return filtered.isEmpty() ? Collections.emptyList() : filtered;
    }
}

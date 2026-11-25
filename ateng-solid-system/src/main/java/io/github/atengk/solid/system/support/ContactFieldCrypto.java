package io.github.atengk.solid.system.support;

import io.github.atengk.boot.mybatisplus.handler.EncryptTypeHandler;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * 联系字段确定性加密与查询归一化无状态工具类。
 * <p>
 * 统一收口手机号、电子邮箱的空白归一化（防空串触发联合唯一索引冲突）、邮箱小写归一化及
 * {@link EncryptTypeHandler#encryptDeterministic(String)} 确定性加密逻辑：
 * <ul>
 *   <li>实体落库（{@code insert}/{@code updateById}/{@code BatchUtil.insertBatch}）与
 *       MyBatis-Plus 3.5.17 {@code LambdaQueryWrapper}（自动注入列 {@code TypeHandler}）统一传入
 *       {@link #normalizePhone(String)} / {@link #normalizeEmail(String)}；</li>
 *   <li>{@code QueryWrapper}（字符串列名）、{@code @Select}/XML 原生 SQL 与
 *       {@code LambdaUpdateWrapper#set}（无自动 {@code TypeHandler}）统一传入
 *       {@link #encryptPhoneForQuery(String)} / {@link #encryptEmailForQuery(String)} 及其批量方法；</li>
 *   <li>严禁对确定性加密列发起 {@code LIKE} 模糊检索或 {@code ORDER BY} 排序。</li>
 * </ul>
 *
 * @author Ateng
 * @since 2026-11-25
 */
public final class ContactFieldCrypto {

    private ContactFieldCrypto() {
    }

    /**
     * 归一化手机号码：去除首尾空白后若为空白字符串则统一返回 {@code null}，避免空串写入联合唯一索引。
     *
     * @param raw 原始手机号码输入（可为 {@code null}）
     * @return 归一化后的非空手机号字符串；输入为空或纯空白时返回 {@code null}
     */
    public static String normalizePhone(String raw) {
        return trimToNull(raw);
    }

    /**
     * 归一化电子邮箱：去除首尾空白并按 {@link Locale#ROOT} 转为小写，空白字符串统一返回 {@code null}。
     *
     * @param raw 原始电子邮箱输入（可为 {@code null}）
     * @return 归一化后的全小写非空邮箱字符串；输入为空或纯空白时返回 {@code null}
     */
    public static String normalizeEmail(String raw) {
        String trimmed = trimToNull(raw);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    /**
     * 对手机号码执行归一化并生成用于等值检索的确定性密文。
     *
     * @param raw 原始手机号码（可为 {@code null}）
     * @return 带 {@code v1:} 前缀的确定性密文；入参为空或纯空白时返回 {@code null}
     */
    public static String encryptPhoneForQuery(String raw) {
        return encryptForQuery(raw, ContactFieldCrypto::normalizePhone);
    }

    /**
     * 对电子邮箱执行空白与小写归一化并生成用于等值检索的确定性密文。
     *
     * @param raw 原始电子邮箱（可为 {@code null}）
     * @return 带 {@code v1:} 前缀的确定性密文；入参为空或纯空白时返回 {@code null}
     */
    public static String encryptEmailForQuery(String raw) {
        return encryptForQuery(raw, ContactFieldCrypto::normalizeEmail);
    }

    /**
     * 批量归一化、去重并确定性加密手机号集合，专供批量导入 {@code IN} 查重使用。
     *
     * @param raws 原始手机号集合（可为 {@code null} 或包含空白元素）
     * @return 去重后的确定性密文列表；入参为空或无有效非空元素时返回不可变空列表
     */
    public static List<String> encryptPhonesForQuery(Collection<String> raws) {
        return encryptBatchForQuery(raws, ContactFieldCrypto::normalizePhone);
    }

    /**
     * 批量归一化、去重并确定性加密邮箱集合，专供批量导入 {@code IN} 查重使用。
     *
     * @param raws 原始邮箱集合（可为 {@code null} 或包含空白/大小写混排元素）
     * @return 去重后的确定性密文列表；入参为空或无有效非空元素时返回不可变空列表
     */
    public static List<String> encryptEmailsForQuery(Collection<String> raws) {
        return encryptBatchForQuery(raws, ContactFieldCrypto::normalizeEmail);
    }

    private static String encryptForQuery(String raw, UnaryOperator<String> normalizer) {
        String normalized = normalizer.apply(raw);
        return normalized == null ? null : EncryptTypeHandler.encryptDeterministic(normalized);
    }

    private static List<String> encryptBatchForQuery(Collection<String> raws, UnaryOperator<String> normalizer) {
        if (raws == null || raws.isEmpty()) {
            return Collections.emptyList();
        }
        return raws.stream()
                .map(normalizer)
                .filter(Objects::nonNull)
                .distinct()
                .map(EncryptTypeHandler::encryptDeterministic)
                .toList();
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}

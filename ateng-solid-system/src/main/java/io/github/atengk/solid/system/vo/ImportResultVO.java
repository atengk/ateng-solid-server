package io.github.atengk.solid.system.vo;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Excel 批量用户导入汇总响应视图对象。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Value
@Builder
public class ImportResultVO {

    /**
     * 解析到的总数据行数
     */
    int totalCount;

    /**
     * 实际成功落库行数（整批原子导入模式下，存在任一错误时为 {@code 0}）
     */
    int successCount;

    /**
     * 校验失败条目总数
     */
    int failCount;

    /**
     * 错误明细列表（全量通过时返回不可变空列表）
     */
    List<ImportErrorVO> errors;

    /**
     * 标红批注工作簿暂存令牌（有效期 15 分钟且绑定租户与操作人；成功时为 {@code null}）
     */
    String feedbackToken;
}

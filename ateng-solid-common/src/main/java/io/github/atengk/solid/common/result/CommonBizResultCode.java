package io.github.atengk.solid.common.result;

import io.github.atengk.boot.core.code.IResultCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 跨模块共享业务错误码枚举（号段：100000 ~ 100999），承载通用参数约束、跨租户切换校验、数据权限范围、文件安全守卫与 Excel 导入校验失败响应。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Getter
@AllArgsConstructor
public enum CommonBizResultCode implements IResultCode {

    /**
     * 业务参数格式或约束不合法（如树形父节点自环、F 节点挂接错误、自定数据权限未勾选部门等）
     */
    PARAM_INVALID(100001, "业务参数格式或约束不合法"),

    /**
     * 非法租户切换目标（目标租户不存在、已停用或已过期，不注销超管自身会话）
     */
    INVALID_TENANT_SWITCH(100002, "目标租户不存在、已停用或已过期，无法切换"),

    /**
     * 数据权限配置或解析范围异常（如可见子孙部门数量超过系统安全上限）
     */
    DATA_SCOPE_ERROR(100003, "数据权限配置或范围校验异常"),

    /**
     * 上传文件未通过图片魔数校验、扩展名受限或文件体积超出上限
     */
    FILE_GUARD_REJECTED(100004, "上传文件格式不合法或大小超出限制"),

    /**
     * Excel 文件格式非法、表头校验失败或导入数据超出单批允许上限
     */
    EXCEL_VALIDATION_FAILED(100005, "Excel 文件格式、表头或导入行数校验失败");

    /**
     * 业务状态码
     */
    private final Integer code;

    /**
     * 默认错误提示文案
     */
    private final String msg;
}

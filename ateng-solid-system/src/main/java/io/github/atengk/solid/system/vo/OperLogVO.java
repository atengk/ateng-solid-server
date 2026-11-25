package io.github.atengk.solid.system.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.github.atengk.boot.core.jackson.NumericLongSerializer;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * 操作审计日志只读分页与详情响应视图对象。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Value
@Builder
public class OperLogVO {

    /**
     * 操作日志主键
     */
    Long id;

    /**
     * 全链路追踪标识
     */
    String traceId;

    /**
     * 操作人用户主键
     */
    Long userId;

    /**
     * 操作人真实账号或昵称
     */
    String userName;

    /**
     * 操作模块名
     */
    String module;

    /**
     * 操作类型编码（对齐 {@code OperateTypeEnum.code}）
     */
    Integer operateType;

    /**
     * 操作语义描述
     */
    String description;

    /**
     * HTTP 请求方法
     */
    String requestMethod;

    /**
     * HTTP 请求路径
     */
    String requestUrl;

    /**
     * 客户端来源 IP
     */
    String clientIp;

    /**
     * 操作归属地（首期可空）
     */
    String operateLocation;

    /**
     * 解析后的浏览器标识
     */
    String browser;

    /**
     * 解析后的操作系统标识
     */
    String os;

    /**
     * 脱敏后请求参数 JSON
     */
    String requestParams;

    /**
     * 响应摘要 JSON
     */
    String responseResult;

    /**
     * 执行状态（0=成功，1=失败）
     */
    Integer status;

    /**
     * 执行耗时（毫秒）
     */
    @JsonSerialize(using = NumericLongSerializer.class)
    Long costTime;

    /**
     * 脱敏后错误摘要
     */
    String errorMsg;

    /**
     * 扩展 JSON
     */
    String extraJson;

    /**
     * 操作发生时间
     */
    LocalDateTime operateTime;
}

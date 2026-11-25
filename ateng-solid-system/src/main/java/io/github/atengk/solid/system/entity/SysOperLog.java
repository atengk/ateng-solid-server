package io.github.atengk.solid.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseIdEntity;
import io.github.atengk.boot.mybatisplus.entity.TenantAware;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serial;
import java.time.LocalDateTime;

/**
 * 操作审计日志只追加持久层实体（支持行级多租户隔离，无软删与乐观锁）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
@TableName("sys_oper_log")
public class SysOperLog extends BaseIdEntity implements TenantAware<Long> {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属租户主键
     */
    private Long tenantId;

    /**
     * 全链路追踪标识
     */
    private String traceId;

    /**
     * 操作人用户主键
     */
    private Long userId;

    /**
     * 操作人真实账号或昵称
     */
    private String userName;

    /**
     * 操作模块名
     */
    private String module;

    /**
     * 操作类型编码（对齐 {@code OperateTypeEnum.code}）
     */
    private Integer operateType;

    /**
     * 操作语义描述
     */
    private String description;

    /**
     * HTTP 请求方法
     */
    private String requestMethod;

    /**
     * HTTP 请求路径
     */
    private String requestUrl;

    /**
     * 客户端来源 IP
     */
    private String clientIp;

    /**
     * 操作归属地（首期可空）
     */
    private String operateLocation;

    /**
     * 解析后的浏览器标识
     */
    private String browser;

    /**
     * 解析后的操作系统标识
     */
    private String os;

    /**
     * 脱敏后请求参数 JSON
     */
    private String requestParams;

    /**
     * 响应摘要 JSON（失败时为 null）
     */
    private String responseResult;

    /**
     * 执行状态（0=成功，1=失败）
     */
    private Integer status;

    /**
     * 执行耗时（毫秒）
     */
    private Long costTime;

    /**
     * 脱敏后错误摘要
     */
    private String errorMsg;

    /**
     * 扩展 JSON
     */
    private String extraJson;

    /**
     * 操作发生时间
     */
    private LocalDateTime operateTime;
}

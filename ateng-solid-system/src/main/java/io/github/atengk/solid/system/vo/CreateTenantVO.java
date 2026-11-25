package io.github.atengk.solid.system.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 新建租户 4 步原子初始化完成后的登录指引凭据视图对象。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateTenantVO {

    /**
     * 新生成租户雪花主键
     */
    private Long tenantId;

    /**
     * 租户登录业务编码
     */
    private String tenantCode;

    /**
     * 租户名称
     */
    private String tenantName;

    /**
     * 初始租户管理员账号
     */
    private String adminUsername;
}

package io.github.atengk.solid.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseLogicEntity;
import io.github.atengk.boot.mybatisplus.entity.TenantAware;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serial;

/**
 * 岗位信息持久层实体（支持行级多租户隔离）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
@TableName("sys_post")
public class SysPost extends BaseLogicEntity implements TenantAware<Long> {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属租户主键
     */
    private Long tenantId;

    /**
     * 岗位编码（同租户未删除唯一）
     */
    private String postCode;

    /**
     * 岗位名称（同租户未删除唯一）
     */
    private String postName;

    /**
     * 显示排序
     */
    private Integer sort;

    /**
     * 岗位启停状态（0=正常，1=停用）
     */
    private Integer status;

    /**
     * 备注
     */
    private String remark;
}

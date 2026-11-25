package io.github.atengk.solid.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseLogicEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serial;

/**
 * 动态字典数据明细持久层实体（平台共享表，全租户只读消费）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
@TableName("sys_dict_data")
public class SysDictData extends BaseLogicEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属字典类型编码
     */
    private String dictType;

    /**
     * 字典展示标签
     */
    private String dictLabel;

    /**
     * 字典真实键值（同 dict_type 内未删除唯一）
     */
    private String dictValue;

    /**
     * 回显标签语义样式（空串 / primary / success / warning / danger / info）
     */
    private String tagType;

    /**
     * 自定义扩展 CSS 类名
     */
    private String cssClass;

    /**
     * 显示排序（升序）
     */
    private Integer sort;

    /**
     * 是否默认项（0=否，1=是，同 dict_type 下互斥唯一）
     */
    private Integer isDefault;

    /**
     * 启停状态（0=正常，1=停用）
     */
    private Integer status;

    /**
     * 备注
     */
    private String remark;
}

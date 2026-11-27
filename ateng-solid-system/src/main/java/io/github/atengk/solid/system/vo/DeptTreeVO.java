package io.github.atengk.solid.system.vo;

import io.github.atengk.boot.core.annotation.FieldSensitive;
import io.github.atengk.boot.core.enums.SensitiveType;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 部门树节点响应视图对象（手机与邮箱出界序列化经 {@link FieldSensitive} 动态脱敏；编辑详情 {@link DeptVO} 保持真明文，返回完整子节点数组）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Value
@Builder
public class DeptTreeVO {

    /**
     * 部门主键
     */
    Long id;

    /**
     * 上级部门主键（顶级为 0）
     */
    Long parentId;

    /**
     * 祖级路径链
     */
    String ancestors;

    /**
     * 部门名称
     */
    String deptName;

    /**
     * 显示排序
     */
    Integer sort;

    /**
     * 部门负责人用户主键
     */
    Long leaderUserId;

    /**
     * 部门负责人昵称
     */
    String leaderNickname;

    /**
     * 部门负责人账号
     */
    String leaderUsername;

    /**
     * 联系电话（树列表响应经 {@link FieldSensitive} 动态脱敏）
     */
    @FieldSensitive(SensitiveType.MOBILE_PHONE)
    String phone;

    /**
     * 电子邮箱（树列表响应经 {@link FieldSensitive} 动态脱敏）
     */
    @FieldSensitive(SensitiveType.EMAIL)
    String email;

    /**
     * 部门状态（0=正常，1=停用）
     */
    Integer status;

    /**
     * 创建时间
     */
    LocalDateTime createTime;

    /**
     * 子部门列表（无子节点时返回空集合）
     */
    @Builder.Default
    List<DeptTreeVO> children = Collections.emptyList();
}

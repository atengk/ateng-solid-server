package io.github.atengk.solid.system.vo;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * 部门详情响应视图对象（包含编辑态所需的内存真明文联系电话与电子邮箱）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Value
@Builder
public class DeptVO {

    /**
     * 部门主键
     */
    Long id;

    /**
     * 上级部门主键
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
     * 联系电话（内存真明文）
     */
    String phone;

    /**
     * 电子邮箱（内存真明文）
     */
    String email;

    /**
     * 部门状态（0=正常，1=停用）
     */
    Integer status;

    /**
     * 创建时间
     */
    LocalDateTime createTime;
}

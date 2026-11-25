package io.github.atengk.solid.system.vo;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户详情响应视图对象（回显完整明文联系方式以及已分配的角色、岗位主键集合）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Value
@Builder
public class UserDetailVO {

    /**
     * 用户主键
     */
    Long id;

    /**
     * 所属租户主键
     */
    Long tenantId;

    /**
     * 归属部门主键
     */
    Long deptId;

    /**
     * 归属部门名称
     */
    String deptName;

    /**
     * 登录账号
     */
    String username;

    /**
     * 用户昵称
     */
    String nickname;

    /**
     * 电子邮箱（内存真明文）
     */
    String email;

    /**
     * 手机号码（内存真明文）
     */
    String phone;

    /**
     * 性别字典编码
     */
    String gender;

    /**
     * 头像预览访问地址
     */
    String avatar;

    /**
     * 账号状态（0=正常，1=停用）
     */
    Integer status;

    /**
     * 最近登录 IP
     */
    String loginIp;

    /**
     * 最近登录时间
     */
    LocalDateTime loginDate;

    /**
     * 最近改密时间
     */
    LocalDateTime pwdUpdateTime;

    /**
     * 备注
     */
    String remark;

    /**
     * 创建时间
     */
    LocalDateTime createTime;

    /**
     * 是否为平台内置超级管理员账号
     */
    boolean builtinSuperAdmin;

    /**
     * 已关联角色主键集合
     */
    List<Long> roleIds;

    /**
     * 已关联岗位主键集合
     */
    List<Long> postIds;
}

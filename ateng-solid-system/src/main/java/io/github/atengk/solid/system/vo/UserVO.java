package io.github.atengk.solid.system.vo;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * 用户分页列表响应视图对象（手机与邮箱保留内存真明文，前端列表渲染层按需脱敏，头像动态签发 inline URL）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Value
@Builder
public class UserVO {

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
     * 头像预览访问地址（动态签发预签名 URL 或降级键）
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
     * 最近改密时间（为空表示仍为初始或重置密码状态）
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
     * 是否为平台内置超级管理员账号（用于前端行保护置灰）
     */
    boolean builtinSuperAdmin;
}

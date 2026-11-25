package io.github.atengk.solid.system.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 个人中心完整用户资料数据传输对象（包含编辑所需的内存真明文联系方式与组织岗位摘要）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"email", "phone"})
public class PersonalProfileDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户主键
     */
    private Long userId;

    /**
     * 所属租户主键
     */
    private Long tenantId;

    /**
     * 所属租户名称
     */
    private String tenantName;

    /**
     * 登录账号
     */
    private String username;

    /**
     * 用户昵称
     */
    private String nickname;

    /**
     * 解密后的电子邮箱真明文
     */
    private String email;

    /**
     * 解密后的手机号码真明文
     */
    private String phone;

    /**
     * 性别字典值（0=男，1=女，2=未知）
     */
    private String gender;

    /**
     * 已动态签发（inline 预览）的可访问头像地址
     */
    private String avatarUrl;

    /**
     * 所属部门名称
     */
    private String deptName;

    /**
     * 所属岗位名称集合
     */
    @Builder.Default
    private List<String> postNames = Collections.emptyList();

    /**
     * 拥有角色名称/编码展示集合
     */
    @Builder.Default
    private List<String> roles = Collections.emptyList();

    /**
     * 最近登录客户端 IP
     */
    private String loginIp;

    /**
     * 最近登录时间
     */
    private LocalDateTime loginDate;

    /**
     * 账号创建时间
     */
    private LocalDateTime createTime;

    /**
     * 最近自助修改密码时间
     */
    private LocalDateTime pwdUpdateTime;
}

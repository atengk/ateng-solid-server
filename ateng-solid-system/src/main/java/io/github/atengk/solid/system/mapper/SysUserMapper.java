package io.github.atengk.solid.system.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.mybatisplus.annotation.DataScope;
import io.github.atengk.boot.mybatisplus.handler.DeterministicEncryptTypeHandler;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.solid.system.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 用户表持久层 Mapper。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Mapper
public interface SysUserMapper extends AtengBaseMapper<SysUser> {

    /**
     * 按用户主键单点查询未删除用户的登录账号（用于异步操作审计日志补全真实操作人账号）。
     *
     * @param id 用户主键 ID
     * @return 登录账号；记录不存在或已逻辑删除时返回 {@code null}
     */
    @Select("SELECT username FROM sys_user WHERE id = #{id} AND deleted = 0")
    String selectUsernameById(@Param("id") Long id);

    /**
     * 分页或受权导出检索未删除用户（受 5 级 {@link DataScope} 与确定性加密列类型处理器双重约束）。
     *
     * @param page     MyBatis-Plus 分页对象
     * @param criteria 持久化查询条件载体
     * @return 用户分页结果（{@code email} 与 {@code phone} 已自动解密为明文）
     */
    @DataScope(alias = "u", deptColumn = "dept_id", userColumn = "id")
    @Results(id = "sysUserCryptoResult", value = {
            @Result(column = "id", property = "id", id = true),
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "dept_id", property = "deptId"),
            @Result(column = "username", property = "username"),
            @Result(column = "nickname", property = "nickname"),
            @Result(column = "pwd_update_time", property = "pwdUpdateTime"),
            @Result(column = "email", property = "email", typeHandler = DeterministicEncryptTypeHandler.class),
            @Result(column = "phone", property = "phone", typeHandler = DeterministicEncryptTypeHandler.class),
            @Result(column = "gender", property = "gender"),
            @Result(column = "avatar", property = "avatar"),
            @Result(column = "status", property = "status"),
            @Result(column = "login_ip", property = "loginIp"),
            @Result(column = "login_date", property = "loginDate"),
            @Result(column = "remark", property = "remark"),
            @Result(column = "create_by", property = "createBy"),
            @Result(column = "create_time", property = "createTime"),
            @Result(column = "update_by", property = "updateBy"),
            @Result(column = "update_time", property = "updateTime"),
            @Result(column = "deleted", property = "deleted")
    })
    @Select("""
            <script>
            SELECT u.id, u.tenant_id, u.dept_id, u.username, u.nickname, u.pwd_update_time,
                   u.email, u.phone, u.gender, u.avatar, u.status, u.login_ip, u.login_date,
                   u.remark, u.create_by, u.create_time, u.update_by, u.update_time, u.deleted
            FROM sys_user u
            WHERE u.deleted = 0
              <if test="criteria.tenantId != null">
                AND u.tenant_id = #{criteria.tenantId}
              </if>
              <if test="criteria.username != null and criteria.username != ''">
                AND (u.username LIKE CONCAT('%', #{criteria.username}, '%')
                     OR u.nickname LIKE CONCAT('%', #{criteria.username}, '%'))
              </if>
              <if test="criteria.encryptedPhone != null and criteria.encryptedPhone != ''">
                AND u.phone = #{criteria.encryptedPhone}
              </if>
              <if test="criteria.status != null">
                AND u.status = #{criteria.status}
              </if>
              <if test="criteria.deptIds != null and !criteria.deptIds.isEmpty()">
                AND u.dept_id IN
                <foreach collection="criteria.deptIds" item="deptId" open="(" separator="," close=")">
                  #{deptId}
                </foreach>
              </if>
              <if test="criteria.ids != null and !criteria.ids.isEmpty()">
                AND u.id IN
                <foreach collection="criteria.ids" item="id" open="(" separator="," close=")">
                  #{id}
                </foreach>
              </if>
              <if test="criteria.beginTime != null">
                AND u.create_time &gt;= #{criteria.beginTime}
              </if>
              <if test="criteria.endTime != null">
                AND u.create_time &lt;= #{criteria.endTime}
              </if>
              <if test="criteria.defaultOrder">
                ORDER BY u.create_time DESC, u.id DESC
              </if>
            </script>
            """)
    Page<SysUser> selectUserPageWithDataScope(Page<SysUser> page, @Param("criteria") UserQueryCriteria criteria);

    /**
     * 按主键查询单个未删除用户（强制叠加 {@link DataScope} 数据范围权限与自动解密）。
     *
     * @param tenantId 目标租户主键
     * @param id       用户主键
     * @return 用户实体；无权限或不存在时返回 {@code null}
     */
    @DataScope(alias = "u", deptColumn = "dept_id", userColumn = "id")
    @ResultMap("sysUserCryptoResult")
    @Select("""
            <script>
            SELECT u.id, u.tenant_id, u.dept_id, u.username, u.nickname, u.pwd_update_time,
                   u.email, u.phone, u.gender, u.avatar, u.status, u.login_ip, u.login_date,
                   u.remark, u.create_by, u.create_time, u.update_by, u.update_time, u.deleted
            FROM sys_user u
            WHERE u.id = #{id}
              AND u.deleted = 0
              <if test="tenantId != null">
                AND u.tenant_id = #{tenantId}
              </if>
            </script>
            """)
    SysUser selectUserByIdWithDataScope(@Param("tenantId") Long tenantId, @Param("id") Long id);

    /**
     * 按主键集合过滤当前操作人 {@link DataScope} 数据范围权限内可见的未删除用户主键列表。
     *
     * @param tenantId 目标租户主键
     * @param ids      待校验的用户主键集合
     * @return 数据权限范围内可见的用户主键列表
     */
    @DataScope(alias = "u", deptColumn = "dept_id", userColumn = "id")
    @Select("""
            <script>
            SELECT u.id
            FROM sys_user u
            WHERE u.tenant_id = #{tenantId}
              AND u.deleted = 0
              <choose>
                <when test="ids != null and !ids.isEmpty()">
                  AND u.id IN
                  <foreach collection="ids" item="id" open="(" separator="," close=")">
                    #{id}
                  </foreach>
                </when>
                <otherwise>
                  AND 1 = 0
                </otherwise>
              </choose>
            </script>
            """)
    List<Long> selectVisibleUserIdsByIdsWithDataScope(@Param("tenantId") Long tenantId, @Param("ids") Collection<Long> ids);

    /**
     * 批量探测租户内已存在的登录账号列表（专供批量导入阶段 1 去重校验）。
     *
     * @param tenantId  租户主键
     * @param usernames 待检登录账号集合
     * @return 库中已存在的登录账号集合
     */
    @Select("""
            <script>
            SELECT username
            FROM sys_user
            WHERE tenant_id = #{tenantId}
              AND deleted = 0
              <choose>
                <when test="usernames != null and !usernames.isEmpty()">
                  AND username IN
                  <foreach collection="usernames" item="item" open="(" separator="," close=")">
                    #{item}
                  </foreach>
                </when>
                <otherwise>
                  AND 1 = 0
                </otherwise>
              </choose>
            </script>
            """)
    List<String> selectExistingUsernamesInTenant(
            @Param("tenantId") Long tenantId,
            @Param("usernames") Collection<String> usernames
    );

    /**
     * 批量探测租户内已存在的手机号密文集合（直接命中确定性密文索引，不做多余加解密往返）。
     *
     * @param tenantId        租户主键
     * @param encryptedPhones 确定性加密后的手机号密文集合
     * @return 库中已存在的手机号密文列表
     */
    @Select("""
            <script>
            SELECT phone
            FROM sys_user
            WHERE tenant_id = #{tenantId}
              AND deleted = 0
              <choose>
                <when test="encryptedPhones != null and !encryptedPhones.isEmpty()">
                  AND phone IN
                  <foreach collection="encryptedPhones" item="item" open="(" separator="," close=")">
                    #{item}
                  </foreach>
                </when>
                <otherwise>
                  AND 1 = 0
                </otherwise>
              </choose>
            </script>
            """)
    List<String> selectExistingPhonesInTenant(
            @Param("tenantId") Long tenantId,
            @Param("encryptedPhones") Collection<String> encryptedPhones
    );

    /**
     * 批量探测租户内已存在的电子邮箱密文集合（直接命中确定性密文索引，不做多余加解密往返）。
     *
     * @param tenantId        租户主键
     * @param encryptedEmails 确定性加密后的电子邮箱密文集合
     * @return 库中已存在的电子邮箱密文列表
     */
    @Select("""
            <script>
            SELECT email
            FROM sys_user
            WHERE tenant_id = #{tenantId}
              AND deleted = 0
              <choose>
                <when test="encryptedEmails != null and !encryptedEmails.isEmpty()">
                  AND email IN
                  <foreach collection="encryptedEmails" item="item" open="(" separator="," close=")">
                    #{item}
                  </foreach>
                </when>
                <otherwise>
                  AND 1 = 0
                </otherwise>
              </choose>
            </script>
            """)
    List<String> selectExistingEmailsInTenant(
            @Param("tenantId") Long tenantId,
            @Param("encryptedEmails") Collection<String> encryptedEmails
    );
}

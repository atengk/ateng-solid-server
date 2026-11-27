package io.github.atengk.solid.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.web.annotation.OperateLog;
import io.github.atengk.boot.web.ratelimit.annotation.LimitType;
import io.github.atengk.boot.web.ratelimit.annotation.RateLimiter;
import io.github.atengk.solid.system.request.StatusUpdateRequest;
import io.github.atengk.solid.system.request.UserCreateRequest;
import io.github.atengk.solid.system.request.UserQueryRequest;
import io.github.atengk.solid.system.request.UserResetPasswordRequest;
import io.github.atengk.solid.system.request.UserUpdateRequest;
import io.github.atengk.solid.system.service.SysUserQueryService;
import io.github.atengk.solid.system.service.SysUserService;
import io.github.atengk.solid.system.vo.UserDetailVO;
import io.github.atengk.solid.system.vo.UserOptionVO;
import io.github.atengk.solid.system.vo.UserVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户管理控制器，提供用户分页查询（受 5 级数据权限控制）、轻量下拉选项、详情读取、配额守卫增删改及重置密码接口
 * （路由前缀：{@code /admin/system/user}）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@RestController
@RequestMapping("/admin/system/user")
@RequiredArgsConstructor
public class SysUserController {

    private final SysUserService sysUserService;
    private final SysUserQueryService sysUserQueryService;

    /**
     * 分页查询当前工作租户下满足 5 级数据权限约束的用户列表。
     *
     * @param query 分页与筛选参数（含部门子树展开与完整手机号精确密文查询）
     * @return 用户分页结果
     */
    @SaCheckPermission("system:user:list")
    @GetMapping("/page")
    public Result<PageResult<UserVO>> page(@Valid UserQueryRequest query) {
        return Result.success(sysUserQueryService.pageUsers(query));
    }

    /**
     * 获取当前工作租户下满足 5 级数据权限与正常启用状态的用户轻量化下拉选项（供部门负责人选择器等受权场景消费）。
     *
     * @param keyword 账号或昵称模糊关键字（可选）
     * @return 启用中用户选项列表
     */
    @SaCheckPermission(value = {"system:dept:create", "system:dept:update", "system:user:list"}, mode = SaMode.OR)
    @GetMapping("/options")
    public Result<List<UserOptionVO>> options(@RequestParam(required = false) String keyword) {
        return Result.success(sysUserQueryService.listActiveUserOptions(keyword));
    }

    /**
     * 查询指定用户的完整详情（受 5 级数据权限约束，包含内存真明文联系方式及角色/岗位关联主键）。
     *
     * @param id 用户主键
     * @return 用户详情视图对象
     */
    @SaCheckPermission("system:user:list")
    @GetMapping("/{id}")
    public Result<UserDetailVO> detail(@PathVariable Long id) {
        return Result.success(sysUserQueryService.getUserDetail(id));
    }

    /**
     * 新增管理端用户（禁入审计请求参数防止明文密码泄漏）。
     *
     * @param request 新增用户请求体
     * @return 新建用户主键
     */
    @SaCheckPermission("system:user:create")
    @OperateLog(module = "用户管理", type = OperateTypeEnum.INSERT, description = "新增用户", saveParams = false)
    @PostMapping
    public Result<Long> create(@Valid @RequestBody UserCreateRequest request) {
        return Result.success(sysUserService.createUser(request));
    }

    /**
     * 修改管理端用户信息。
     *
     * @param request 修改用户请求体
     * @return 空成功响应
     */
    @SaCheckPermission("system:user:update")
    @OperateLog(module = "用户管理", type = OperateTypeEnum.UPDATE, description = "修改用户", excludeParams = {"phone", "email"})
    @PutMapping
    public Result<Void> update(@Valid @RequestBody UserUpdateRequest request) {
        sysUserService.updateUser(request);
        return Result.success();
    }

    /**
     * 切换用户启用/停用状态。
     *
     * @param id      用户主键
     * @param request 目标状态请求体
     * @return 空成功响应
     */
    @SaCheckPermission("system:user:update")
    @OperateLog(module = "用户管理", type = OperateTypeEnum.UPDATE, description = "切换用户状态")
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody StatusUpdateRequest request
    ) {
        sysUserService.updateUserStatus(id, request.getStatus());
        return Result.success();
    }

    /**
     * 重置指定用户登录密码（禁入审计请求参数防止明文密码泄漏，置空 {@code pwd_update_time} 并强退在线会话）。
     *
     * @param id      目标用户主键
     * @param request 重置密码请求体（可为空，留空自动使用 {@code sys.user.initPassword}）
     * @return 空成功响应
     */
    @SaCheckPermission("system:user:update")
    @RateLimiter(limitType = LimitType.USER, key = "'system:user:reset-password'", count = 10, time = 60)
    @OperateLog(module = "用户管理", type = OperateTypeEnum.UPDATE, description = "重置用户密码", saveParams = false)
    @PutMapping("/{id}/reset-password")
    public Result<Void> resetPassword(
            @PathVariable Long id,
            @RequestBody(required = false) UserResetPasswordRequest request
    ) {
        sysUserService.resetPassword(id, request);
        return Result.success();
    }

    /**
     * 批量逻辑删除用户。
     *
     * @param ids 逗号分隔的用户主键串
     * @return 空成功响应
     */
    @SaCheckPermission("system:user:delete")
    @OperateLog(module = "用户管理", type = OperateTypeEnum.DELETE, description = "删除用户")
    @DeleteMapping("/{ids}")
    public Result<Void> delete(@PathVariable String ids) {
        sysUserService.deleteUsers(ids);
        return Result.success();
    }
}

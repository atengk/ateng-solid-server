package io.github.atengk.solid.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.web.annotation.OperateLog;
import io.github.atengk.boot.web.idempotent.annotation.Idempotent;
import io.github.atengk.solid.system.request.RoleDataScopeRequest;
import io.github.atengk.solid.system.request.RoleQueryRequest;
import io.github.atengk.solid.system.request.RoleSaveRequest;
import io.github.atengk.solid.system.request.StatusUpdateRequest;
import io.github.atengk.solid.system.service.SysRoleQueryService;
import io.github.atengk.solid.system.service.SysRoleService;
import io.github.atengk.solid.system.vo.MenuTreeVO;
import io.github.atengk.solid.system.vo.RoleDetailVO;
import io.github.atengk.solid.system.vo.RoleOptionVO;
import io.github.atengk.solid.system.vo.RoleVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色管理控制器，提供角色分页、选项、可授权菜单树、菜单绑定与 5 级数据权限分配接口（路由前缀：{@code /admin/system/role}）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@RestController
@RequestMapping("/admin/system/role")
@RequiredArgsConstructor
public class SysRoleController {

    private final SysRoleService sysRoleService;
    private final SysRoleQueryService sysRoleQueryService;

    /**
     * 分页查询当前工作租户下的角色列表。
     *
     * @param query 分页与筛选参数
     * @return 角色分页结果
     */
    @SaCheckPermission("system:role:list")
    @GetMapping("/page")
    public Result<PageResult<RoleVO>> page(@Valid RoleQueryRequest query) {
        return Result.success(sysRoleQueryService.pageRoles(query));
    }

    /**
     * 获取当前工作租户下正常启用状态的角色下拉选项。
     *
     * @return 角色选项集合
     */
    @SaCheckLogin
    @GetMapping("/options")
    public Result<List<RoleOptionVO>> options() {
        return Result.success(sysRoleQueryService.listActiveOptions());
    }

    /**
     * 获取当前工作租户角色表单中可勾选的启用菜单树（普通租户仅返回其套餐交集并过滤平台专属菜单）。
     *
     * @return 可授权菜单树
     */
    @SaCheckPermission(value = {"system:role:list", "system:role:create", "system:role:update"}, mode = SaMode.OR)
    @GetMapping("/menu-tree")
    public Result<List<MenuTreeVO>> grantableMenuTree() {
        return Result.success(sysRoleQueryService.listGrantableMenuTree());
    }

    /**
     * 查询指定角色详情及已绑定菜单、自定部门主键集合。
     *
     * @param id 角色主键
     * @return 角色详情
     */
    @SaCheckPermission("system:role:list")
    @GetMapping("/{id}")
    public Result<RoleDetailVO> detail(@PathVariable Long id) {
        return Result.success(sysRoleQueryService.getDetail(id));
    }

    /**
     * 新增角色并绑定菜单权限。
     *
     * @param request 角色保存请求
     * @return 新建角色主键
     */
    @SaCheckPermission("system:role:create")
    @Idempotent
    @OperateLog(module = "角色管理", type = OperateTypeEnum.INSERT, description = "新增角色")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody RoleSaveRequest request) {
        return Result.success(sysRoleService.createRole(request));
    }

    /**
     * 修改角色信息及菜单权限集合。
     *
     * @param request 角色修改请求
     * @return 空成功响应
     */
    @SaCheckPermission("system:role:update")
    @OperateLog(module = "角色管理", type = OperateTypeEnum.UPDATE, description = "修改角色")
    @PutMapping
    public Result<Void> update(@Valid @RequestBody RoleSaveRequest request) {
        sysRoleService.updateRole(request);
        return Result.success();
    }

    /**
     * 切换角色启停状态。
     *
     * @param id      角色主键
     * @param request 状态切换请求
     * @return 空成功响应
     */
    @SaCheckPermission("system:role:update")
    @OperateLog(module = "角色管理", type = OperateTypeEnum.UPDATE, description = "切换角色状态")
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody StatusUpdateRequest request
    ) {
        sysRoleService.updateRoleStatus(id, request.getStatus());
        return Result.success();
    }

    /**
     * 分配角色 5 级数据权限范围。
     *
     * @param id      角色主键
     * @param request 数据权限配置请求
     * @return 空成功响应
     */
    @SaCheckPermission("system:role:update")
    @OperateLog(module = "角色管理", type = OperateTypeEnum.UPDATE, description = "分配角色数据权限")
    @PutMapping("/{id}/data-scope")
    public Result<Void> updateDataScope(
            @PathVariable Long id,
            @Valid @RequestBody RoleDataScopeRequest request
    ) {
        sysRoleService.updateRoleDataScope(id, request);
        return Result.success();
    }

    /**
     * 批量删除角色。
     *
     * @param ids 逗号分隔的角色主键串
     * @return 空成功响应
     */
    @SaCheckPermission("system:role:delete")
    @OperateLog(module = "角色管理", type = OperateTypeEnum.DELETE, description = "删除角色")
    @DeleteMapping("/{ids}")
    public Result<Void> delete(@PathVariable String ids) {
        sysRoleService.deleteRoles(ids);
        return Result.success();
    }
}

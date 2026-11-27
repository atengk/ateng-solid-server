package io.github.atengk.solid.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.web.annotation.OperateLog;
import io.github.atengk.boot.web.idempotent.annotation.Idempotent;
import io.github.atengk.solid.system.request.MenuQueryRequest;
import io.github.atengk.solid.system.request.MenuSaveRequest;
import io.github.atengk.solid.system.request.StatusUpdateRequest;
import io.github.atengk.solid.system.service.SysMenuService;
import io.github.atengk.solid.system.vo.MenuTreeVO;
import io.github.atengk.solid.system.vo.MenuVO;
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
 * 菜单管理控制器（严格锁定平台默认租户 0 维护），提供菜单权限树检索及 M/C/F 节点维护接口（路由前缀：{@code /admin/system/menu}）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@RestController
@RequestMapping("/admin/system/menu")
@RequiredArgsConstructor
public class SysMenuController {

    private final SysMenuService sysMenuService;

    /**
     * 查询平台菜单权限树。
     *
     * @param query 检索条件
     * @return 菜单树集合
     */
    @SaCheckPermission("system:menu:list")
    @GetMapping("/tree")
    public Result<List<MenuTreeVO>> tree(@Valid MenuQueryRequest query) {
        return Result.success(sysMenuService.listMenuTree(query));
    }

    /**
     * 查询指定菜单节点详情。
     *
     * @param id 菜单主键
     * @return 菜单详情
     */
    @SaCheckPermission("system:menu:list")
    @GetMapping("/{id}")
    public Result<MenuVO> detail(@PathVariable Long id) {
        return Result.success(sysMenuService.getDetail(id));
    }

    /**
     * 新增菜单或按钮权限。
     *
     * @param request 菜单保存请求
     * @return 新建菜单主键
     */
    @SaCheckPermission("system:menu:create")
    @Idempotent
    @OperateLog(module = "菜单管理", type = OperateTypeEnum.INSERT, description = "新增菜单")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody MenuSaveRequest request) {
        return Result.success(sysMenuService.createMenu(request));
    }

    /**
     * 修改菜单或按钮权限。
     *
     * @param request 菜单修改请求
     * @return 空成功响应
     */
    @SaCheckPermission("system:menu:update")
    @OperateLog(module = "菜单管理", type = OperateTypeEnum.UPDATE, description = "修改菜单")
    @PutMapping
    public Result<Void> update(@Valid @RequestBody MenuSaveRequest request) {
        sysMenuService.updateMenu(request);
        return Result.success();
    }

    /**
     * 切换菜单启停状态。
     *
     * @param id      菜单主键
     * @param request 状态切换请求
     * @return 空成功响应
     */
    @SaCheckPermission("system:menu:update")
    @OperateLog(module = "菜单管理", type = OperateTypeEnum.UPDATE, description = "切换菜单状态")
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody StatusUpdateRequest request
    ) {
        sysMenuService.updateMenuStatus(id, request.getStatus());
        return Result.success();
    }

    /**
     * 删除指定菜单或按钮权限节点。
     *
     * @param id 菜单主键
     * @return 空成功响应
     */
    @SaCheckPermission("system:menu:delete")
    @OperateLog(module = "菜单管理", type = OperateTypeEnum.DELETE, description = "删除菜单")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        sysMenuService.deleteMenu(id);
        return Result.success();
    }
}

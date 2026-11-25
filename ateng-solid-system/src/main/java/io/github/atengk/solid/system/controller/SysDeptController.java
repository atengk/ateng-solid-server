package io.github.atengk.solid.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.web.annotation.OperateLog;
import io.github.atengk.solid.system.request.DeptQueryRequest;
import io.github.atengk.solid.system.request.DeptSaveRequest;
import io.github.atengk.solid.system.request.StatusUpdateRequest;
import io.github.atengk.solid.system.service.SysDeptQueryService;
import io.github.atengk.solid.system.service.SysDeptService;
import io.github.atengk.solid.system.vo.DeptTreeVO;
import io.github.atengk.solid.system.vo.DeptVO;
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
 * 部门管理控制器，提供组织部门树检索、选项下拉及防自环级联维护接口（路由前缀：{@code /admin/system/dept}）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@RestController
@RequestMapping("/admin/system/dept")
@RequiredArgsConstructor
public class SysDeptController {

    private final SysDeptService sysDeptService;
    private final SysDeptQueryService sysDeptQueryService;

    /**
     * 查询当前工作租户的部门树列表。
     *
     * @param query 部门名称与状态检索条件
     * @return 部门树集合
     */
    @SaCheckPermission("system:dept:list")
    @GetMapping("/tree")
    public Result<List<DeptTreeVO>> tree(DeptQueryRequest query) {
        return Result.success(sysDeptQueryService.listTree(query));
    }

    /**
     * 获取当前工作租户下正常启用状态的部门选项树（供用户、角色等选择器消费）。
     *
     * @return 正常部门选项树集合
     */
    @SaCheckLogin
    @GetMapping("/options")
    public Result<List<DeptTreeVO>> options() {
        return Result.success(sysDeptQueryService.listActiveTreeOptions());
    }

    /**
     * 查询指定部门详情。
     *
     * @param id 部门主键
     * @return 部门详情
     */
    @SaCheckPermission("system:dept:list")
    @GetMapping("/{id}")
    public Result<DeptVO> detail(@PathVariable Long id) {
        return Result.success(sysDeptQueryService.getDetail(id));
    }

    /**
     * 新增部门节点。
     *
     * @param request 部门保存请求
     * @return 新建部门主键
     */
    @SaCheckPermission("system:dept:create")
    @OperateLog(module = "部门管理", type = OperateTypeEnum.INSERT, description = "新增部门")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody DeptSaveRequest request) {
        return Result.success(sysDeptService.createDept(request));
    }

    /**
     * 修改部门节点并在切换上级时级联重算子孙祖级路径。
     *
     * @param request 部门修改请求
     * @return 空成功响应
     */
    @SaCheckPermission("system:dept:update")
    @OperateLog(module = "部门管理", type = OperateTypeEnum.UPDATE, description = "修改部门")
    @PutMapping
    public Result<Void> update(@Valid @RequestBody DeptSaveRequest request) {
        sysDeptService.updateDept(request);
        return Result.success();
    }

    /**
     * 切换部门启停状态。
     *
     * @param id      部门主键
     * @param request 状态切换请求
     * @return 空成功响应
     */
    @SaCheckPermission("system:dept:update")
    @OperateLog(module = "部门管理", type = OperateTypeEnum.UPDATE, description = "切换部门状态")
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody StatusUpdateRequest request
    ) {
        sysDeptService.updateDeptStatus(id, request.getStatus());
        return Result.success();
    }

    /**
     * 删除指定部门节点。
     *
     * @param id 部门主键
     * @return 空成功响应
     */
    @SaCheckPermission("system:dept:delete")
    @OperateLog(module = "部门管理", type = OperateTypeEnum.DELETE, description = "删除部门")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        sysDeptService.deleteDept(id);
        return Result.success();
    }
}

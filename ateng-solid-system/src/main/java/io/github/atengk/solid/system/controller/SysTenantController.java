package io.github.atengk.solid.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.web.annotation.OperateLog;
import io.github.atengk.solid.system.request.StatusUpdateRequest;
import io.github.atengk.solid.system.request.TenantCreateRequest;
import io.github.atengk.solid.system.request.TenantQueryRequest;
import io.github.atengk.solid.system.request.TenantUpdateRequest;
import io.github.atengk.solid.system.service.SysTenantQueryService;
import io.github.atengk.solid.system.service.SysTenantService;
import io.github.atengk.solid.system.vo.CreateTenantVO;
import io.github.atengk.solid.system.vo.TenantOptionVO;
import io.github.atengk.solid.system.vo.TenantVO;
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
 * 可开关 SaaS 租户管理控制器（仅多租户开启且平台默认租户 {@code 0} 可维护，路由前缀：{@code /admin/system/tenant}）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@RestController
@RequestMapping("/admin/system/tenant")
@RequiredArgsConstructor
public class SysTenantController {

    private final SysTenantQueryService sysTenantQueryService;
    private final SysTenantService sysTenantService;

    /**
     * 分页查询租户列表。
     *
     * @param query 分页与筛选参数
     * @return 租户分页视图结果
     */
    @SaCheckPermission("system:tenant:list")
    @GetMapping("/page")
    public Result<PageResult<TenantVO>> page(@Valid TenantQueryRequest query) {
        return Result.success(sysTenantQueryService.pageTenants(query));
    }

    /**
     * 获取顶栏租户切换器可选活跃未过期租户列表（面向平台超级管理员）。
     *
     * @return 活跃租户选项列表
     */
    @SaCheckLogin
    @GetMapping("/options")
    public Result<List<TenantOptionVO>> options() {
        return Result.success(sysTenantQueryService.listSwitchOptions());
    }

    /**
     * 查询指定租户详情（允许回显平台默认租户 {@code 0}）。
     *
     * @param id 租户主键
     * @return 租户详情视图
     */
    @SaCheckPermission("system:tenant:list")
    @GetMapping("/{id}")
    public Result<TenantVO> detail(@PathVariable Long id) {
        return Result.success(sysTenantQueryService.getDetail(id));
    }

    /**
     * 新增租户并执行 4 步原子初始化，关闭参数持久化避免初始密码写入审计日志。
     *
     * @param request 新增租户请求
     * @return 新建租户初始化凭据摘要
     */
    @SaCheckPermission("system:tenant:create")
    @OperateLog(module = "租户管理", type = OperateTypeEnum.INSERT, description = "新增租户", saveParams = false)
    @PostMapping
    public Result<CreateTenantVO> create(@Valid @RequestBody TenantCreateRequest request) {
        return Result.success(sysTenantService.createTenant(request));
    }

    /**
     * 修改租户基础信息或变更关联套餐。
     *
     * @param request 修改租户请求
     * @return 空成功响应
     */
    @SaCheckPermission("system:tenant:update")
    @OperateLog(module = "租户管理", type = OperateTypeEnum.UPDATE, description = "修改租户")
    @PutMapping
    public Result<Void> update(@Valid @RequestBody TenantUpdateRequest request) {
        sysTenantService.updateTenant(request);
        return Result.success();
    }

    /**
     * 切换租户启停状态（停用提交后强退该租户全员在线会话）。
     *
     * @param id      租户主键
     * @param request 状态切换请求
     * @return 空成功响应
     */
    @SaCheckPermission("system:tenant:update")
    @OperateLog(module = "租户管理", type = OperateTypeEnum.UPDATE, description = "切换租户状态")
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody StatusUpdateRequest request
    ) {
        sysTenantService.updateTenantStatus(id, request.getStatus());
        return Result.success();
    }

    /**
     * 批量删除租户（删除提交后驱逐状态缓存并强退该批租户全员在线会话）。
     *
     * @param ids 逗号分隔的租户主键列表串
     * @return 空成功响应
     */
    @SaCheckPermission("system:tenant:delete")
    @OperateLog(module = "租户管理", type = OperateTypeEnum.DELETE, description = "删除租户")
    @DeleteMapping("/{ids}")
    public Result<Void> delete(@PathVariable String ids) {
        sysTenantService.deleteTenants(ids);
        return Result.success();
    }
}

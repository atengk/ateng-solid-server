package io.github.atengk.solid.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.web.annotation.OperateLog;
import io.github.atengk.solid.system.request.StatusUpdateRequest;
import io.github.atengk.solid.system.request.TenantPackageQueryRequest;
import io.github.atengk.solid.system.request.TenantPackageSaveRequest;
import io.github.atengk.solid.system.request.TenantPackageShrinkPreviewRequest;
import io.github.atengk.solid.system.service.SysTenantPackageQueryService;
import io.github.atengk.solid.system.service.SysTenantPackageService;
import io.github.atengk.solid.system.vo.TenantPackageDetailVO;
import io.github.atengk.solid.system.vo.TenantPackageOptionVO;
import io.github.atengk.solid.system.vo.TenantPackageShrinkPreviewVO;
import io.github.atengk.solid.system.vo.TenantPackageVO;
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
 * 租户套餐管理控制器（严格锁定多租户开启态平台默认租户 {@code 0}，路由前缀：{@code /admin/system/tenant-package}）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@RestController
@RequestMapping("/admin/system/tenant-package")
@RequiredArgsConstructor
public class SysTenantPackageController {

    private final SysTenantPackageQueryService sysTenantPackageQueryService;
    private final SysTenantPackageService sysTenantPackageService;

    /**
     * 分页查询租户套餐列表。
     *
     * @param query 分页与筛选参数
     * @return 套餐分页结果
     */
    @SaCheckPermission("system:tenant-package:list")
    @GetMapping("/page")
    public Result<PageResult<TenantPackageVO>> page(@Valid TenantPackageQueryRequest query) {
        return Result.success(sysTenantPackageQueryService.pagePackages(query));
    }

    /**
     * 获取全部正常启用状态的租户套餐下拉选项。
     *
     * @return 启用中套餐选项列表
     */
    @SaCheckPermission(
            value = {"system:tenant-package:list", "system:tenant:list", "system:tenant:create", "system:tenant:update"},
            mode = SaMode.OR
    )
    @GetMapping("/options")
    public Result<List<TenantPackageOptionVO>> options() {
        return Result.success(sysTenantPackageQueryService.listActiveOptions());
    }

    /**
     * 查询指定租户套餐详情及已绑定菜单主键集合。
     *
     * @param id 套餐主键
     * @return 套餐详情视图
     */
    @SaCheckPermission("system:tenant-package:list")
    @GetMapping("/{id}")
    public Result<TenantPackageDetailVO> detail(@PathVariable Long id) {
        return Result.success(sysTenantPackageQueryService.getDetail(id));
    }

    /**
     * 只读预览缩减套餐菜单对存量绑定租户与移除菜单名称的影响范围。
     *
     * @param request 缩减预览请求
     * @return 影响范围预览视图
     */
    @SaCheckPermission("system:tenant-package:update")
    @PostMapping("/shrink-preview")
    public Result<TenantPackageShrinkPreviewVO> shrinkPreview(
            @Valid @RequestBody TenantPackageShrinkPreviewRequest request
    ) {
        return Result.success(sysTenantPackageQueryService.previewShrinkImpact(request));
    }

    /**
     * 新增租户套餐。
     *
     * @param request 套餐保存请求
     * @return 新建套餐主键
     */
    @SaCheckPermission("system:tenant-package:create")
    @OperateLog(module = "租户套餐", type = OperateTypeEnum.INSERT, description = "新增租户套餐")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody TenantPackageSaveRequest request) {
        return Result.success(sysTenantPackageService.createPackage(request));
    }

    /**
     * 修改租户套餐（缩减菜单时联动裁剪关联租户越界角色权限并刷新缓存）。
     *
     * @param request 套餐修改请求
     * @return 空成功响应
     */
    @SaCheckPermission("system:tenant-package:update")
    @OperateLog(module = "租户套餐", type = OperateTypeEnum.UPDATE, description = "修改租户套餐")
    @PutMapping
    public Result<Void> update(@Valid @RequestBody TenantPackageSaveRequest request) {
        sysTenantPackageService.updatePackage(request);
        return Result.success();
    }

    /**
     * 切换租户套餐启停状态。
     *
     * @param id      套餐主键
     * @param request 状态切换请求
     * @return 空成功响应
     */
    @SaCheckPermission("system:tenant-package:update")
    @OperateLog(module = "租户套餐", type = OperateTypeEnum.UPDATE, description = "切换租户套餐状态")
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody StatusUpdateRequest request
    ) {
        sysTenantPackageService.updatePackageStatus(id, request.getStatus());
        return Result.success();
    }

    /**
     * 批量删除租户套餐。
     *
     * @param ids 逗号分隔的套餐主键列表串
     * @return 空成功响应
     */
    @SaCheckPermission("system:tenant-package:delete")
    @OperateLog(module = "租户套餐", type = OperateTypeEnum.DELETE, description = "删除租户套餐")
    @DeleteMapping("/{ids}")
    public Result<Void> delete(@PathVariable String ids) {
        sysTenantPackageService.deletePackages(ids);
        return Result.success();
    }
}

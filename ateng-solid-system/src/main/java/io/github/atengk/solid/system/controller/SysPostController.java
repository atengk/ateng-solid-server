package io.github.atengk.solid.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.web.annotation.OperateLog;
import io.github.atengk.solid.system.request.PostQueryRequest;
import io.github.atengk.solid.system.request.PostSaveRequest;
import io.github.atengk.solid.system.request.StatusUpdateRequest;
import io.github.atengk.solid.system.service.SysPostService;
import io.github.atengk.solid.system.vo.PostOptionVO;
import io.github.atengk.solid.system.vo.PostVO;
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
 * 岗位管理控制器，提供岗位分页检索、下拉选项及增删改启停接口（路由前缀：{@code /admin/system/post}）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@RestController
@RequestMapping("/admin/system/post")
@RequiredArgsConstructor
public class SysPostController {

    private final SysPostService sysPostService;

    /**
     * 分页查询当前工作租户下的岗位列表。
     *
     * @param query 分页与筛选参数
     * @return 岗位分页结果
     */
    @SaCheckPermission("system:post:list")
    @GetMapping("/page")
    public Result<PageResult<PostVO>> page(@Valid PostQueryRequest query) {
        return Result.success(sysPostService.pagePosts(query));
    }

    /**
     * 获取当前工作租户下正常启用状态的岗位下拉选项。
     *
     * @return 岗位选项列表
     */
    @SaCheckLogin
    @GetMapping("/options")
    public Result<List<PostOptionVO>> options() {
        return Result.success(sysPostService.listActiveOptions());
    }

    /**
     * 查询指定岗位详情。
     *
     * @param id 岗位主键
     * @return 岗位详情
     */
    @SaCheckPermission("system:post:list")
    @GetMapping("/{id}")
    public Result<PostVO> detail(@PathVariable Long id) {
        return Result.success(sysPostService.getDetail(id));
    }

    /**
     * 新增岗位。
     *
     * @param request 岗位保存请求
     * @return 新建岗位主键
     */
    @SaCheckPermission("system:post:create")
    @OperateLog(module = "岗位管理", type = OperateTypeEnum.INSERT, description = "新增岗位")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody PostSaveRequest request) {
        return Result.success(sysPostService.createPost(request));
    }

    /**
     * 修改岗位。
     *
     * @param request 岗位修改请求
     * @return 空成功响应
     */
    @SaCheckPermission("system:post:update")
    @OperateLog(module = "岗位管理", type = OperateTypeEnum.UPDATE, description = "修改岗位")
    @PutMapping
    public Result<Void> update(@Valid @RequestBody PostSaveRequest request) {
        sysPostService.updatePost(request);
        return Result.success();
    }

    /**
     * 切换岗位启停状态。
     *
     * @param id      岗位主键
     * @param request 状态切换请求
     * @return 空成功响应
     */
    @SaCheckPermission("system:post:update")
    @OperateLog(module = "岗位管理", type = OperateTypeEnum.UPDATE, description = "切换岗位状态")
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody StatusUpdateRequest request
    ) {
        sysPostService.updatePostStatus(id, request.getStatus());
        return Result.success();
    }

    /**
     * 批量删除岗位。
     *
     * @param ids 逗号分隔的岗位主键串
     * @return 空成功响应
     */
    @SaCheckPermission("system:post:delete")
    @OperateLog(module = "岗位管理", type = OperateTypeEnum.DELETE, description = "删除岗位")
    @DeleteMapping("/{ids}")
    public Result<Void> delete(@PathVariable String ids) {
        sysPostService.deletePosts(ids);
        return Result.success();
    }
}

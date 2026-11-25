package io.github.atengk.solid.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.solid.system.request.LoginLogQueryRequest;
import io.github.atengk.solid.system.request.OperLogQueryRequest;
import io.github.atengk.solid.system.service.SysLogQueryService;
import io.github.atengk.solid.system.vo.LoginLogVO;
import io.github.atengk.solid.system.vo.OperLogVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计日志严格只读控制器，提供操作审计日志分页/详情与登录审计日志分页查询接口（路由前缀：{@code /admin/system/log}），
 * 严禁提供任何新增、编辑、删除或清空端点。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@RestController
@RequestMapping("/admin/system/log")
@RequiredArgsConstructor
public class SysLogController {

    private final SysLogQueryService sysLogQueryService;

    /**
     * 分页检索操作审计日志。
     *
     * @param query 分页与筛选参数
     * @return 操作日志分页结果
     */
    @SaCheckPermission("system:oper-log:list")
    @GetMapping("/oper/page")
    public Result<PageResult<OperLogVO>> pageOperLogs(@Valid OperLogQueryRequest query) {
        return Result.success(sysLogQueryService.pageOperLogs(query));
    }

    /**
     * 查询单条操作审计日志详情。
     *
     * @param id 操作日志主键
     * @return 操作日志详情
     */
    @SaCheckPermission("system:oper-log:list")
    @GetMapping("/oper/{id}")
    public Result<OperLogVO> operLogDetail(@PathVariable Long id) {
        return Result.success(sysLogQueryService.getOperLogDetail(id));
    }

    /**
     * 分页检索登录审计日志。
     *
     * @param query 分页与筛选参数
     * @return 登录日志分页结果
     */
    @SaCheckPermission("system:login-log:list")
    @GetMapping("/login/page")
    public Result<PageResult<LoginLogVO>> pageLoginLogs(@Valid LoginLogQueryRequest query) {
        return Result.success(sysLogQueryService.pageLoginLogs(query));
    }
}

package io.github.atengk.solid.auth.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.web.annotation.OperateLog;
import io.github.atengk.solid.auth.request.OnlineSessionQuery;
import io.github.atengk.solid.auth.service.OnlineSessionService;
import io.github.atengk.solid.auth.vo.OnlineSessionVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端在线会话治理控制器，提供工作租户内在线会话只读分页检索与不透明句柄强退能力（路由前缀：{@code /admin/auth/online}）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@RestController
@RequestMapping("/admin/auth/online")
@RequiredArgsConstructor
public class OnlineSessionController {

    private final OnlineSessionService onlineSessionService;

    /**
     * 分页查询当前工作租户的在线管理端会话列表。
     *
     * @param query 分页与筛选参数
     * @return 在线会话分页结果
     */
    @SaCheckPermission("auth:online:list")
    @GetMapping("/page")
    public Result<PageResult<OnlineSessionVO>> page(@Valid OnlineSessionQuery query) {
        return Result.success(onlineSessionService.page(query));
    }

    /**
     * 强退指定不透明会话句柄对应的在线会话。
     *
     * @param sessionId 服务端签密的不透明会话句柄
     * @return 空成功响应
     */
    @SaCheckPermission("auth:online:kickout")
    @OperateLog(module = "在线会话", type = OperateTypeEnum.DELETE, description = "强退在线会话")
    @DeleteMapping("/{sessionId}")
    public Result<Void> kickout(@PathVariable String sessionId) {
        onlineSessionService.kickout(sessionId);
        return Result.success();
    }
}

package io.github.atengk.solid.system.service;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.solid.common.result.CommonBizResultCode;
import io.github.atengk.solid.system.properties.SolidSystemProperties;
import io.github.atengk.solid.system.request.UserExportRequest;
import io.github.atengk.solid.system.security.SolidDataPermissionHandler;
import io.github.atengk.solid.system.service.SysUserExcelService.ExportContext;
import io.github.atengk.solid.system.service.UserImportValidator.ValidatedImportRow;
import io.github.atengk.solid.system.support.ImportFeedbackStore;
import io.github.atengk.solid.system.support.RbacPrivilegeGuard;
import io.github.atengk.solid.system.support.SystemContextSupport;
import io.github.atengk.solid.system.support.UserContactAndDictSupport;
import io.github.atengk.solid.system.support.UserContactAndDictSupport.GenderDictionary;
import io.github.atengk.solid.system.support.UserImportSemaphoreCoordinator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SysUserExcelService} 错误批注文件名安全净化与并行哈希失败/超时撤回单元测试。
 *
 * @author Ateng
 * @since 2026-11-27
 */
class SysUserExcelServiceTest {

    @Test
    @DisplayName("buildFeedbackFilename 应当清洗路径穿越/控制/不安全字符，空主干回落默认名，并截断至 64 字符以内")
    void shouldSanitizeControlCharsPathSeparatorsAndTruncateFeedbackFilename() {
        assertThat(SysUserExcelService.buildFeedbackFilename(null)).isEqualTo("用户导入_错误批注.xlsx");
        assertThat(SysUserExcelService.buildFeedbackFilename("   ")).isEqualTo("用户导入_错误批注.xlsx");
        assertThat(SysUserExcelService.buildFeedbackFilename("///..///.xlsx")).isEqualTo("用户导入_错误批注.xlsx");
        assertThat(SysUserExcelService.buildFeedbackFilename("\r\n\0***???.xlsx")).isEqualTo("用户导入_错误批注.xlsx");

        String traversed = SysUserExcelService.buildFeedbackFilename("../../etc/passwd.xlsx");
        assertThat(traversed).isEqualTo("passwd_错误批注.xlsx");
        assertThat(traversed).doesNotContain("..").doesNotContain("/").doesNotContain("\\");

        String injected = SysUserExcelService.buildFeedbackFilename("C:\\fakepath\\团队..名单\r\n;\"<tag>.xlsx");
        assertThat(injected).doesNotContain("..")
                .doesNotContain("\\")
                .doesNotContain("/")
                .doesNotContain("\r")
                .doesNotContain("\n")
                .doesNotContain(";")
                .doesNotContain("\"")
                .doesNotContain("<")
                .doesNotContain(">")
                .endsWith("_错误批注.xlsx");

        String longStem = "一二三四五六七八九十".repeat(8) + ".xlsx";
        String truncated = SysUserExcelService.buildFeedbackFilename(longStem);
        assertThat(truncated).endsWith("_错误批注.xlsx");
        assertThat(truncated.length()).isLessThanOrEqualTo(64);
    }

    @Test
    @DisplayName("awaitHashTasks 超时或部分失败时应当主动取消全部未完成 Future 防止队列任务滞留")
    void shouldCancelIncompleteFuturesWhenParallelHashTasksFailOrTimeout() {
        ValidatedImportRow row1 = new ValidatedImportRow("u1", "用户1", 1001L, List.of(), List.of(), null, null, null, 0);
        ValidatedImportRow row2 = new ValidatedImportRow("u2", "用户2", 1001L, List.of(), List.of(), null, null, null, 0);

        CompletableFuture<String> completed = CompletableFuture.completedFuture("hashed-1");
        CompletableFuture<String> hungTimeout = new CompletableFuture<>();
        assertThatThrownBy(() -> SysUserExcelService.awaitHashTasks(
                List.of(row1, row2), List.of(completed, hungTimeout), 40, TimeUnit.MILLISECONDS
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(CommonResultCode.SERVICE_UNAVAILABLE.getCode()));
        assertThat(hungTimeout.isCancelled()).isTrue();

        CompletableFuture<String> failed = CompletableFuture.failedFuture(
                new BusinessException(CommonResultCode.BAD_REQUEST, "口令不合法")
        );
        CompletableFuture<String> hungOnFail = new CompletableFuture<>();
        assertThatThrownBy(() -> SysUserExcelService.awaitHashTasks(
                List.of(row1, row2), List.of(failed, hungOnFail), 5, TimeUnit.SECONDS
        ))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(CommonResultCode.BAD_REQUEST.getCode()));
        assertThat(hungOnFail.isCancelled()).isTrue();

        assertThat(SysUserExcelService.awaitHashTasks(
                Collections.emptyList(), Collections.emptyList(), 1, TimeUnit.SECONDS
        )).isEmpty();
    }

    @Test
    @DisplayName("prepareExportContext 应当预解析并缓存部门子树，且在总行数超限时拦截抛出 100001")
    void shouldPreCacheDeptSubtreeAndRejectOverLimitInPrepareExportContext() {
        SystemContextSupport contextSupport = mock(SystemContextSupport.class);
        UserContactAndDictSupport contactAndDictSupport = mock(UserContactAndDictSupport.class);
        SysUserQueryService queryService = mock(SysUserQueryService.class);
        SysUserExcelService excelService = new SysUserExcelService(
                contextSupport,
                new SolidSystemProperties(),
                mock(UserImportValidator.class),
                mock(UserBatchPersistHelper.class),
                mock(ImportFeedbackStore.class),
                contactAndDictSupport,
                mock(UserImportSemaphoreCoordinator.class),
                mock(SolidDataPermissionHandler.class),
                mock(RbacPrivilegeGuard.class),
                queryService
        );

        when(contextSupport.requireEffectiveTenantId()).thenReturn(0L);
        when(queryService.resolveDeptSubtreeIds(0L, 100L)).thenReturn(List.of(100L, 101L, 102L));
        when(queryService.countUsersForExport(eq(0L), any(UserExportRequest.class), eq(List.of(100L, 101L, 102L))))
                .thenReturn(2L)
                .thenReturn(3L);
        when(contactAndDictSupport.loadGenderDictionary())
                .thenReturn(new GenderDictionary(Set.of("0"), Map.of("男", "0"), Map.of("0", "男"), "2"));

        UserExportRequest request = new UserExportRequest();
        request.setDeptId(100L);

        ExportContext ctx = excelService.prepareExportContext(request, 2L);
        assertThat(ctx.tenantId()).isZero();
        assertThat(ctx.deptSubtreeIds()).containsExactly(100L, 101L, 102L);
        assertThat(ctx.genderCodeToLabel()).containsEntry("0", "男");

        assertThatThrownBy(() -> excelService.prepareExportContext(request, 2L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(CommonBizResultCode.PARAM_INVALID.getCode()));

        verify(queryService, times(2)).resolveDeptSubtreeIds(0L, 100L);
        verify(contactAndDictSupport, times(1)).loadGenderDictionary();
        Mockito.verifyNoMoreInteractions(contactAndDictSupport);
    }
}

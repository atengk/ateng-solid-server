package io.github.atengk.solid.system.audit;

import io.github.atengk.boot.core.audit.OperateLogModel;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.solid.system.entity.SysOperLog;
import io.github.atengk.solid.system.mapper.SysOperLogMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.support.SystemContextSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SolidOperateLogStorageProvider} 脱离 HTTP/Sa-Token 上下文异步操作审计落库单元测试。
 *
 * @author Ateng
 * @since 2026-11-25
 */
class SolidOperateLogStorageProviderTest {

    private SysOperLogMapper sysOperLogMapper;
    private SysUserMapper sysUserMapper;
    private SolidOperateLogStorageProvider provider;

    @BeforeEach
    void setUp() {
        sysOperLogMapper = Mockito.mock(SysOperLogMapper.class);
        sysUserMapper = Mockito.mock(SysUserMapper.class);
        provider = new SolidOperateLogStorageProvider(sysOperLogMapper, sysUserMapper);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        DataPermissionContextHolder.clear();
        MDC.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("脱离 HTTP/Sa-Token 上下文的独立线程应当通过主键查询补全真实 userName 并完整落库")
    void shouldResolveRealUsernameInDetachedThreadAndPersistAllAuditFields() {
        when(sysUserMapper.selectUsernameById(9001L)).thenAnswer(inv -> {
            assertThat(TenantContextHolder.getTenantIdAsLong()).isEqualTo(2001L);
            assertThat(DataPermissionContextHolder.isIgnore()).isTrue();
            return "alice";
        });

        LocalDateTime operateTime = LocalDateTime.of(2026, 11, 25, 14, 30, 0);
        OperateLogModel model = OperateLogModel.builder()
                .traceId("trace-detached-1")
                .tenantId("2001")
                .userId("9001")
                .userName("9001")
                .module("岗位管理")
                .operateType(OperateTypeEnum.UPDATE)
                .description("修改岗位状态")
                .requestMethod("PUT")
                .requestUrl("/admin/system/post/1/status")
                .clientIp("10.10.1.25")
                .browser("Chrome 130")
                .os("Windows 10")
                .requestParams("{\"status\":1}")
                .responseResult("{\"code\":200}")
                .status(0)
                .costTime(42L)
                .extra(Map.of("targetId", "1"))
                .operateTime(operateTime)
                .build();

        // 在完全无 HTTP/Sa-Token 上下文的独立异步线程中执行
        CompletableFuture.runAsync(() -> {
            RequestContextHolder.resetRequestAttributes();
            provider.store(model);
        }).join();

        ArgumentCaptor<SysOperLog> captor = ArgumentCaptor.forClass(SysOperLog.class);
        verify(sysOperLogMapper).insert(captor.capture());
        SysOperLog saved = captor.getValue();

        assertThat(saved.getTenantId()).isEqualTo(2001L);
        assertThat(saved.getTraceId()).isEqualTo("trace-detached-1");
        assertThat(saved.getUserId()).isEqualTo(9001L);
        assertThat(saved.getUserName()).isEqualTo("alice");
        assertThat(saved.getModule()).isEqualTo("岗位管理");
        assertThat(saved.getOperateType()).isEqualTo(OperateTypeEnum.UPDATE.getCode());
        assertThat(saved.getDescription()).isEqualTo("修改岗位状态");
        assertThat(saved.getRequestMethod()).isEqualTo("PUT");
        assertThat(saved.getRequestUrl()).isEqualTo("/admin/system/post/1/status");
        assertThat(saved.getClientIp()).isEqualTo("10.10.1.25");
        assertThat(saved.getBrowser()).isEqualTo("Chrome 130");
        assertThat(saved.getOs()).isEqualTo("Windows 10");
        assertThat(saved.getRequestParams()).isEqualTo("{\"status\":1}");
        assertThat(saved.getResponseResult()).isEqualTo("{\"code\":200}");
        assertThat(saved.getStatus()).isEqualTo(0);
        assertThat(saved.getCostTime()).isEqualTo(42L);
        assertThat(saved.getExtraJson()).contains("targetId");
        assertThat(saved.getOperateTime()).isEqualTo(operateTime);
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(DataPermissionContextHolder.isIgnore()).isFalse();
    }

    @Test
    @DisplayName("超管跨租户操作应当兜底查平台租户 0 用户名，查无或空模型时安全兜底")
    void shouldFallbackToPlatformTenantOrModelUserNameAndIgnoreNullModel() {
        provider.store(null);
        verify(sysOperLogMapper, never()).insert(any(SysOperLog.class));

        when(sysUserMapper.selectUsernameById(1L)).thenAnswer(inv -> {
            if (Long.valueOf(0L).equals(TenantContextHolder.getTenantIdAsLong())) {
                return "superadmin";
            }
            return null;
        });

        OperateLogModel switchedModel = OperateLogModel.builder()
                .tenantId("2001")
                .userId("1")
                .userName("1")
                .module("系统参数")
                .operateType(OperateTypeEnum.INSERT)
                .status(0)
                .build();
        provider.store(switchedModel);

        ArgumentCaptor<SysOperLog> captor = ArgumentCaptor.forClass(SysOperLog.class);
        verify(sysOperLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(2001L);
        assertThat(captor.getValue().getUserName()).isEqualTo("superadmin");

        when(sysUserMapper.selectUsernameById(404L)).thenReturn(null);
        OperateLogModel missingUserModel = OperateLogModel.builder()
                .tenantId("0")
                .userId("404")
                .userName("fallback-actor")
                .module("测试")
                .operateType(OperateTypeEnum.OTHER)
                .status(1)
                .errorMsg("业务异常")
                .build();
        provider.store(missingUserModel);

        verify(sysOperLogMapper, Mockito.times(2)).insert(captor.capture());
        SysOperLog second = captor.getValue();
        assertThat(second.getUserName()).isEqualTo("fallback-actor");
        assertThat(second.getErrorMsg()).isEqualTo("业务异常");
    }

    @Test
    @DisplayName("操作审计失败消息中的原始 SQL 与堆栈应当自动脱敏，合法业务提示保持原样")
    void shouldSanitizeRawSqlAndStackTraceInAuditErrorMsg() {
        String masked = "系统内部处理异常，详情请检索服务端日志";
        assertThat(SystemContextSupport.sanitizeAuditErrorMsg("   ")).isNull();
        assertThat(SystemContextSupport.sanitizeAuditErrorMsg("岗位名称已存在")).isEqualTo("岗位名称已存在");
        assertThat(SystemContextSupport.sanitizeAuditErrorMsg("SELECT id, password FROM sys_user WHERE id = 1"))
                .isEqualTo(masked);
        assertThat(SystemContextSupport.sanitizeAuditErrorMsg("java.sql.SQLException: Syntax error\n\tat Foo.bar(Foo.java:10)"))
                .isEqualTo(masked);

        provider.store(OperateLogModel.builder()
                .tenantId("0")
                .userId("1")
                .module("系统参数")
                .operateType(OperateTypeEnum.UPDATE)
                .status(1)
                .errorMsg("SELECT config_value FROM sys_config WHERE tenant_id = 0")
                .build());
        provider.store(OperateLogModel.builder()
                .tenantId("0")
                .userId("1")
                .module("系统参数")
                .operateType(OperateTypeEnum.UPDATE)
                .status(1)
                .errorMsg("RuntimeException: fail\n\tat io.github.atengk.Test.call(Test.java:12)")
                .build());

        ArgumentCaptor<SysOperLog> captor = ArgumentCaptor.forClass(SysOperLog.class);
        verify(sysOperLogMapper, Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(SysOperLog::getErrorMsg).containsExactly(masked, masked);
    }

    @Test
    @DisplayName("非数字租户或用户上下文标识应当安全回退而不中断异步审计落库")
    void shouldFallbackGracefullyWhenTenantIdOrUserIdNotNumeric() {
        provider.store(OperateLogModel.builder()
                .tenantId("not-a-tenant")
                .userId("not-a-user")
                .userName("raw-actor")
                .module("数据字典")
                .operateType(OperateTypeEnum.OTHER)
                .status(0)
                .build());

        verify(sysUserMapper, never()).selectUsernameById(any());
        ArgumentCaptor<SysOperLog> captor = ArgumentCaptor.forClass(SysOperLog.class);
        verify(sysOperLogMapper).insert(captor.capture());
        SysOperLog saved = captor.getValue();
        assertThat(saved.getTenantId()).isEqualTo(0L);
        assertThat(saved.getUserId()).isNull();
        assertThat(saved.getUserName()).isEqualTo("raw-actor");
    }
}

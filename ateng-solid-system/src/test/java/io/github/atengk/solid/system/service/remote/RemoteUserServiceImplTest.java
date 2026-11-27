package io.github.atengk.solid.system.service.remote;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.model.StorageUploadRequest;
import io.github.atengk.boot.storage.template.StorageOperations;
import io.github.atengk.solid.system.api.command.UploadAvatarCommand;
import io.github.atengk.solid.system.api.dto.AvatarUploadDTO;
import io.github.atengk.solid.system.api.service.RemoteTenantService;
import io.github.atengk.solid.system.entity.SysUser;
import io.github.atengk.solid.system.mapper.SysDeptMapper;
import io.github.atengk.solid.system.mapper.SysUserMapper;
import io.github.atengk.solid.system.mapper.SysUserPostMapper;
import io.github.atengk.solid.system.mapper.SysUserRoleMapper;
import io.github.atengk.solid.system.support.AvatarStorageHelper;
import io.github.atengk.solid.system.support.AvatarUrlResolver;
import io.github.atengk.solid.system.support.UserContactAndDictSupport;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Base64;
import java.util.function.Consumer;

import static io.github.atengk.solid.system.support.CacheTestFixtures.providerOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RemoteUserServiceImpl} 事务外对象存储头像上传、行锁更新与事务失败静默补偿单元测试。
 *
 * @author Ateng
 * @since 2026-11-27
 */
class RemoteUserServiceImplTest {

    private static final byte[] VALID_PNG_BYTES = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    );

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SysUser.class);
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    private static UploadAvatarCommand pngCommand() {
        return UploadAvatarCommand.builder()
                .originalFilename("avatar.png")
                .contentType("image/png")
                .content(VALID_PNG_BYTES)
                .build();
    }

    @Test
    @DisplayName("uploadAvatar 应当在事务外执行对象上传，事务提交失败时静默补偿删除新上传对象并原样重抛异常")
    void shouldUploadAvatarOutsideDbTransactionAndCompensateOnTxFailure() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysUser user = new SysUser();
        user.setId(99L);
        user.setTenantId(1001L);
        user.setAvatar("avatar/1001/99/old.png");
        when(userMapper.selectOne(any())).thenReturn(user);

        StorageOperations storage = mock(StorageOperations.class);
        when(storage.upload(any(StorageUploadRequest.class))).thenAnswer(inv -> {
            StorageUploadRequest req = inv.getArgument(0);
            return StorageObject.builder().path(req.getPath()).build();
        });
        doThrow(new IllegalStateException("storage delete also failed")).when(storage).delete(anyString());

        AvatarUrlResolver urlResolver = mock(AvatarUrlResolver.class);
        AvatarStorageHelper avatarHelper = new AvatarStorageHelper(
                providerOf(storage), providerOf(null), urlResolver
        );

        TransactionTemplate txTemplate = mock(TransactionTemplate.class);
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> callback = inv.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            throw new IllegalStateException("db commit failed");
        }).when(txTemplate).executeWithoutResult(any());

        RemoteUserServiceImpl service = new RemoteUserServiceImpl(
                userMapper,
                mock(SysDeptMapper.class),
                mock(SysUserRoleMapper.class),
                mock(SysUserPostMapper.class),
                mock(RemoteTenantService.class),
                avatarHelper,
                mock(UserContactAndDictSupport.class),
                txTemplate
        );

        assertThatThrownBy(() -> service.uploadAvatar(1001L, 99L, pngCommand()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("db commit failed");

        ArgumentCaptor<StorageUploadRequest> uploadCaptor = ArgumentCaptor.forClass(StorageUploadRequest.class);
        InOrder ordered = inOrder(storage, txTemplate);
        ordered.verify(storage).upload(uploadCaptor.capture());
        ordered.verify(txTemplate).executeWithoutResult(any());

        String uploadedPath = uploadCaptor.getValue().getPath();
        assertThat(uploadedPath).matches("avatar/1001/99/[a-f0-9]+\\.png");
        verify(storage, never()).uploadWithRollbackDelete(any());
        verify(storage).delete(eq(uploadedPath));
    }

    @Test
    @DisplayName("uploadAvatar 事务成功提交后应当注册旧头像延迟删除并绝不触发新对象回滚删除")
    void shouldLockUserForUpdateAndScheduleOldAvatarDeletionAfterCommit() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysUser user = new SysUser();
        user.setId(99L);
        user.setTenantId(1001L);
        user.setAvatar("avatar/1001/99/old.png");
        when(userMapper.selectOne(any())).thenReturn(user);

        StorageOperations storage = mock(StorageOperations.class);
        when(storage.upload(any(StorageUploadRequest.class))).thenAnswer(inv -> {
            StorageUploadRequest req = inv.getArgument(0);
            return StorageObject.builder().path(req.getPath()).build();
        });

        AvatarUrlResolver urlResolver = mock(AvatarUrlResolver.class);
        when(urlResolver.resolve(anyString())).thenAnswer(inv -> "https://cdn.example.com/" + inv.getArgument(0));
        AvatarStorageHelper avatarHelper = new AvatarStorageHelper(
                providerOf(storage), providerOf(null), urlResolver
        );

        TransactionTemplate txTemplate = mock(TransactionTemplate.class);
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> callback = inv.getArgument(0);
            callback.accept(mock(TransactionStatus.class));
            return null;
        }).when(txTemplate).executeWithoutResult(any());

        RemoteUserServiceImpl service = new RemoteUserServiceImpl(
                userMapper,
                mock(SysDeptMapper.class),
                mock(SysUserRoleMapper.class),
                mock(SysUserPostMapper.class),
                mock(RemoteTenantService.class),
                avatarHelper,
                mock(UserContactAndDictSupport.class),
                txTemplate
        );

        AvatarUploadDTO dto = service.uploadAvatar(1001L, 99L, pngCommand());
        assertThat(dto.getObjectPath()).matches("avatar/1001/99/[a-f0-9]+\\.png");
        assertThat(dto.getAvatarUrl()).isEqualTo("https://cdn.example.com/" + dto.getObjectPath());

        verify(storage, never()).delete(anyString());
        verify(storage).deleteAfterCommit(eq("avatar/1001/99/old.png"));
    }
}

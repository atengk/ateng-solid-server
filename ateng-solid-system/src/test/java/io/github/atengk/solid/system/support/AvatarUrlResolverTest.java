package io.github.atengk.solid.system.support;

import io.github.atengk.boot.storage.model.PresignedDownloadRequest;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.template.StorageOperations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AvatarUrlResolver} 头像地址动态签发与降级逻辑 Layer 1 单元测试。
 *
 * @author Ateng
 * @since 2026-11-25
 */
class AvatarUrlResolverTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StorageOperations> providerOf(StorageOperations storageOperations) {
        ObjectProvider<StorageOperations> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(storageOperations);
        return provider;
    }

    @Test
    @DisplayName("空值与空白头像路径应当直接返回 null 且不触发对象存储调用")
    void shouldReturnNullForBlankAvatarKey() {
        StorageOperations storage = mock(StorageOperations.class);
        AvatarUrlResolver resolver = new AvatarUrlResolver(providerOf(storage));

        assertThat(resolver.resolve(null)).isNull();
        assertThat(resolver.resolve("")).isNull();
        assertThat(resolver.resolve("   \t\n  ")).isNull();
        verify(storage, never()).getPresignedUrl(any(PresignedDownloadRequest.class));
    }

    @Test
    @DisplayName("http 与 https 外链头像应当原样透传且不签发预签名地址")
    void shouldPassThroughExternalHttpUrls() {
        StorageOperations storage = mock(StorageOperations.class);
        AvatarUrlResolver resolver = new AvatarUrlResolver(providerOf(storage));

        assertThat(resolver.resolve("https://cdn.example.com/avatar/u1.png"))
                .isEqualTo("https://cdn.example.com/avatar/u1.png");
        assertThat(resolver.resolve("  HTTP://img.example.com/u2.jpg  "))
                .isEqualTo("HTTP://img.example.com/u2.jpg");
        verify(storage, never()).getPresignedUrl(any(PresignedDownloadRequest.class));
    }

    @Test
    @DisplayName("对象存储未启用时应当优雅降级返回原始相对对象键")
    void shouldFallbackToObjectKeyWhenStorageBeanAbsent() {
        AvatarUrlResolver resolver = new AvatarUrlResolver(providerOf(null));

        assertThat(resolver.resolve("avatar/0/1/demo.png")).isEqualTo("avatar/0/1/demo.png");
    }

    @Test
    @DisplayName("正常相对对象键应当签发 2 小时有效期及 inline 预览响应头")
    void shouldSignTwoHourInlinePresignedUrlWhenStorageEnabled() {
        StorageOperations storage = mock(StorageOperations.class);
        when(storage.getPresignedUrl(any(PresignedDownloadRequest.class)))
                .thenReturn(PresignedUrl.builder().url("https://minio.local/avatar/0/1/demo.png?sig=1").build());
        AvatarUrlResolver resolver = new AvatarUrlResolver(providerOf(storage));

        String resolved = resolver.resolve("avatar/0/1/demo.png");

        assertThat(resolved).isEqualTo("https://minio.local/avatar/0/1/demo.png?sig=1");
        ArgumentCaptor<PresignedDownloadRequest> captor = ArgumentCaptor.forClass(PresignedDownloadRequest.class);
        verify(storage).getPresignedUrl(captor.capture());
        PresignedDownloadRequest req = captor.getValue();
        assertThat(req.getPath()).isEqualTo("avatar/0/1/demo.png");
        assertThat(req.getDuration()).isEqualTo(Duration.ofHours(2));
        assertThat(req.getResponseContentDisposition()).isEqualTo("inline");
    }

    @Test
    @DisplayName("预签名签发抛出异常或返回空 URL 时应当优雅降级返回相对对象键")
    void shouldFallbackToObjectKeyWhenPresignFailsOrEmpty() {
        StorageOperations storage = mock(StorageOperations.class);
        when(storage.getPresignedUrl(any(PresignedDownloadRequest.class)))
                .thenThrow(new RuntimeException("S3 offline"))
                .thenReturn(PresignedUrl.builder().url("").build());
        AvatarUrlResolver resolver = new AvatarUrlResolver(providerOf(storage));

        assertThat(resolver.resolve("avatar/0/1/err.png")).isEqualTo("avatar/0/1/err.png");
        assertThat(resolver.resolve("avatar/0/1/empty.png")).isEqualTo("avatar/0/1/empty.png");
    }
}

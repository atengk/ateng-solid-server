package io.github.atengk.solid.system.api.command;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;

/**
 * 头像上传跨模块指令（严禁使用 {@code MultipartFile} 等 Web 容器类型，以保持契约层纯净与 RPC 可序列化）。
 *
 * @author Ateng
 * @since 2026-11-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "content")
public class UploadAvatarCommand implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 客户端上传时的原始文件名（含扩展名）
     */
    private String originalFilename;

    /**
     * 请求头声明的 MIME Content-Type
     */
    private String contentType;

    /**
     * 头像文件原始字节载荷
     */
    private byte[] content;
}

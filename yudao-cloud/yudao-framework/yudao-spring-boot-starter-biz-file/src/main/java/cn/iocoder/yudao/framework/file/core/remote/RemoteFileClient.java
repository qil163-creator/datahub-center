package cn.iocoder.yudao.framework.file.core.remote;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** 每次请求独占一个客户端并关闭；调用方拥有传入的流，组件不会关闭它们。 */
public interface RemoteFileClient extends AutoCloseable {
    List<RemoteFileEntry> list(String path) throws IOException;
    RemoteFileEntry stat(String path) throws IOException;
    default void upload(String path, InputStream input, long size) throws IOException {
        upload(path, input, size, false);
    }
    void upload(String path, InputStream input, long size, boolean overwrite) throws IOException;
    void download(String path, OutputStream output, long maxBytes) throws IOException;
    void rename(String path, String target) throws IOException;
    void mkdir(String path) throws IOException;
    /** 仅删除文件或空目录；禁止删除配置根目录。 */
    void delete(String path) throws IOException;
    @Override
    void close() throws IOException;
}

package cn.iocoder.yudao.framework.file.core.client.local;

import cn.iocoder.yudao.framework.file.core.client.AbstractFileClient;
import cn.iocoder.yudao.framework.file.core.client.FileStoragePaths;
import cn.iocoder.yudao.framework.file.core.client.FileUrlResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 本地存储；每次操作重新检查根目录边界，不递归删除。 */
public class LocalFileClient extends AbstractFileClient<LocalFileClientConfig> {

    public LocalFileClient(Long id, LocalFileClientConfig config) {
        super(id, config);
    }

    public LocalFileClient(Long id, LocalFileClientConfig config, FileUrlResolver urls) {
        super(id, config, urls);
    }

    @Override
    protected void doInit() { }

    @Override
    public String upload(byte[] content, String path, String type) throws IOException {
        Path target = FileStoragePaths.resolveLocal(config.getBasePath(), path);
        Files.createDirectories(target.getParent());
        Files.write(FileStoragePaths.resolveLocal(config.getBasePath(), path), content,
                LinkOption.NOFOLLOW_LINKS, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return formatFileUrl(config.getDomain(), path);
    }

    @Override
    public void delete(String path) throws IOException {
        Files.deleteIfExists(FileStoragePaths.resolveLocal(config.getBasePath(), path));
    }

    @Override
    public byte[] getContent(String path) throws IOException {
        Path target = FileStoragePaths.resolveLocal(config.getBasePath(), path);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try (var input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
            byte[] content = input.readNBytes(32 * 1024 * 1024 + 1);
            if (content.length > 32 * 1024 * 1024) {
                throw new IOException("文件超过内存读取上限");
            }
            return content;
        }
    }
}

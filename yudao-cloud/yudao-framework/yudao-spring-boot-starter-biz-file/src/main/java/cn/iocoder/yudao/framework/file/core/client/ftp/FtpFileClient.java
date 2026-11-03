package cn.iocoder.yudao.framework.file.core.client.ftp;

import cn.iocoder.yudao.framework.file.core.client.AbstractFileClient;
import cn.iocoder.yudao.framework.file.core.client.DefaultFileUrlResolver;
import cn.iocoder.yudao.framework.file.core.client.FileStoragePaths;
import cn.iocoder.yudao.framework.file.core.client.FileUrlResolver;
import cn.iocoder.yudao.framework.file.core.remote.DefaultRemoteFileClientFactory;
import cn.iocoder.yudao.framework.file.core.remote.RemoteFileClient;
import cn.iocoder.yudao.framework.file.core.remote.RemoteFileClientFactory;
import cn.iocoder.yudao.framework.file.core.remote.RemoteFileConfig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Set;

/** 通过共享远程文件组件执行 FTP，每次操作独占并关闭连接。 */
public class FtpFileClient extends AbstractFileClient<FtpFileClientConfig> {

    private final RemoteFileClientFactory factory;

    public FtpFileClient(Long id, FtpFileClientConfig config) {
        this(id, config, new DefaultFileUrlResolver(), new DefaultRemoteFileClientFactory());
    }

    public FtpFileClient(Long id, FtpFileClientConfig config, FileUrlResolver urls, RemoteFileClientFactory factory) {
        super(id, config, urls);
        this.factory = factory;
    }

    @Override
    protected void doInit() {
        connection();
    }

    private RemoteFileConfig connection() {
        if (!Set.of("Active", "Passive", "ACTIVE", "PASSIVE").contains(config.getMode())) {
            throw new IllegalArgumentException("FTP连接模式不合法");
        }
        return new RemoteFileConfig(RemoteFileConfig.Protocol.FTP, config.getHost(), config.getPort(),
                config.getUsername(), config.getPassword(), config.getBasePath(), null, Set.of(),
                config.getMode().equalsIgnoreCase("PASSIVE"));
    }

    private static String path(String key) {
        return "/" + FileStoragePaths.validate(key);
    }

    @Override
    public String upload(byte[] content, String key, String type) throws IOException {
        String path = path(key);
        try (RemoteFileClient client = factory.open(connection())) {
            String[] parts = key.split("/");
            String directory = "";
            for (int i = 0; i < parts.length - 1; i++) {
                directory += "/" + parts[i];
                try {
                    client.stat(directory);
                } catch (FileNotFoundException absent) {
                    client.mkdir(directory);
                }
            }
            client.upload(path, new ByteArrayInputStream(content), content.length, true);
        }
        return formatFileUrl(config.getDomain(), key);
    }

    @Override
    public byte[] getContent(String key) throws IOException {
        String path = path(key);
        try (RemoteFileClient client = factory.open(connection())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            client.download(path, out, 32L * 1024 * 1024);
            return out.toByteArray();
        } catch (FileNotFoundException absent) {
            return null;
        }
    }

    @Override
    public void delete(String key) throws IOException {
        String path = path(key);
        try (RemoteFileClient client = factory.open(connection())) {
            client.delete(path);
        }
    }
}

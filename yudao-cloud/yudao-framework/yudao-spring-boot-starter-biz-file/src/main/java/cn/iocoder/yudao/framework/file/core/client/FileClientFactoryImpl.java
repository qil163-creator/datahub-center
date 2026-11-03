package cn.iocoder.yudao.framework.file.core.client;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.file.core.client.ftp.FtpFileClient;
import cn.iocoder.yudao.framework.file.core.client.ftp.FtpFileClientConfig;
import cn.iocoder.yudao.framework.file.core.client.local.LocalFileClient;
import cn.iocoder.yudao.framework.file.core.client.local.LocalFileClientConfig;
import cn.iocoder.yudao.framework.file.core.client.s3.S3FileClient;
import cn.iocoder.yudao.framework.file.core.client.s3.S3FileClientConfig;
import cn.iocoder.yudao.framework.file.core.client.sftp.SftpFileClient;
import cn.iocoder.yudao.framework.file.core.client.sftp.SftpFileClientConfig;
import cn.iocoder.yudao.framework.file.core.remote.RemoteFileClientFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** 线程安全的客户端注册表；配置替换与关闭串行，日志不包含连接配置。 */
public class FileClientFactoryImpl implements FileClientFactory {

    private record Registration(Integer storage, FileClientConfig config, ManagedFileClient client) { }

    private final Map<Long, Registration> clients = new ConcurrentHashMap<>();
    private final List<FileClientProvider> providers;
    private final FileUrlResolver urls;
    private final RemoteFileClientFactory remote;
    private boolean closed;

    public FileClientFactoryImpl(List<FileClientProvider> providers, FileUrlResolver urls, RemoteFileClientFactory remote) {
        this.providers = List.copyOf(providers);
        this.urls = Objects.requireNonNull(urls);
        this.remote = Objects.requireNonNull(remote);
    }

    @Override
    public FileClient getFileClient(Long id) {
        Registration entry = clients.get(id);
        return entry == null ? null : entry.client();
    }

    @Override
    public synchronized <C extends FileClientConfig> void createOrUpdateFileClient(Long id, Integer storage, C config) {
        if (closed) {
            throw new IllegalStateException("文件客户端工厂已关闭");
        }
        Registration current = clients.get(id);
        if (current != null && current.storage().equals(storage) && current.config().equals(config)) {
            return;
        }
        // 在初始化连接前保存快照，复制失败不留下已创建的资源。
        FileClientConfig snapshot = JsonUtils.parseObject2(JsonUtils.toJsonString(config), config.getClass());
        FileClient created = create(id, storage, snapshot);
        try {
            if (created instanceof AbstractFileClient<?> client) {
                client.init();
            }
        } catch (RuntimeException failure) {
            try {
                created.close();
            } catch (Exception ignored) {
                // 保留初始化异常，不输出可能含凭据的关闭异常。
            }
            throw failure;
        }
        ManagedFileClient managed = current == null ? new ManagedFileClient(created) : current.client();
        if (current != null) {
            // 即使旧资源关闭失败，也登记已切换的新配置，避免状态与句柄不一致。
            try {
                managed.replace(created);
            } finally {
                clients.put(id, new Registration(storage, snapshot, managed));
            }
        } else {
            clients.put(id, new Registration(storage, snapshot, managed));
        }
    }

    /** 稳定代理让外部缓存持有同一个句柄；替换等待正在进行的操作完成。 */
    private static final class ManagedFileClient implements FileClient {

        private FileClient delegate;
        private final Long id;

        ManagedFileClient(FileClient delegate) {
            this.delegate = delegate;
            this.id = delegate.getId();
        }

        synchronized void replace(FileClient replacement) {
            FileClient old = delegate;
            delegate = replacement;
            if (old != null) {
                closeClient(old);
            }
        }

        private FileClient current() {
            if (delegate == null) {
                throw new IllegalStateException("文件客户端已关闭");
            }
            return delegate;
        }

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public synchronized String upload(byte[] bytes, String path, String type) throws Exception {
            return current().upload(bytes, path, type);
        }

        @Override
        public synchronized byte[] getContent(String path) throws Exception {
            return current().getContent(path);
        }

        @Override
        public synchronized void delete(String path) throws Exception {
            current().delete(path);
        }

        @Override
        public synchronized String presignPutUrl(String path) {
            return current().presignPutUrl(path);
        }

        @Override
        public synchronized String presignGetUrl(String url, Integer expiration) {
            return current().presignGetUrl(url, expiration);
        }

        @Override
        public synchronized void close() {
            FileClient old = delegate;
            delegate = null;
            if (old != null) {
                closeClient(old);
            }
        }
    }

    private FileClient create(Long id, Integer storage, FileClientConfig config) {
        for (FileClientProvider provider : providers) {
            if (provider.supports(storage, config)) {
                return provider.create(id, config, urls);
            }
        }
        if (storage == 10 && config instanceof LocalFileClientConfig c) {
            return new LocalFileClient(id, c, urls);
        }
        if (storage == 11 && config instanceof FtpFileClientConfig c) {
            return new FtpFileClient(id, c, urls, remote);
        }
        if (storage == 12 && config instanceof SftpFileClientConfig c) {
            return new SftpFileClient(id, c, urls);
        }
        if (storage == 20 && config instanceof S3FileClientConfig c) {
            return new S3FileClient(id, c);
        }
        throw new IllegalArgumentException("不支持的文件存储配置");
    }

    @Override
    public synchronized void removeFileClient(Long id) {
        Registration old = clients.remove(id);
        if (old != null) {
            closeClient(old.client());
        }
    }

    private static void closeClient(FileClient client) {
        try {
            client.close();
        } catch (Exception e) {
            throw new IllegalStateException("关闭文件客户端失败");
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        RuntimeException failure = null;
        for (Registration entry : clients.values()) {
            try {
                closeClient(entry.client());
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        clients.clear();
        if (failure != null) {
            throw failure;
        }
    }
}

package cn.iocoder.yudao.framework.file.core.client;

/** 文件客户端注册表；由容器管理生命周期。 */
public interface FileClientFactory extends AutoCloseable {
    FileClient getFileClient(Long configId);

    <C extends FileClientConfig> void createOrUpdateFileClient(Long configId, Integer storage, C config);

    void removeFileClient(Long configId);

    @Override
    void close();
}

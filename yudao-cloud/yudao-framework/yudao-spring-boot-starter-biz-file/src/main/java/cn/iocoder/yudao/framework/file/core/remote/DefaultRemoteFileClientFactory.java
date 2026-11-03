package cn.iocoder.yudao.framework.file.core.remote;

import java.io.IOException;
import java.util.Objects;

/** 无状态工厂；仅在显式打开会话时连接远端，不缓存客户端或凭据。 */
public final class DefaultRemoteFileClientFactory implements RemoteFileClientFactory {

    @Override
    public RemoteFileClient open(RemoteFileConfig config) throws IOException {
        Objects.requireNonNull(config, "远程文件连接配置不能为空");
        return switch (config.protocol()) {
            case FTP -> new FtpRemoteFileClient(config);
            case HDFS -> new WebHdfsRemoteFileClient(config);
        };
    }
}

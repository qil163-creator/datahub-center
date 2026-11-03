package cn.iocoder.yudao.framework.file.core.remote;

import java.io.IOException;

/** 创建调用方拥有的短生命周期会话；实现应可被多个请求并发调用。 */
@FunctionalInterface
public interface RemoteFileClientFactory {

    RemoteFileClient open(RemoteFileConfig config) throws IOException;
}

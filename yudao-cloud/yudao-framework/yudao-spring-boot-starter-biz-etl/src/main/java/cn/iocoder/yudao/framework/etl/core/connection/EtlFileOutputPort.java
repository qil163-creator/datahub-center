package cn.iocoder.yudao.framework.etl.core.connection;

import cn.iocoder.yudao.framework.etl.core.runtime.EtlFileStore;

import java.io.IOException;
import java.util.List;

/** 持久化意图与授权边界。实现必须在返回前完成持久化；持有数据库锁时不得操作文件系统。 */
public interface EtlFileOutputPort {

    /**
     * 为后续写入预留资源并检查当前容量上限。
     */
    EtlFileStore.Binding reserve(String nodeNo, String directory, String fileName) throws IOException;

    /** 在持有写入器操作系统锁且尚未创建文件字节时调用；防止预留完成后的恢复操作越过此边界。 */
    default void beforeCreate(EtlFileStore.Binding binding) {
    }

    /**
     * 通知调用方当前文件对象已经创建。
     */
    void created(EtlFileStore.Binding binding, String fileKey);

    /** 创建操作范围内的临时对象前，必须先提交辅助对象意图。 */
    default void temporaryIntent(EtlFileStore.Binding binding, String name) {
        throw new UnsupportedOperationException("Durable temporary resources are not supported");
    }

    /**
     * 拒绝当前实现未支持的 {@code temporaryCreated} 操作。
     */
    default void temporaryCreated(EtlFileStore.Binding binding, String name, String fileKey) {
        throw new UnsupportedOperationException("Durable temporary resources are not supported");
    }

    /**
     * 拒绝当前实现未支持的 {@code temporaryCleaned} 操作。
     */
    default void temporaryCleaned(EtlFileStore.Binding binding, String name, String fileKey) {
        throw new UnsupportedOperationException("Durable temporary resources are not supported");
    }

    /**
     * 检查当前资源是否已经准备就绪。
     */
    void ready(EtlFileStore.Prepared prepared);

    /** 在任何文件可见前，对完整成功执行图作出一次本地持久化决策。 */
    void approvePublish(List<EtlFileStore.Prepared> outputs);

    /**
     * 通知调用方当前文件对象已经发布。
     */
    void published(EtlFileStore.Prepared output);

    /**
     * 通知调用方当前文件对象已经丢弃。
     */
    void discarded(EtlFileStore.Binding binding, String state);
}

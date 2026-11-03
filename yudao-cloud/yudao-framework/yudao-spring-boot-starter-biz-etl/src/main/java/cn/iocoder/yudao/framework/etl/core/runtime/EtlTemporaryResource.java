package cn.iocoder.yudao.framework.etl.core.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;

/** 已登记的有界临时对象；关闭时确认精确对象的清理及其持久化回执。 */
public interface EtlTemporaryResource extends AutoCloseable {

    /**
     * 打开输出流并准备后续访问。
     */
    OutputStream open() throws IOException;

    /** 供需要 File 句柄的库使用；调用方绝不能自行删除文件。 */
    Path registeredPath() throws IOException;

    /**
     * 打开输入。
     */
    InputStream openInput() throws IOException;

    /**
     * 校验并解析允许读取的本地文件路径。
     */
    Path pathForRead() throws IOException;

    /**
     * 关闭 {@code EtlTemporaryResource} 当前持有的资源。
     */
    @Override
    void close() throws IOException;
}

package cn.iocoder.yudao.module.infra.framework.file.core.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** 存储适配器共用的内存字节预算，拒绝先全量分配再检查。 */
public final class BoundedFileContent extends ByteArrayOutputStream {
    private final long limit;
    public BoundedFileContent(long limit) {
        if (limit < 1 || limit > Integer.MAX_VALUE - 8L) throw new IllegalArgumentException("文件大小预算无效");
        this.limit = limit;
    }
    private void reserve(int length) {
        if ((long) count + length > limit) throw new IllegalArgumentException("文件内容超过读取预算");
    }
    @Override public synchronized void write(int value) { reserve(1); super.write(value); }
    @Override public synchronized void write(byte[] bytes, int offset, int length) { reserve(length); super.write(bytes, offset, length); }
    public static byte[] read(InputStream stream, long maxBytes) throws IOException {
        var result = new BoundedFileContent(maxBytes);
        stream.transferTo(result);
        return result.toByteArray();
    }
}

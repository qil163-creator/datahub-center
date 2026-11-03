package cn.iocoder.yudao.framework.file.core.remote;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

final class RemoteFileStreams {
    static final long MAX_TRANSFER = 500L * 1024 * 1024;
    static final int MAX_ENTRIES = 10_000;
    private RemoteFileStreams() { }

    static long copy(InputStream input, OutputStream output, long limit) throws IOException {
        if (limit < 0 || limit > MAX_TRANSFER) throw new IllegalArgumentException("文件大小上限不合法");
        byte[] buffer = new byte[32 * 1024];
        long total = 0;
        for (int count; (count = input.read(buffer, 0, (int) Math.min(buffer.length, limit - total + 1))) >= 0;) {
            if (count == 0) continue;
            total += count;
            if (total > limit) throw new IOException("文件超过传输上限");
            output.write(buffer, 0, count);
        }
        return total;
    }

    static void validateUploadSize(long size) {
        if (size < 0 || size > MAX_TRANSFER) throw new IllegalArgumentException("文件超过上传上限");
    }
}

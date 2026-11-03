package cn.iocoder.yudao.module.infra.framework.file.core.local;

import cn.iocoder.yudao.module.infra.framework.file.core.client.local.LocalFileClient;
import cn.iocoder.yudao.module.infra.framework.file.core.client.local.LocalFileClientConfig;
import cn.iocoder.yudao.module.infra.framework.file.core.utils.BoundedFileContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ManagedFileBoundedContentTest {
    @TempDir Path directory;

    @Test void localReadStopsAtBudgetAndMissingFileIsNull() throws Exception {
        var config = new LocalFileClientConfig(); config.setBasePath(directory.toString()); config.setDomain("http://example.invalid");
        var client = new LocalFileClient(1L, config); client.init();
        client.upload(new byte[100], "managed/object/source.csv", "text/csv");
        assertEquals(100, client.getContent("managed/object/source.csv", 100).length);
        assertThrows(IllegalArgumentException.class, () -> client.getContent("managed/object/source.csv", 99));
        assertNull(client.getContent("managed/missing.csv", 100));
    }

    @Test void unboundedSourceCannotAllocatePastBudget() {
        var consumed = new AtomicLong();
        var source = new InputStream() {
            @Override public int read() { consumed.incrementAndGet(); return 42; }
        };
        assertThrows(IllegalArgumentException.class, () -> BoundedFileContent.read(source, 100));
        assertTrue(consumed.get() <= 16 * 1024, "只能预读一个有界缓冲区");
    }
}

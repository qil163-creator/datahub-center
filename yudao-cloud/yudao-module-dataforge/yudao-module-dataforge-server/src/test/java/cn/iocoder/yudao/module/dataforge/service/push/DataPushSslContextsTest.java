package cn.iocoder.yudao.module.dataforge.service.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 证书路径校验
 */
class DataPushSslContextsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("未配置证书时不创建 SSLContext")
    void blankReturnsNull() {
        assertNull(DataPushSslContexts.create(null, null, null, null));
        assertNull(DataPushSslContexts.create("  ", null, "", null));
    }

    @Test
    @DisplayName("空路径跳过校验")
    void optionalBlankSkipped() {
        assertDoesNotThrow(() -> DataPushSslContexts.validateOptionalFile(null));
        assertDoesNotThrow(() -> DataPushSslContexts.validateOptionalFile(" "));
    }

    @Test
    @DisplayName("不存在或不可读的证书文件被拒绝")
    void missingFileRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> DataPushSslContexts.requireReadableFile(tempDir.resolve("missing.jks").toString()));
        assertThrows(IllegalArgumentException.class,
                () -> DataPushSslContexts.requireReadableFile(tempDir.toString()));
    }

    @Test
    @DisplayName("可读普通文件通过路径校验")
    void readableFileAccepted() throws Exception {
        Path file = Files.createFile(tempDir.resolve("trust.jks"));
        assertNotNull(DataPushSslContexts.requireReadableFile(file.toString()));
        assertDoesNotThrow(() -> DataPushSslContexts.validateOptionalFile(file.toString()));
    }

}

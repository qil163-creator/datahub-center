package cn.iocoder.yudao.framework.file.core.remote;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class RemoteFileSafetyTest {
    @Test
    void configRejectsCredentialBearingEndpointsAndInvalidNetworkParametersBeforeConnecting() {
        for (String host : List.of("user:password@server", "server/path", "server:21", "server?secret", "server\n")) {
            assertThatThrownBy(() -> new RemoteFileConfig(RemoteFileConfig.Protocol.FTP, host, 21, "fixture", "", "/", null, Set.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new RemoteFileConfig(RemoteFileConfig.Protocol.FTP, "localhost", 0, "fixture", "", "/", null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        for (String endpoint : List.of("hdfs://localhost:8020", "http://user:secret@localhost:9870", "http://localhost:9870?token=secret", "http://localhost:9870/arbitrary")) {
            assertThatThrownBy(() -> new RemoteFileConfig(RemoteFileConfig.Protocol.HDFS, null, 0, "fixture", "", "/", endpoint, Set.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new RemoteFileConfig(RemoteFileConfig.Protocol.HDFS, null, 0, "fixture", "", "/", "http://localhost:9870", Set.of("user:secret@localhost:9864")))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void rejectsAmbiguousPathsAndLeavesChineseNamesIntact() {
        for (String path : List.of("../a", "/../a", "/a/./b", "/a//b", "/a\\b", "/a%2fb", "/a\n", "/a\u0000b")) {
            assertThatThrownBy(() -> RemoteFilePaths.normalize(path)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(RemoteFilePaths.child("/目录/", "文件.txt")).isEqualTo("/目录/文件.txt");
        assertThat(RemoteFilePaths.child("/100%目录/", "100%+中文.txt")).isEqualTo("/100%目录/100%+中文.txt");
        assertThatThrownBy(() -> RemoteFilePaths.child("/", "%252f")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RemoteFilePaths.child("/", "../a")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hardDeadlineAbortsAndCancellationSuppressesAbortion() throws Exception {
        CountDownLatch timeout = new CountDownLatch(1);
        try (RemoteFileSessionDeadline deadline = new RemoteFileSessionDeadline(Duration.ofMillis(10), timeout::countDown)) {
            assertThat(timeout.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(deadline::check).isInstanceOf(IOException.class);
        }
        CountDownLatch cancelled = new CountDownLatch(1);
        RemoteFileSessionDeadline deadline = new RemoteFileSessionDeadline(Duration.ofMillis(100), cancelled::countDown);
        deadline.close();
        assertThat(cancelled.await(150, TimeUnit.MILLISECONDS)).isFalse();
    }

    @Test
    void transferNeverWritesBytesBeyondLimitAndDoesNotCloseCallerStreams() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThatThrownBy(() -> RemoteFileStreams.copy(new ByteArrayInputStream(new byte[]{1, 2, 3}), output, 2)).isInstanceOf(IOException.class);
        assertThat(output.size()).isLessThanOrEqualTo(2);
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[]{4});
        assertThat(RemoteFileStreams.copy(input, output, 1)).isEqualTo(1);
        output.write(5);
        assertThat(output.toByteArray()).endsWith((byte) 4, (byte) 5);
    }
}

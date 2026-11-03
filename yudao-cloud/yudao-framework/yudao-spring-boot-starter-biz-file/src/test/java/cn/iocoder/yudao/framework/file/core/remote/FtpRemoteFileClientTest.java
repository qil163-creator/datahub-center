package cn.iocoder.yudao.framework.file.core.remote;

import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.DefaultFtplet;
import org.apache.ftpserver.ftplet.DefaultFtpReply;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.ftplet.FtpSession;
import org.apache.ftpserver.ftplet.FtpletResult;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.*;

class FtpRemoteFileClientTest {
    @TempDir Path home;
    private FtpServer server;
    private RemoteFileConfig config;
    private boolean refuseCommit;
    private boolean refuseRestore;

    @BeforeEach
    void start() throws Exception {
        Files.createDirectory(home.resolve("scope"));
        Files.writeString(home.resolve("outside.txt"), "outside");
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        ListenerFactory listener = new ListenerFactory();
        listener.setServerAddress("127.0.0.1");
        listener.setPort(port);
        FtpServerFactory factory = new FtpServerFactory();
        factory.setFtplets(new java.util.HashMap<>(Map.of("failure-fixture", new DefaultFtplet() {
            @Override
            public FtpletResult beforeCommand(FtpSession session, FtpRequest request) throws FtpException {
                if (request.getCommand().equals("RNTO") && session.getRenameFrom() != null) {
                    String source = session.getRenameFrom().getName();
                    if ((refuseCommit && source.startsWith(".remote-file-upload-"))
                            || (refuseRestore && source.startsWith(".remote-file-backup-"))) {
                        session.write(new DefaultFtpReply(550, "isolated failure fixture"));
                        return FtpletResult.SKIP;
                    }
                }
                return FtpletResult.DEFAULT;
            }
        })));
        factory.addListener("default", listener.createListener());
        BaseUser user = new BaseUser();
        user.setName("isolated-file-test");
        user.setPassword(UUID.randomUUID().toString());
        user.setHomeDirectory(home.toString());
        user.setAuthorities(List.of(new WritePermission()));
        factory.getUserManager().save(user);
        server = factory.createServer();
        server.start();
        config = new RemoteFileConfig(RemoteFileConfig.Protocol.FTP, "127.0.0.1", port,
                user.getName(), user.getPassword(), "/scope", null, Set.of());
    }

    @AfterEach void stop() { if (server != null) server.stop(); }

    @Test
    void realFtpStreamsAndOverwritesWithinConfiguredRoot() throws Exception {
        byte[] bytes = "文件内容".getBytes(StandardCharsets.UTF_8);
        try (RemoteFileClient client = new DefaultRemoteFileClientFactory().open(config)) {
            client.mkdir("/子目录");
            client.upload("/子目录/a.txt", new ByteArrayInputStream(bytes), bytes.length);
            assertThat(client.list("/")).extracting(RemoteFileEntry::path).containsExactly("/子目录");
            assertThat(client.stat("/子目录/a.txt").size()).isEqualTo(bytes.length);
            assertThatThrownBy(() -> client.upload("/子目录/a.txt", new ByteArrayInputStream(bytes), bytes.length)).isInstanceOf(IOException.class);
            client.upload("/子目录/a.txt", new ByteArrayInputStream(new byte[]{42}), 1, true);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            client.download("/子目录/a.txt", output, 5);
            assertThat(output.toByteArray()).containsExactly((byte) 42);
            assertThat(client.list("/子目录")).hasSize(1);
            client.rename("/子目录/a.txt", "/子目录/b.txt");
            client.delete("/子目录/b.txt");
            client.delete("/子目录");
            assertThat(client.list("/")).isEmpty();
        }
        assertThat(Files.readString(home.resolve("outside.txt"))).isEqualTo("outside");
    }

    @Test
    void literalPercentNameRoundTripsWithoutDecoding() throws Exception {
        try (RemoteFileClient client = new DefaultRemoteFileClientFactory().open(config)) {
            String path = "/100%+中文.txt";
            client.upload(path, new ByteArrayInputStream(new byte[]{3}), 1);
            assertThat(client.list("/")).extracting(RemoteFileEntry::name).containsExactly("100%+中文.txt");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            client.download(path, output, 1);
            assertThat(output.toByteArray()).containsExactly((byte) 3);
        }
        assertThat(Files.exists(home.resolve("scope/100%+中文.txt"))).isTrue();
    }

    @Test
    void activeModeTransfersAgainstRealFtpServer() throws Exception {
        RemoteFileConfig active = new RemoteFileConfig(config.protocol(), config.host(), config.port(),
                config.username(), config.password(), config.rootPath(), null, Set.of(), false);
        try (RemoteFileClient client = new DefaultRemoteFileClientFactory().open(active)) {
            client.upload("/active.txt", new ByteArrayInputStream(new byte[]{1, 2}), 2);
            assertThat(client.list("/")).extracting(RemoteFileEntry::name).containsExactly("active.txt");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            client.download("/active.txt", bytes, 2);
            assertThat(bytes.toByteArray()).containsExactly((byte) 1, (byte) 2);
        }
    }

    @Test
    void rejectsTraversalRootMutationDirectoryOverwriteAndBoundedDownloads() throws Exception {
        try (RemoteFileClient client = new DefaultRemoteFileClientFactory().open(config)) {
            client.mkdir("/nested");
            client.upload("/nested/data", new ByteArrayInputStream(new byte[]{1, 2, 3}), 3);
            assertThatThrownBy(() -> client.list("/../")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.delete("/")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.rename("/", "/another")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.upload("/nested", new ByteArrayInputStream(new byte[0]), 0, true)).isInstanceOf(IOException.class);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertThatThrownBy(() -> client.download("/nested/data", output, 2)).isInstanceOf(IOException.class);
            assertThat(output.size()).isZero();
            assertThatThrownBy(() -> client.delete("/nested")).isInstanceOf(IOException.class);
        }
    }

    @Test
    void zipPreflightsBytesDeduplicatesSelectionsAndRecursiveDeleteWorks() throws Exception {
        try (RemoteFileClient client = new DefaultRemoteFileClientFactory().open(config)) {
            client.mkdir("/nested");
            client.upload("/nested/a", new ByteArrayInputStream(new byte[]{1, 2}), 2);
            client.upload("/b", new ByteArrayInputStream(new byte[]{3}), 1);
            assertThat(RemoteFileOperations.walk(client, "/", 10, 4)).hasSize(4);
            assertThatThrownBy(() -> RemoteFileOperations.walk(client, "/", 2, 4)).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> RemoteFileOperations.walk(client, "/", 10, 0)).isInstanceOf(IOException.class);
            ByteArrayOutputStream rejected = new ByteArrayOutputStream();
            assertThatThrownBy(() -> RemoteFileOperations.zip(client, List.of("/"), rejected, 10, 4, 2)).isInstanceOf(IOException.class);
            assertThat(rejected.size()).isZero();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            RemoteFileOperations.zip(client, List.of("/", "/nested/a"), output, 10, 4, 3);
            List<String> names = new java.util.ArrayList<>();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) names.add(entry.getName());
            }
            assertThat(names).containsExactlyInAnyOrder("nested/", "nested/a", "b");
            RemoteFileOperations.deleteRecursively(client, "/nested", 10, 4);
            assertThat(client.list("/")).extracting(RemoteFileEntry::name).containsExactly("b");
        }
    }

    @Test
    void failedUploadDoesNotReplaceOriginalAndLeavesNoStagingFile() throws Exception {
        Files.writeString(home.resolve("scope/original"), "original");
        try (RemoteFileClient client = new DefaultRemoteFileClientFactory().open(config)) {
            assertThatThrownBy(() -> client.upload("/original", new ByteArrayInputStream(new byte[]{1}), 2, true)).isInstanceOf(IOException.class);
        }
        assertThat(Files.readString(home.resolve("scope/original"))).isEqualTo("original");
        try (var paths = Files.list(home.resolve("scope"))) { assertThat(paths.map(path -> path.getFileName().toString())).containsExactly("original"); }
    }

    @Test
    void excessUploadBytesAreRejectedAndTemporaryTransferIsCleaned() throws Exception {
        try (RemoteFileClient client = new DefaultRemoteFileClientFactory().open(config)) {
            assertThatThrownBy(() -> client.upload("/excess", new ByteArrayInputStream(new byte[]{1, 2, 3}), 2)).isInstanceOf(IOException.class);
        }
        try (var paths = Files.list(home.resolve("scope"))) { assertThat(paths).isEmpty(); }
    }

    @Test
    void failedRenameCommitRestoresOriginalThroughRealFtpCommands() throws Exception {
        Files.writeString(home.resolve("scope/original"), "original");
        refuseCommit = true;
        try (RemoteFileClient client = new DefaultRemoteFileClientFactory().open(config)) {
            assertThatThrownBy(() -> client.upload("/original", new ByteArrayInputStream(new byte[]{1}), 1, true)).isInstanceOf(IOException.class);
        }
        assertThat(Files.readString(home.resolve("scope/original"))).isEqualTo("original");
        try (var paths = Files.list(home.resolve("scope"))) { assertThat(paths.map(path -> path.getFileName().toString())).containsExactly("original"); }
    }

    @Test
    void failedRestorationRetainsOriginalBackupForRecovery() throws Exception {
        Files.writeString(home.resolve("scope/original"), "original");
        refuseCommit = true;
        refuseRestore = true;
        try (RemoteFileClient client = new DefaultRemoteFileClientFactory().open(config)) {
            assertThatThrownBy(() -> client.upload("/original", new ByteArrayInputStream(new byte[]{1}), 1, true))
                    .isInstanceOf(IOException.class).hasMessageContaining("原件备份已保留");
        }
        try (var paths = Files.list(home.resolve("scope"))) {
            List<Path> remaining = paths.toList();
            assertThat(remaining).hasSize(1);
            assertThat(remaining.getFirst().getFileName().toString()).startsWith(".remote-file-backup-");
            assertThat(Files.readString(remaining.getFirst())).isEqualTo("original");
        }
    }
}

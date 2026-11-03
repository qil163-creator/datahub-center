package cn.iocoder.yudao.framework.file.core.client;
import cn.iocoder.yudao.framework.file.core.client.sftp.*;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
class SftpStorageTest {
    @TempDir Path home;
    SshServer server;SftpFileClientConfig config;
    @BeforeEach void start() throws Exception {
        Files.createDirectory(home.resolve("scope"));String password=UUID.randomUUID().toString();
        server=SshServer.setUpDefaultServer();server.setHost("127.0.0.1");server.setPort(0);
        SimpleGeneratorHostKeyProvider keys=new SimpleGeneratorHostKeyProvider(home.resolve("host.key"));keys.setAlgorithm("RSA");server.setKeyPairProvider(keys);
        server.setPasswordAuthenticator((username,secret,session)->username.equals("isolated-storage")&&secret.equals(password));
        server.setFileSystemFactory(new VirtualFileSystemFactory(home));server.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));server.start();
        config=new SftpFileClientConfig().setHost("127.0.0.1").setPort(server.getPort()).setUsername("isolated-storage").setPassword(password).setBasePath("/scope").setDomain("https://files.example.invalid").setHostKeyFingerprint(KeyUtils.getFingerPrint(keys.loadKeys(null).iterator().next().getPublic()));
    }
    @AfterEach void stop() throws Exception {if(server!=null)server.stop(false);}
    @Test void realSftpRoundtripConcurrencyAndCleanup() throws Exception {
        try(SftpFileClient client=new SftpFileClient(1L,config);var executor=Executors.newFixedThreadPool(3)) {
            client.init();List<Future<?>> futures=new ArrayList<>();
            for(int i=0;i<6;i++){final int value=i;futures.add(executor.submit(()->{try {String path="dir"+value+"/file";client.upload(new byte[]{(byte)value},path,"text/plain");assertThat(client.getContent(path)).containsExactly((byte)value);client.delete(path);assertThat(client.getContent(path)).isNull();}catch(Exception e){throw new RuntimeException(e);}}));}
            for(Future<?> future:futures)future.get(20,TimeUnit.SECONDS);
        }
        for(int i=0;i<50&&!server.getActiveSessions().isEmpty();i++)Thread.sleep(20);
        assertThat(server.getActiveSessions()).isEmpty();assertThat(config.toString()).doesNotContain(config.getPassword());
    }
    @Test void rejectsWrongHostKeyAndTraversal() throws Exception {
        config.setHostKeyFingerprint("SHA256:"+"A".repeat(43));
        try(SftpFileClient client=new SftpFileClient(1L,config)) {
            client.init();assertThatThrownBy(()->client.upload(new byte[]{1},"file","text/plain")).hasMessageContaining("公钥");
            assertThatThrownBy(()->client.upload(new byte[]{1},"../escape","text/plain")).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(Files.exists(home.resolve("scope/file"))).isFalse();
    }
    @Test void probeAuthenticatesAndChecksDirectoryWithoutWrites() throws Exception {
        try (SftpFileClient client = new SftpFileClient(1L, config)) {
            client.init();client.testConnection();
        }
        try (var files = Files.list(home.resolve("scope"))) {
            assertThat(files.toList()).isEmpty();
        }
        config.setBasePath("/missing");
        try (SftpFileClient client = new SftpFileClient(1L, config)) {
            client.init();assertThatThrownBy(client::testConnection).isInstanceOf(Exception.class);
        }
        assertThat(Files.exists(home.resolve("missing"))).isFalse();
        config.setBasePath("/scope").setPassword(UUID.randomUUID().toString());
        try (SftpFileClient client = new SftpFileClient(1L, config)) {
            client.init();assertThatThrownBy(client::testConnection).hasMessageContaining("连接失败");
        }
        for(int i=0;i<50&&!server.getActiveSessions().isEmpty();i++)Thread.sleep(20);
        assertThat(server.getActiveSessions()).isEmpty();
    }
    @Test void probeUsesConfiguredTimeoutAndRejectsSubMillisecondTimeout() throws Exception {
        try (SftpFileClient client = new SftpFileClient(1L, config)) {
            client.init();
            client.testConnection(Duration.ofSeconds(5));
            assertThatThrownBy(() -> client.testConnection(Duration.ofNanos(1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.testConnection(Duration.ofSeconds(31)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        try (ServerSocket silentServer = new ServerSocket(0)) {
            config.setPort(silentServer.getLocalPort());
            try (SftpFileClient client = new SftpFileClient(1L, config)) {
                client.init();
                long started = System.nanoTime();
                assertThatThrownBy(() -> client.testConnection(Duration.ofMillis(250)))
                        .hasMessageContaining("连接失败").hasMessageNotContaining(config.getPassword());
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            }
        }
    }
}

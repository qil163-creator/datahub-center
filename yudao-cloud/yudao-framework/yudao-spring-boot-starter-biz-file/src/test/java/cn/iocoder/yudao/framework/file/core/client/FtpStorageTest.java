package cn.iocoder.yudao.framework.file.core.client;
import cn.iocoder.yudao.framework.file.core.client.ftp.*;
import org.apache.ftpserver.*;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.impl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
class FtpStorageTest {
    @TempDir Path home;
    FtpServer server; FtpFileClientConfig config;
    @BeforeEach void start() throws Exception {
        Files.createDirectory(home.resolve("scope")); int port; try(ServerSocket socket=new ServerSocket(0)){port=socket.getLocalPort();}
        ListenerFactory listener=new ListenerFactory(); listener.setServerAddress("127.0.0.1");listener.setPort(port);
        FtpServerFactory factory=new FtpServerFactory();factory.addListener("default",listener.createListener());
        BaseUser user=new BaseUser();user.setName("isolated-storage");user.setPassword(UUID.randomUUID().toString());user.setHomeDirectory(home.toString());user.setAuthorities(List.of(new WritePermission()));factory.getUserManager().save(user);
        server=factory.createServer();server.start();
        config=new FtpFileClientConfig().setHost("127.0.0.1").setPort(port).setUsername(user.getName()).setPassword(user.getPassword()).setMode("Passive").setBasePath("/scope").setDomain("https://files.example.invalid");
    }
    @AfterEach void stop(){if(server!=null)server.stop();}
    @Test void sharedClientUsesIsolatedSessionsForConcurrentUploads() throws Exception {
        try(FtpFileClient client=new FtpFileClient(1L,config);var executor=Executors.newFixedThreadPool(4)) {
            client.init();List<Future<?>> futures=new ArrayList<>();
            for(int i=0;i<8;i++){final int value=i;futures.add(executor.submit(()->{try {client.upload(new byte[]{(byte)value},"dir"+value+"/file","text/plain");assertThat(client.getContent("dir"+value+"/file")).containsExactly((byte)value);}catch(Exception e){throw new RuntimeException(e);}}));}
            for(Future<?> future:futures)future.get(20,TimeUnit.SECONDS);
            assertThat(config.toString()).doesNotContain(config.getPassword());
        }
    }
    @Test void activeModeAndRootBounds() throws Exception {
        config.setMode("Active");try(FtpFileClient client=new FtpFileClient(1L,config)) {
            client.init();client.upload(new byte[]{1},"nested/100%+中文.txt","text/plain");client.upload(new byte[]{2},"nested/100%+中文.txt","text/plain");assertThat(client.getContent("nested/100%+中文.txt")).containsExactly((byte)2);
            client.delete("nested/100%+中文.txt");assertThat(client.getContent("nested/100%+中文.txt")).isNull();
            assertThatThrownBy(()->client.upload(new byte[]{3},"../escape","text/plain")).isInstanceOf(IllegalArgumentException.class);
        }
    }
}

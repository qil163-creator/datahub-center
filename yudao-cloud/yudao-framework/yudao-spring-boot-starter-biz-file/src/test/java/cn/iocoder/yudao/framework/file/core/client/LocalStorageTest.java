package cn.iocoder.yudao.framework.file.core.client;
import cn.iocoder.yudao.framework.file.core.client.local.*;
import cn.iocoder.yudao.framework.file.core.remote.DefaultRemoteFileClientFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
class LocalStorageTest {
    @TempDir Path directory;
    private LocalFileClientConfig config(Path root) { return new LocalFileClientConfig().setBasePath(root.toString()).setDomain("https://files.example.invalid"); }
    @Test void lifecycleAndUrlEncoding() throws Exception {
        try(LocalFileClient client=new LocalFileClient(1L,config(directory))) {
            client.init(); String key="目录/中文 100%+文件.txt";
            String url=client.upload(new byte[]{1,2},key,"text/plain");
            assertThat(url).startsWith("https://files.example.invalid/").contains("100%25+").doesNotContain("infra");
            assertThat(client.getContent(key)).containsExactly((byte)1,(byte)2);
            client.delete(key); assertThat(client.getContent(key)).isNull();
        }
    }
    @Test void rejectsTraversalAbsoluteAndDirectoryDeletion() throws Exception {
        try(LocalFileClient client=new LocalFileClient(1L,config(directory))) {
            for(String key:List.of("../outside","/outside","C:/outside","a\\b","a//b","a/./b","a/../b",""))
                assertThatThrownBy(()->client.upload(new byte[]{1},key,"text/plain")).isInstanceOf(IllegalArgumentException.class);
            client.upload(new byte[]{1},"folder/file","text/plain");
            assertThatThrownBy(()->client.delete("folder")).isInstanceOf(DirectoryNotEmptyException.class);
            assertThat(client.getContent("folder/file")).containsExactly((byte)1);
        }
    }
    @Test void rejectsSymbolicLinks() throws Exception {
        Path outside=Files.createDirectory(directory.resolve("outside"));
        Path root=Files.createDirectory(directory.resolve("root"));
        try { Files.createSymbolicLink(root.resolve("link"),outside); }
        catch(FileSystemException failure) { org.junit.jupiter.api.Assumptions.abort("当前操作系统不允许测试进程创建符号链接"); }
        try(LocalFileClient client=new LocalFileClient(1L,config(root))) {
            assertThatThrownBy(()->client.upload(new byte[]{1},"link/escape","text/plain")).isInstanceOf(IllegalArgumentException.class);
            assertThat(Files.exists(outside.resolve("escape"))).isFalse();
        }
    }
    @Test void rejectsWindowsDirectoryJunction() throws Exception {
        if(!System.getProperty("os.name").startsWith("Windows"))return;
        Path outside=Files.createDirectory(directory.resolve("outside"));Path root=Files.createDirectory(directory.resolve("root"));Path link=root.resolve("junction");
        Process process=new ProcessBuilder("cmd.exe","/c","mklink","/J",link.toString(),outside.toString()).redirectErrorStream(true).start();
        String output=new String(process.getInputStream().readAllBytes());assertThat(process.waitFor()).as(output).isZero();
        try(LocalFileClient client=new LocalFileClient(1L,config(root))) {
            Files.writeString(outside.resolve("secret"),"isolated-outside");
            assertThatThrownBy(()->client.getContent("junction/secret")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->client.upload(new byte[]{1},"junction/escape","text/plain")).isInstanceOf(IllegalArgumentException.class);
            assertThat(Files.exists(outside.resolve("escape"))).isFalse();
            try(LocalFileClient nested=new LocalFileClient(2L,config(link.resolve("new-root")))) {
                assertThatThrownBy(()->nested.upload(new byte[]{1},"escape","text/plain")).isInstanceOf(IllegalArgumentException.class);
                assertThat(Files.exists(outside.resolve("new-root"))).isFalse();
            }
        } finally { Files.deleteIfExists(link); }
    }
    @Test void rejectsUnboundedLocalReads() throws Exception {
        try(var file=new java.io.RandomAccessFile(directory.resolve("large.bin").toFile(),"rw")) { file.setLength(32L*1024*1024+1); }
        try(LocalFileClient client=new LocalFileClient(1L,config(directory))) {
            assertThatThrownBy(()->client.getContent("large.bin")).isInstanceOf(java.io.IOException.class).hasMessageContaining("上限");
        }
    }
    @Test void factorySnapshotsMutableConfigurationAndCloses() throws Exception {
        LocalFileClientConfig config=config(directory.resolve("first"));
        try(FileClientFactoryImpl factory=new FileClientFactoryImpl(List.of(),new DefaultFileUrlResolver(),new DefaultRemoteFileClientFactory())) {
            factory.createOrUpdateFileClient(1L,10,config); FileClient first=factory.getFileClient(1L);
            config.setBasePath(directory.resolve("second").toString());
            factory.createOrUpdateFileClient(1L,10,config);
            assertThat(factory.getFileClient(1L)).isSameAs(first);
            first.upload(new byte[]{7},"file","text/plain");
            assertThat(Files.exists(directory.resolve("second/file"))).isTrue();
            factory.removeFileClient(1L); assertThat(factory.getFileClient(1L)).isNull();
        }
    }
}

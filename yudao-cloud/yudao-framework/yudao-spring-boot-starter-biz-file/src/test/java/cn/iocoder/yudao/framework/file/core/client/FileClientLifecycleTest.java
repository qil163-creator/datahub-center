package cn.iocoder.yudao.framework.file.core.client;

import cn.iocoder.yudao.framework.file.core.client.local.LocalFileClientConfig;
import cn.iocoder.yudao.framework.file.core.remote.DefaultRemoteFileClientFactory;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class FileClientLifecycleTest {
    private static class BlockingClient implements FileClient {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        boolean closed;
        @Override public Long getId() { return 1L; }
        @Override public String upload(byte[] bytes, String path, String type) throws Exception {
            started.countDown();
            if (!finish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试夹具等待超时");
            assertThat(closed).isFalse();
            return "complete";
        }
        @Override public void delete(String path) { }
        @Override public byte[] getContent(String path) { assertThat(closed).isFalse();return new byte[]{7}; }
        @Override public void close() { closed=true; }
    }

    @Test void configurationReplacementAndRemovalWaitForInflightWork() throws Exception {
        BlockingClient original = new BlockingClient();
        BlockingClient replacement = new BlockingClient();
        CountDownLatch replacementCreated = new CountDownLatch(1);
        AtomicInteger creates = new AtomicInteger();
        FileClientProvider provider = new FileClientProvider() {
            @Override public boolean supports(Integer storage, FileClientConfig config) { return storage==99; }
            @Override public FileClient create(Long id, FileClientConfig config, FileUrlResolver resolver) {
                if (creates.getAndIncrement()==0) return original;
                replacementCreated.countDown();return replacement;
            }
        };
        try (FileClientFactoryImpl factory = new FileClientFactoryImpl(List.of(provider), new DefaultFileUrlResolver(), new DefaultRemoteFileClientFactory());
             var executor = Executors.newFixedThreadPool(2)) {
            LocalFileClientConfig config = new LocalFileClientConfig().setBasePath("first");
            factory.createOrUpdateFileClient(1L, 99, config);
            FileClient borrowed = factory.getFileClient(1L);
            Future<String> operation = executor.submit(() -> borrowed.upload(new byte[]{1}, "file", "text/plain"));
            assertThat(original.started.await(2, TimeUnit.SECONDS)).isTrue();
            config.setBasePath("second");
            Future<?> update = executor.submit(() -> factory.createOrUpdateFileClient(1L, 99, config));
            assertThat(replacementCreated.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(original.closed).isFalse();assertThat(update.isDone()).isFalse();
            original.finish.countDown();assertThat(operation.get(2, TimeUnit.SECONDS)).isEqualTo("complete");update.get(2, TimeUnit.SECONDS);
            assertThat(original.closed).isTrue();
            assertThat(factory.getFileClient(1L)).isSameAs(borrowed);
            assertThat(borrowed.getContent("file")).containsExactly((byte)7);
            Future<String> secondOperation=executor.submit(() -> borrowed.upload(new byte[]{1}, "file", "text/plain"));
            assertThat(replacement.started.await(2, TimeUnit.SECONDS)).isTrue();
            Future<?> removal=executor.submit(() -> factory.removeFileClient(1L));
            assertThat(replacement.closed).isFalse();replacement.finish.countDown();secondOperation.get(2, TimeUnit.SECONDS);removal.get(2, TimeUnit.SECONDS);
            assertThat(replacement.closed).isTrue();assertThat(factory.getFileClient(1L)).isNull();
            assertThatThrownBy(() -> borrowed.getContent("file")).isInstanceOf(IllegalStateException.class).hasMessage("文件客户端已关闭");
        }
    }
}

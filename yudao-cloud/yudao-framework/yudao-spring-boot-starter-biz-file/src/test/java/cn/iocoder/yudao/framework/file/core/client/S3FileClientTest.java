package cn.iocoder.yudao.framework.file.core.client;

import cn.iocoder.yudao.framework.file.core.client.s3.S3FileClient;
import cn.iocoder.yudao.framework.file.core.client.s3.S3FileClientConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class S3FileClientTest {
    private final List<S3FileClient> clients = new ArrayList<>();

    private S3FileClient createClient(S3FileClientConfig config) {
        S3FileClient client = new S3FileClient(0L, config);
        clients.add(client);
        return client;
    }

    @AfterEach
    void closeClients() {
        clients.forEach(S3FileClient::close);
    }


    @Test
    public void testPresignGetUrl_publicAccess_encodeUrlPath() {
        // 准备参数
        S3FileClientConfig config = new S3FileClientConfig();
        config.setDomain("https://static.iocoder.cn");
        config.setEnablePublicAccess(true);
        S3FileClient client = createClient(config);

        // 调用
        String result = client.presignGetUrl("avatar/中文 100%+文件.jpg", 300);

        // 断言
        assertEquals("https://static.iocoder.cn/avatar/%E4%B8%AD%E6%96%87%20100%25+%E6%96%87%E4%BB%B6.jpg", result);
    }

    @Test
    public void testPresignGetUrl_publicAccess_decodeDomainUrl() {
        // 准备参数
        S3FileClientConfig config = new S3FileClientConfig();
        config.setDomain("https://static.iocoder.cn");
        config.setEnablePublicAccess(true);
        S3FileClient client = createClient(config);

        // 调用
        String result = client.presignGetUrl("https://static.iocoder.cn/avatar/%E4%B8%AD%E6%96%87%20100%25+%E6%96%87%E4%BB%B6.jpg?token=1", 300);

        // 断言
        assertEquals("https://static.iocoder.cn/avatar/%E4%B8%AD%E6%96%87%20100%25+%E6%96%87%E4%BB%B6.jpg", result);
    }

    @Test
    public void testPresignGetUrl_privateAccess_rawPath() {
        // 准备参数
        S3FileClientConfig config = new S3FileClientConfig();
        config.setAccessKey("isolated-signing-test");
        config.setAccessSecret(UUID.randomUUID().toString());
        config.setBucket("yudao");
        config.setDomain("http://127.0.0.1:9000/yudao");
        config.setEndpoint("http://127.0.0.1:9000");
        config.setEnablePathStyleAccess(true);
        config.setEnablePublicAccess(false);
        S3FileClient client = createClient(config);
        client.init();

        // 调用
        String result = client.presignGetUrl("avatar/中文 100%+文件.jpg", 300);

        // 断言
        assertTrue(result.contains("/yudao/avatar/%E4%B8%AD%E6%96%87%20100%25"));
        assertTrue(result.contains("%E6%96%87%E4%BB%B6.jpg"));
        assertFalse(result.contains("%25E4%25B8%25AD"));
    }

    @Test
    public void testPresignPutUrl_aliyunUsesEndpointInsteadOfCustomDomain() {
        S3FileClientConfig config = new S3FileClientConfig();
        config.setAccessKey("access-key");
        config.setAccessSecret(UUID.randomUUID().toString());
        config.setBucket("test-bucket");
        config.setDomain("https://custom.example.com");
        config.setEndpoint("oss-cn-beijing.aliyuncs.com");
        config.setEnablePathStyleAccess(false);
        config.setEnablePublicAccess(false);
        S3FileClient client = createClient(config);
        client.init();

        String result = client.presignPutUrl("avatar.jpg");

        assertTrue(result.startsWith("https://test-bucket.oss-cn-beijing.aliyuncs.com/"));
    }

    @Test
    public void testPresignPutUrl_qiniuUsesEndpointInsteadOfCustomDomain() {
        S3FileClientConfig config = new S3FileClientConfig();
        config.setAccessKey("access-key");
        config.setAccessSecret(UUID.randomUUID().toString());
        config.setBucket("test-bucket");
        config.setDomain("https://custom.example.com");
        config.setEndpoint("s3-cn-south-1.qiniucs.com");
        config.setEnablePathStyleAccess(false);
        config.setEnablePublicAccess(false);
        S3FileClient client = createClient(config);
        client.init();

        String result = client.presignPutUrl("avatar.jpg");

        assertTrue(result.startsWith("https://test-bucket.s3-cn-south-1.qiniucs.com/"));
    }

}

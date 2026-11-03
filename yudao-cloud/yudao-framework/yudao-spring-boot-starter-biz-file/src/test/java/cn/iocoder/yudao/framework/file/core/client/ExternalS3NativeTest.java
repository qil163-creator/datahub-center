package cn.iocoder.yudao.framework.file.core.client;

import cn.iocoder.yudao.framework.file.core.client.s3.S3FileClient;
import cn.iocoder.yudao.framework.file.core.client.s3.S3FileClientConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 对外部编排已建的桶执行 HEAD，禁止以测试为由自动建桶或写对象。 */
class ExternalS3NativeTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "DATAHUB_TEST_S3_ENDPOINT", matches = ".+")
    void authenticatesBucketAndRejectsUnknownBucket() throws Exception {
        S3FileClientConfig config = new S3FileClientConfig().setEndpoint(env("ENDPOINT"))
                .setBucket(env("BUCKET")).setAccessKey(env("ACCESS_KEY")).setAccessSecret(env("SECRET"))
                .setEnablePathStyleAccess(true).setEnablePublicAccess(false);
        try (S3FileClient client = new S3FileClient(1L, config)) {
            client.init();client.testConnection(Duration.ofSeconds(10));
        }
        config.setBucket("missing-" + UUID.randomUUID());
        try (S3FileClient client = new S3FileClient(2L, config)) {
            client.init();assertThatThrownBy(() -> client.testConnection(Duration.ofSeconds(10)))
                    .isInstanceOf(software.amazon.awssdk.services.s3.model.S3Exception.class);
        }
        config.setBucket(env("BUCKET")).setAccessSecret(UUID.randomUUID().toString());
        try (S3FileClient client = new S3FileClient(3L, config)) {
            client.init();assertThatThrownBy(() -> client.testConnection(Duration.ofSeconds(10)))
                    .isInstanceOf(software.amazon.awssdk.services.s3.model.S3Exception.class);
        }
    }

    private static String env(String key) {
        return System.getenv("DATAHUB_TEST_S3_" + key);
    }
}

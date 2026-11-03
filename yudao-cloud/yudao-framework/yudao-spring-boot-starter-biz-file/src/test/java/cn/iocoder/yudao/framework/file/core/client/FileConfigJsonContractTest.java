package cn.iocoder.yudao.framework.file.core.client;

import cn.iocoder.yudao.framework.file.core.client.ftp.FtpFileClientConfig;
import cn.iocoder.yudao.framework.file.core.client.sftp.SftpFileClientConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 配置仅按调用方显式给定类型解析，禁止输入决定 Java 类。 */
class FileConfigJsonContractTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void serializesConcreteConfigurationWithJacksonThreeWithoutJavaClassMetadata() {
        FtpFileClientConfig config = new FtpFileClientConfig().setHost("127.0.0.1").setPort(21)
                .setUsername("isolated-test").setPassword("synthetic-configuration-value")
                .setBasePath("/files").setDomain("https://files.example.invalid").setMode("Passive");
        String json = mapper.writeValueAsString(config);
        assertThat(json).contains("\"port\":21", "\"basePath\":\"/files\"")
                .doesNotContain("@class", "cn.iocoder");
        assertThat(mapper.readValue(json, FtpFileClientConfig.class)).isEqualTo(config);
        assertThat(config.toString()).doesNotContain(config.getPassword());
    }

    @Test
    void rejectsArbitraryPolymorphicTypesAndKeepsFingerprintField() {
        assertThatThrownBy(() -> mapper.readValue("{\"@class\":\"java.lang.Runtime\"}", FileClientConfig.class))
                .isInstanceOf(JacksonException.class);
        SftpFileClientConfig config = new SftpFileClientConfig().setHostKeyFingerprint("SHA256:" + "A".repeat(43));
        String json = mapper.writeValueAsString(config);
        assertThat(json).contains("\"hostKeyFingerprint\":");
        assertThat(mapper.readValue(json, SftpFileClientConfig.class).getHostKeyFingerprint())
                .isEqualTo(config.getHostKeyFingerprint());
    }
}

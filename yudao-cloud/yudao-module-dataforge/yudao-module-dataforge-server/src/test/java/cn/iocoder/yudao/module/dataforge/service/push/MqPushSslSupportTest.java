package cn.iocoder.yudao.module.dataforge.service.push;

import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushChannelConfigVO;
import cn.iocoder.yudao.module.dataforge.service.push.channel.MqPushChannel;
import org.apache.kafka.common.config.SslConfigs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Kafka 证书属性
 */
class MqPushSslSupportTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("写入 truststore 路径并关闭主机名校验")
    void applyTruststoreAndDisableHostname() throws Exception {
        Path trust = Files.createFile(tempDir.resolve("client-trust.jks"));
        DataPushChannelConfigVO.KafkaConfig kafka = new DataPushChannelConfigVO.KafkaConfig();
        kafka.setTruststorePath(trust.toString());
        kafka.setTruststorePassword("changeit");
        kafka.setDisableHostnameVerification(true);
        Properties props = new Properties();
        MqPushChannel.applyKafkaSsl(props, kafka);
        assertEquals(trust.toAbsolutePath().normalize().toString(),
                props.get(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG));
        assertEquals("changeit", props.get(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG));
        assertEquals("", props.get(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG));
    }

}

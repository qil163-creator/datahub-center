package cn.iocoder.yudao.module.dataforge.service.push;

import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushChannelConfigVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DataPushSecretMaskerTest {

    private static DataPushChannelConfigVO.KeyValue kv(String key, String value) {
        DataPushChannelConfigVO.KeyValue item = new DataPushChannelConfigVO.KeyValue();
        item.setKey(key);
        item.setValue(value);
        return item;
    }

    private static DataPushChannelConfigVO httpConfig(String authorization, String truststorePassword) {
        DataPushChannelConfigVO config = new DataPushChannelConfigVO();
        DataPushChannelConfigVO.HttpConfig http = new DataPushChannelConfigVO.HttpConfig();
        http.setUrl("https://example.com/push");
        List<DataPushChannelConfigVO.KeyValue> headers = new ArrayList<>();
        headers.add(kv("Authorization", authorization));
        headers.add(kv("X-Env", "prod"));
        http.setHeaders(headers);
        http.setTruststorePassword(truststorePassword);
        config.setHttp(http);
        return config;
    }

    private static DataPushChannelConfigVO kafkaConfig(String saslPassword, String keystorePassword) {
        DataPushChannelConfigVO config = new DataPushChannelConfigVO();
        DataPushChannelConfigVO.MqConfig mq = new DataPushChannelConfigVO.MqConfig();
        mq.setMqType("kafka");
        DataPushChannelConfigVO.KafkaConfig kafka = new DataPushChannelConfigVO.KafkaConfig();
        kafka.setBootstrapServers("kafka:9092");
        kafka.setSaslUsername("user");
        kafka.setSaslPassword(saslPassword);
        kafka.setKeystorePassword(keystorePassword);
        mq.setKafka(kafka);
        config.setMq(mq);
        return config;
    }

    @Test
    @DisplayName("详情脱敏：Authorization 头、证书口令、SASL 密码替换为掩码，普通字段保持原值")
    void maskReplacesSecretsOnly() {
        DataPushChannelConfigVO http = httpConfig("Bearer abc", "ts-pass");
        DataPushSecretMasker.mask(http);
        assertEquals(DataPushSecretMasker.MASK, http.getHttp().getHeaders().get(0).getValue());
        assertEquals("prod", http.getHttp().getHeaders().get(1).getValue());
        assertEquals(DataPushSecretMasker.MASK, http.getHttp().getTruststorePassword());
        assertNull(http.getHttp().getKeystorePassword());

        DataPushChannelConfigVO kafka = kafkaConfig("secret", null);
        DataPushSecretMasker.mask(kafka);
        assertEquals(DataPushSecretMasker.MASK, kafka.getMq().getKafka().getSaslPassword());
        assertEquals("user", kafka.getMq().getKafka().getSaslUsername());
        assertNull(kafka.getMq().getKafka().getKeystorePassword());
    }

    @Test
    @DisplayName("保存回填：收到掩码时取库中真实值，改过的字段按新值保存")
    void restoreKeepsExistingWhenMasked() {
        DataPushChannelConfigVO incoming = httpConfig(DataPushSecretMasker.MASK, "new-ts-pass");
        DataPushChannelConfigVO existing = httpConfig("Bearer real", "old-ts-pass");
        DataPushSecretMasker.restoreMasked(incoming, existing);
        assertEquals("Bearer real", incoming.getHttp().getHeaders().get(0).getValue());
        assertEquals("new-ts-pass", incoming.getHttp().getTruststorePassword());

        DataPushChannelConfigVO incomingKafka = kafkaConfig(DataPushSecretMasker.MASK, DataPushSecretMasker.MASK);
        DataPushChannelConfigVO existingKafka = kafkaConfig("real-sasl", "real-ks");
        DataPushSecretMasker.restoreMasked(incomingKafka, existingKafka);
        assertEquals("real-sasl", incomingKafka.getMq().getKafka().getSaslPassword());
        assertEquals("real-ks", incomingKafka.getMq().getKafka().getKeystorePassword());
    }

    @Test
    @DisplayName("保存回填：库中没有对应凭据时掩码置空，交由校验按未填写处理")
    void restoreClearsMaskWithoutExistingValue() {
        DataPushChannelConfigVO incoming = kafkaConfig(DataPushSecretMasker.MASK, null);
        DataPushSecretMasker.restoreMasked(incoming, new DataPushChannelConfigVO());
        assertNull(incoming.getMq().getKafka().getSaslPassword());

        DataPushChannelConfigVO incomingHttp = httpConfig(DataPushSecretMasker.MASK, null);
        DataPushSecretMasker.restoreMasked(incomingHttp, null);
        assertNull(incomingHttp.getHttp().getHeaders().get(0).getValue());
    }

}

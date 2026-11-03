package cn.iocoder.yudao.module.dataforge.service.push;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushChannelConfigVO;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 推送通道配置中的凭据脱敏与回填。
 *
 * <p>对外（分页、详情）统一把密码、证书口令、Authorization 头替换为 {@link #MASK}；
 * 保存时若收到 {@link #MASK}，表示前端未修改该项，需要从库中已有配置回填真实值。
 */
final class DataPushSecretMasker {

    /** 凭据掩码：接口回显值，保存时收到该值表示保持库中凭据不变 */
    static final String MASK = "******";

    private static final String AUTHORIZATION_HEADER = "authorization";

    /**
     * 限制数据推送密钥脱敏器工具的实例化，统一通过静态能力使用。
     */
    private DataPushSecretMasker() {
    }

    /**
     * 原地脱敏：非空凭据一律替换为掩码
     */
    static void mask(DataPushChannelConfigVO config) {
        if (config == null) {
            return;
        }
        DataPushChannelConfigVO.HttpConfig http = config.getHttp();
        if (http != null) {
            maskHeaders(http.getHeaders());
            maskIfPresent(http::getTruststorePassword, http::setTruststorePassword);
            maskIfPresent(http::getKeystorePassword, http::setKeystorePassword);
        }
        DataPushChannelConfigVO.MqConfig mq = config.getMq();
        if (mq != null) {
            DataPushChannelConfigVO.RabbitConfig rabbit = mq.getRabbit();
            if (rabbit != null) {
                maskIfPresent(rabbit::getPassword, rabbit::setPassword);
                maskIfPresent(rabbit::getTruststorePassword, rabbit::setTruststorePassword);
                maskIfPresent(rabbit::getKeystorePassword, rabbit::setKeystorePassword);
            }
            DataPushChannelConfigVO.KafkaConfig kafka = mq.getKafka();
            if (kafka != null) {
                maskIfPresent(kafka::getSaslPassword, kafka::setSaslPassword);
                maskIfPresent(kafka::getTruststorePassword, kafka::setTruststorePassword);
                maskIfPresent(kafka::getKeystorePassword, kafka::setKeystorePassword);
            }
        }
    }

    /**
     * 回填：incoming 中等于掩码的凭据字段，用 existing 中对应位置的真实值替换；
     * existing 缺失对应值时置空，交由后续校验按"未填写"处理。
     */
    static void restoreMasked(DataPushChannelConfigVO incoming, DataPushChannelConfigVO existing) {
        if (incoming == null) {
            return;
        }
        DataPushChannelConfigVO safeExisting = existing == null ? new DataPushChannelConfigVO() : existing;
        DataPushChannelConfigVO.HttpConfig http = incoming.getHttp();
        if (http != null) {
            DataPushChannelConfigVO.HttpConfig oldHttp = safeExisting.getHttp();
            restoreHeaders(http.getHeaders(), oldHttp == null ? null : oldHttp.getHeaders());
            restoreIfMasked(http::getTruststorePassword, http::setTruststorePassword,
                    oldHttp == null ? null : oldHttp.getTruststorePassword());
            restoreIfMasked(http::getKeystorePassword, http::setKeystorePassword,
                    oldHttp == null ? null : oldHttp.getKeystorePassword());
        }
        DataPushChannelConfigVO.MqConfig mq = incoming.getMq();
        if (mq != null) {
            DataPushChannelConfigVO.MqConfig oldMq = safeExisting.getMq();
            DataPushChannelConfigVO.RabbitConfig rabbit = mq.getRabbit();
            if (rabbit != null) {
                DataPushChannelConfigVO.RabbitConfig oldRabbit = oldMq == null ? null : oldMq.getRabbit();
                restoreIfMasked(rabbit::getPassword, rabbit::setPassword,
                        oldRabbit == null ? null : oldRabbit.getPassword());
                restoreIfMasked(rabbit::getTruststorePassword, rabbit::setTruststorePassword,
                        oldRabbit == null ? null : oldRabbit.getTruststorePassword());
                restoreIfMasked(rabbit::getKeystorePassword, rabbit::setKeystorePassword,
                        oldRabbit == null ? null : oldRabbit.getKeystorePassword());
            }
            DataPushChannelConfigVO.KafkaConfig kafka = mq.getKafka();
            if (kafka != null) {
                DataPushChannelConfigVO.KafkaConfig oldKafka = oldMq == null ? null : oldMq.getKafka();
                restoreIfMasked(kafka::getSaslPassword, kafka::setSaslPassword,
                        oldKafka == null ? null : oldKafka.getSaslPassword());
                restoreIfMasked(kafka::getTruststorePassword, kafka::setTruststorePassword,
                        oldKafka == null ? null : oldKafka.getTruststorePassword());
                restoreIfMasked(kafka::getKeystorePassword, kafka::setKeystorePassword,
                        oldKafka == null ? null : oldKafka.getKeystorePassword());
            }
        }
    }

    /**
     * 脱敏请求头。
     *
     * @param headers 请求头映射
     */
    private static void maskHeaders(List<DataPushChannelConfigVO.KeyValue> headers) {
        if (headers == null) {
            return;
        }
        for (DataPushChannelConfigVO.KeyValue header : headers) {
            if (header != null && isSecretHeader(header.getKey()) && StrUtil.isNotBlank(header.getValue())) {
                header.setValue(MASK);
            }
        }
    }

    /**
     * 恢复请求头。
     *
     * @param headers 请求头映射
     * @param oldHeaders 历史请求头
     */
    private static void restoreHeaders(List<DataPushChannelConfigVO.KeyValue> headers,
            List<DataPushChannelConfigVO.KeyValue> oldHeaders) {
        if (headers == null) {
            return;
        }
        for (DataPushChannelConfigVO.KeyValue header : headers) {
            if (header == null || !MASK.equals(header.getValue())) {
                continue;
            }
            String oldValue = null;
            if (oldHeaders != null) {
                oldValue = oldHeaders.stream()
                        .filter(Objects::nonNull)
                        .filter(old -> old.getKey() != null && old.getKey().equalsIgnoreCase(header.getKey()))
                        .map(DataPushChannelConfigVO.KeyValue::getValue)
                        .filter(StrUtil::isNotBlank)
                        .findFirst()
                        .orElse(null);
            }
            header.setValue(oldValue);
        }
    }

    /**
     * 判断是否满足密钥请求头条件。
     *
     * @param key 标识键
     * @return 判断结果
     */
    private static boolean isSecretHeader(String key) {
        return key != null && AUTHORIZATION_HEADER.equalsIgnoreCase(key.trim());
    }

    /**
     * 脱敏条件展示。
     *
     * @param getter 读取方法
     * @param setter 写入方法
     */
    private static void maskIfPresent(Supplier<String> getter, Consumer<String> setter) {
        if (StrUtil.isNotBlank(getter.get())) {
            setter.accept(MASK);
        }
    }

    /**
     * 恢复条件已脱敏。
     *
     * @param getter 读取方法
     * @param setter 写入方法
     * @param oldValue 历史值
     */
    private static void restoreIfMasked(Supplier<String> getter, Consumer<String> setter, String oldValue) {
        if (MASK.equals(getter.get())) {
            setter.accept(StrUtil.isBlank(oldValue) ? null : oldValue);
        }
    }

}

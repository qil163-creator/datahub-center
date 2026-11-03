package cn.iocoder.yudao.module.dataforge.service.push;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushConfigDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.DataPushConfigMapper;
import cn.iocoder.yudao.module.dataforge.enums.push.PushChannelTypeEnum;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATA_PUSH_CHANNEL_CONFIG_INVALID;

class DataPushMqConfigTest {
    private DataPushConfigSaveReqVO request(String acks) {
        var kafka = new DataPushChannelConfigVO.KafkaConfig();
        kafka.setBootstrapServers("127.0.0.1:19092"); kafka.setTopic("fixture"); kafka.setAcks(acks);
        var mq = new DataPushChannelConfigVO.MqConfig(); mq.setMqType("kafka"); mq.setKafka(kafka);
        var config = new DataPushChannelConfigVO(); config.setMq(mq);
        var request = new DataPushConfigSaveReqVO(); request.setChannelType(PushChannelTypeEnum.MQ.getType());
        request.setConfig(config); request.setName("fixture");
        return request;
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "all", "-1", "1"})
    void savesCanonicalAcknowledgedMode(String acks) {
        var service = new DataPushConfigServiceImpl();
        var mapper = mock(DataPushConfigMapper.class);
        ReflectionTestUtils.setField(service, "configMapper", mapper);
        service.createConfig(request(acks));
        var saved = ArgumentCaptor.forClass(DataPushConfigDO.class);
        verify(mapper).insert(saved.capture());
        var config = JsonUtils.parseObject(saved.getValue().getChannelJson(), DataPushChannelConfigVO.class);
        assertEquals("1".equals(acks) ? "1" : "all", config.getMq().getKafka().getAcks());
    }

    @ParameterizedTest @ValueSource(strings = {"0", "2", "invalid"})
    void refusesUnacknowledgedOrInvalidConfigWithoutWriting(String acks) {
        var service = new DataPushConfigServiceImpl();
        var mapper = mock(DataPushConfigMapper.class);
        ReflectionTestUtils.setField(service, "configMapper", mapper);
        var failure = assertThrows(ServiceException.class, () -> service.createConfig(request(acks)));
        assertEquals(DATA_PUSH_CHANNEL_CONFIG_INVALID.getCode(), failure.getCode());
        verifyNoInteractions(mapper);
    }
}

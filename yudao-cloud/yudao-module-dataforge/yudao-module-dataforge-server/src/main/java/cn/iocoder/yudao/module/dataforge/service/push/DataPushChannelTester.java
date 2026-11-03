package cn.iocoder.yudao.module.dataforge.service.push;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushChannelConfigVO;
import cn.iocoder.yudao.module.dataforge.enums.push.PushChannelTypeEnum;
import cn.iocoder.yudao.module.dataforge.service.database.DatabaseService;
import cn.iocoder.yudao.module.dataforge.service.push.channel.MqPushChannel;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 推送通道连通性探测：不发送业务数据。HTTP 只发 HEAD/GET；库走已有测连；MQ 只建连即关。
 */
@Component
public class DataPushChannelTester {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    @Resource
    private HttpPushUrlGuard urlGuard;

    @Resource
    private DatabaseService databaseService;

    @Resource
    private MqPushChannel mqPushChannel;

    /**
     * 测试数据推送通道测试器配置。
     *
     * @param channelType 通道类型
     * @param config 处理配置
     */
    public void test(Integer channelType, DataPushChannelConfigVO config) throws Exception {
        PushChannelTypeEnum type = PushChannelTypeEnum.of(channelType);
        if (type == null || config == null) {
            throw new IllegalArgumentException("通道配置不完整");
        }
        switch (type) {
            case HTTP -> testHttp(config.getHttp());
            case DB -> {
                if (config.getDb() == null || config.getDb().getDatabaseId() == null) {
                    throw new IllegalArgumentException("请选择目标数据源");
                }
                databaseService.testDatabase(config.getDb().getDatabaseId());
            }
            case MQ -> {
                try (DataPushSession session = mqPushChannel.openSession(JsonUtils.toJsonString(config))) {
                    if (session == null) {
                        throw new IllegalStateException("无法建立消息队列连接");
                    }
                }
            }
        }
    }

    /**
     * 测试HTTP。
     *
     * @param http HTTP
     */
    private void testHttp(DataPushChannelConfigVO.HttpConfig http) throws Exception {
        String url = HttpPushUrlBuilder.build(http);
        URI uri = urlGuard.validate(url);
        HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        HttpRequest head = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            client.send(head, HttpResponse.BodyHandlers.discarding());
            return;
        } catch (Exception headEx) {
            if (StrUtil.containsIgnoreCase(headEx.getMessage(), "HEAD")) {
                HttpRequest get = HttpRequest.newBuilder(uri)
                        .timeout(TIMEOUT)
                        .GET()
                        .build();
                client.send(get, HttpResponse.BodyHandlers.discarding());
                return;
            }
            throw headEx;
        }
    }

}

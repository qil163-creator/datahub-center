package cn.iocoder.yudao.framework.etl.rabbitmq.smoke;

import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedSecrets;
import cn.iocoder.yudao.framework.etl.rabbitmq.shaded.client.ConnectionFactory;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 独立验收客户端只连接批准的 fixture，任何凭据不写入命令行或结果。 */
public final class RabbitFixtureClient {
    public static void main(String[] args) throws Exception {
        Properties values = new Properties(); values.load(new StringReader(EtlManagedSecrets.readFromEnvironment("rabbit.fixture")));
        if (!"rabbit-native".equals(values.getProperty("host")) || !"/components".equals(values.getProperty("virtualHost"))) throw new IllegalStateException("验收资源不一致");
        ConnectionFactory factory = new ConnectionFactory(); factory.setHost(values.getProperty("host")); factory.setPort(5672); factory.setVirtualHost("/components");
        factory.setUsername(values.getProperty("username")); factory.setPassword(values.getProperty("password"));
        try (var connection = factory.newConnection(); var channel = connection.createChannel()) {
            switch (args[0]) {
                case "purge" -> { channel.queuePurge("components.in"); channel.queuePurge("components.out"); }
                case "publish" -> { channel.confirmSelect(); channel.basicPublish("", "components.in", null, Files.readAllBytes(Path.of(args[1]))); channel.waitForConfirmsOrDie(10_000); }
                case "read" -> {
                    var result = new ArrayList<Map<String, Object>>(); var mapper = new ObjectMapper();
                    while (true) {
                        var delivery = channel.basicGet("components.out", false); if (delivery == null) break;
                        result.add(mapper.readValue(delivery.getBody(), Map.class)); channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                    }
                    System.out.println(mapper.writeValueAsString(result));
                }
                default -> throw new IllegalArgumentException("未知验收命令");
            }
        }
    }
}

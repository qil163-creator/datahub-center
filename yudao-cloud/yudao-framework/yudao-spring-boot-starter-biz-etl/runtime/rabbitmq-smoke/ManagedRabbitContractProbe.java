package cn.iocoder.yudao.framework.etl.flink.rabbitmq;

import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedSecrets;
import cn.iocoder.yudao.framework.etl.flink.common.EtlJsonRowGuard;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.runtime.typeutils.InternalTypeInfo;
import org.apache.flink.table.types.logical.*;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** 在目标 Java17/Flink 运行类路径验证序列化和格式护栏，不把秘密输出到结果。 */
public final class ManagedRabbitContractProbe {
    public static void main(String[] args) throws Exception {
        RowType shape = RowType.of(new LogicalType[]{new BigIntType(), new DecimalType(30,4), new BooleanType(), new VarCharType(VarCharType.MAX_LENGTH)}, new String[]{"id","amount","enabled","name"});
        var decoder = new Noop(shape); var guard = new EtlJsonRowGuard(decoder, shape); guard.open(null);
        guard.deserialize("{\"id\":9007199254740993,\"amount\":12345678901234567890.1234,\"enabled\":true,\"name\":\"河道\"}".getBytes(StandardCharsets.UTF_8));
        int rejected = 0;
        for (String body : new String[]{
                "{\"id\":1.2,\"amount\":1,\"enabled\":true,\"name\":\"a\"}",
                "{\"id\":9223372036854775808,\"amount\":1,\"enabled\":true,\"name\":\"a\"}",
                "{\"id\":1,\"amount\":1.00001,\"enabled\":true,\"name\":\"a\"}",
                "{\"id\":1,\"amount\":1,\"enabled\":\"not boolean\",\"name\":\"a\"}",
                "{\"id\":1,\"id\":2,\"amount\":1,\"enabled\":true,\"name\":\"a\"}", "{\"id\":1}",
                "{\"id\":1,\"amount\":1,\"enabled\":true,\"name\":\"a\"}{}"}) {
            try { guard.deserialize(body.getBytes(StandardCharsets.UTF_8)); throw new AssertionError("非法消息未被拒绝"); }
            catch (IOException expected) { rejected++; }
        }
        var resource = new ManagedRabbitConnection("rabbit-native",5672,"/components","components.in","rabbit.fixture",false,false);
        var source = new ManagedRabbitSource(resource,100,guard);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(source); }
        Properties properties = new Properties(); properties.load(new StringReader(EtlManagedSecrets.readFromEnvironment("rabbit.fixture")));
        assertNoSecret(bytes.toByteArray(), properties.getProperty("password"));
        bytes.reset();
        var sinkResource = new ManagedRabbitConnection("rabbit-native",5672,"/components","components.out","rabbit.fixture",false,true);
        var sink = new ManagedRabbitSink(sinkResource,10_000,value -> new byte[0]);
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(sink); }
        assertNoSecret(bytes.toByteArray(), properties.getProperty("password"));
        var environment = StreamExecutionEnvironment.getExecutionEnvironment(); environment.enableCheckpointing(1000);
        environment.addSource(source).addSink(sink);
        var graph = environment.getStreamGraph().getJobGraph(); bytes.reset();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(graph); }
        assertNoSecret(bytes.toByteArray(), properties.getProperty("password"));
        try {
            new ManagedRabbitConnection("another-host",5672,"/components","components.in","rabbit.fixture",false,false).openFactory();
            throw new AssertionError("秘密引用允许地址重定向");
        } catch (IllegalStateException expected) { }
        try (var connection = resource.openFactory().newConnection(); var channel = connection.createChannel()) {
            channel.queueDeclarePassive("components.in"); channel.queueDeclarePassive("components.out");
        }
        System.out.println("{\"serializedSourceContainsPassword\":false,\"serializedSinkContainsPassword\":false,\"serializedJobGraphContainsPassword\":false,\"jobGraphBytes\":" + bytes.size() + ",\"rejectedTypedMessageCases\":" + rejected + ",\"redirectedSecretRefRejected\":true,\"brokerAuthorizationPassed\":true}");
    }
    private static void assertNoSecret(byte[] serialized, String password) {
        String binary = new String(serialized, StandardCharsets.ISO_8859_1);
        for (var encoding : new java.nio.charset.Charset[]{StandardCharsets.UTF_8, StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE})
            if (binary.contains(new String(password.getBytes(encoding), StandardCharsets.ISO_8859_1))) throw new AssertionError("凭据进入序列化数据");
    }
    private record Noop(RowType shape) implements DeserializationSchema<RowData> {
        @Override public RowData deserialize(byte[] message) { return new GenericRowData(shape.getFieldCount()); }
        @Override public boolean isEndOfStream(RowData value) { return false; }
        @Override public TypeInformation<RowData> getProducedType() { return InternalTypeInfo.of(shape); }
    }
}

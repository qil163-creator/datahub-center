package cn.iocoder.yudao.framework.etl.flink.rocketmq;

import cn.iocoder.yudao.framework.etl.core.executor.support.EtlManagedSecrets;
import cn.iocoder.yudao.framework.etl.flink.common.EtlJsonRowGuard;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.core.memory.DataInputDeserializer;
import org.apache.flink.core.memory.DataOutputSerializer;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.runtime.typeutils.InternalTypeInfo;
import org.apache.flink.table.types.logical.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 使用目标引擎实际序列化器检查源、输出、JobGraph、检查点；秘密只用于结果内存断言。 */
public final class ManagedRocketContractProbe {
    public static void main(String[] args) throws Exception {
        var shape = RowType.of(new LogicalType[]{new BigIntType(), new VarCharType(VarCharType.MAX_LENGTH)}, new String[]{"id", "name"});
        var guard = new EtlJsonRowGuard(new Noop(shape), shape); guard.open(null);
        guard.deserialize("{\"id\":9007199254740993,\"name\":\"河道 O'Reilly\"}".getBytes(StandardCharsets.UTF_8));
        var input = new ManagedRocketResource("rocket-native-namesrv:9876", "components_input", "components_read", "rocket.fixture", false, false);
        var source = new ManagedRocketSource(input, "earliest", 1000, guard);
        var output = new ManagedRocketResource("rocket-native-namesrv:9876", "components_output", "components_write", "rocket.fixture", false, true);
        var sink = new ManagedRocketSink(output, 5000, row -> new byte[0]);
        var secrets = new Properties(); secrets.load(new StringReader(EtlManagedSecrets.readFromEnvironment("rocket.fixture")));
        String access = secrets.getProperty("accessKey"), secret = secrets.getProperty("secretKey");
        int sourceBytes = serialized(source, access, secret), sinkBytes = serialized(sink, access, secret);
        var environment = StreamExecutionEnvironment.getExecutionEnvironment(); environment.enableCheckpointing(1000);
        environment.addSource(source).addSink(sink); int graphBytes = serialized(environment.getStreamGraph().getJobGraph(), access, secret);
        var cursor = new ManagedRocketSource.Cursor(input.identity(), "components-broker", 1, 9007199254740993L);
        var serializer = TypeInformation.of(ManagedRocketSource.Cursor.class).createSerializer(new org.apache.flink.api.common.ExecutionConfig());
        var bytes = new DataOutputSerializer(256); serializer.serialize(cursor, bytes); assertNoSecret(bytes.getCopyOfBuffer(), access, secret);
        var restored = serializer.deserialize(new DataInputDeserializer(bytes.getCopyOfBuffer()));
        if (restored.getOffset() != cursor.getOffset() || !restored.getResourceIdentity().equals(input.identity())) throw new AssertionError("实际检查点序列化有损");
        int rejected = 0;
        for (var resource : List.of(new ManagedRocketResource("another-host:9876", input.topic, input.group, input.reference, false, false),
                new ManagedRocketResource(input.servers, input.topic, "foreign-group", input.reference, false, false),
                new ManagedRocketResource(input.servers, "foreign-topic", input.group, input.reference, false, false))) {
            try { resource.credentials(); throw new AssertionError("秘密引用越权"); } catch (IllegalStateException expected) { rejected++; }
        }
        System.out.println("{\"sourceBytes\":" + sourceBytes + ",\"sinkBytes\":" + sinkBytes + ",\"jobGraphBytes\":" + graphBytes + ",\"checkpointBytes\":" + bytes.length()
                + ",\"accessKeyAndSecretSentinelMatches\":0,\"officialCheckpointSerializerExact\":true,\"resourceScopeNegatives\":" + rejected + "}");
    }
    private static int serialized(Object object, String... secrets) throws Exception {
        var bytes = new ByteArrayOutputStream(); try (var stream = new ObjectOutputStream(bytes)) { stream.writeObject(object); }
        assertNoSecret(bytes.toByteArray(), secrets); return bytes.size();
    }
    private static void assertNoSecret(byte[] serialized, String... secrets) {
        String binary = new String(serialized, StandardCharsets.ISO_8859_1);
        for (String secret : secrets) for (var encoding : List.of(StandardCharsets.UTF_8, StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE))
            if (binary.contains(new String(secret.getBytes(encoding), StandardCharsets.ISO_8859_1))) throw new AssertionError("认证材料进入序列化内容");
    }
    private record Noop(RowType shape) implements DeserializationSchema<RowData> {
        @Override public RowData deserialize(byte[] message) { return new GenericRowData(shape.getFieldCount()); }
        @Override public boolean isEndOfStream(RowData value) { return false; }
        @Override public TypeInformation<RowData> getProducedType() { return InternalTypeInfo.of(shape); }
    }
}

package cn.iocoder.yudao.framework.etl.flink.rabbitmq;

import cn.iocoder.yudao.framework.etl.flink.common.EtlJsonRowGuard;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.serialization.SerializationSchema;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.ConfigOptions;
import org.apache.flink.table.api.ValidationException;
import org.apache.flink.table.connector.ChangelogMode;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.connector.source.ScanTableSource;
import org.apache.flink.table.connector.source.SourceFunctionProvider;
import org.apache.flink.table.connector.sink.DynamicTableSink;
import org.apache.flink.table.connector.sink.SinkFunctionProvider;
import org.apache.flink.table.factories.*;
import org.apache.flink.table.types.DataType;

import java.util.Set;

/** 把官方 RabbitMQ 数据流 Source/Sink 接入 Flink JSON 表格式，不在规划阶段读取凭据。 */
public final class ManagedRabbitFactory implements DynamicTableSourceFactory, DynamicTableSinkFactory {
    private static final ConfigOption<String> HOST = ConfigOptions.key("host").stringType().noDefaultValue();
    private static final ConfigOption<Integer> PORT = ConfigOptions.key("port").intType().noDefaultValue();
    private static final ConfigOption<String> VHOST = ConfigOptions.key("virtual-host").stringType().noDefaultValue();
    private static final ConfigOption<String> QUEUE = ConfigOptions.key("queue").stringType().noDefaultValue();
    private static final ConfigOption<String> REF = ConfigOptions.key("credential-ref").stringType().noDefaultValue();
    private static final ConfigOption<Boolean> SSL = ConfigOptions.key("use-ssl").booleanType().defaultValue(true);
    private static final ConfigOption<Integer> PREFETCH = ConfigOptions.key("prefetch-count").intType().defaultValue(100);
    private static final ConfigOption<Integer> CONFIRM = ConfigOptions.key("confirm.timeout.ms").intType().defaultValue(10_000);
    @Override public String factoryIdentifier() { return "datahub-rabbitmq"; }
    @Override public Set<ConfigOption<?>> requiredOptions() { return Set.of(HOST, PORT, VHOST, QUEUE, REF, FactoryUtil.FORMAT); }
    @Override public Set<ConfigOption<?>> optionalOptions() { return Set.of(SSL, PREFETCH, CONFIRM); }
    @Override public DynamicTableSource createDynamicTableSource(DynamicTableSourceFactory.Context context) {
        var helper = FactoryUtil.createTableFactoryHelper(this, context);
        var format = helper.discoverDecodingFormat(DeserializationFormatFactory.class, FactoryUtil.FORMAT); helper.validate();
        var resource = resource(helper, false); int prefetch = helper.getOptions().get(PREFETCH); var type = context.getPhysicalRowDataType();
        return new ScanTableSource() {
            @Override public ChangelogMode getChangelogMode() { return ChangelogMode.insertOnly(); }
            @Override public ScanRuntimeProvider getScanRuntimeProvider(ScanContext runtime) {
                DeserializationSchema<org.apache.flink.table.data.RowData> decoder = format.createRuntimeDecoder(runtime, type);
                return SourceFunctionProvider.of(new ManagedRabbitSource(resource, prefetch,
                        new EtlJsonRowGuard(decoder, (org.apache.flink.table.types.logical.RowType) type.getLogicalType())), false);
            }
            @Override public DynamicTableSource copy() { return this; }
            @Override public String asSummaryString() { return "RabbitMQ 受管持续消息输入"; }
        };
    }
    @Override public DynamicTableSink createDynamicTableSink(DynamicTableSinkFactory.Context context) {
        var helper = FactoryUtil.createTableFactoryHelper(this, context);
        var format = helper.discoverEncodingFormat(SerializationFormatFactory.class, FactoryUtil.FORMAT); helper.validate();
        var resource = resource(helper, true); int timeout = helper.getOptions().get(CONFIRM); DataType type = context.getPhysicalRowDataType();
        return new DynamicTableSink() {
            @Override public ChangelogMode getChangelogMode(ChangelogMode requestedMode) { return ChangelogMode.insertOnly(); }
            @Override public SinkRuntimeProvider getSinkRuntimeProvider(Context runtime) {
                SerializationSchema<org.apache.flink.table.data.RowData> encoder = format.createRuntimeEncoder(runtime, type);
                return SinkFunctionProvider.of(new ManagedRabbitSink(resource, timeout, encoder), 1);
            }
            @Override public DynamicTableSink copy() { return this; }
            @Override public String asSummaryString() { return "RabbitMQ 受管确认消息输出"; }
        };
    }
    private static ManagedRabbitConnection resource(FactoryUtil.TableFactoryHelper helper, boolean output) {
        var config = helper.getOptions(); String host = config.get(HOST), queue = config.get(QUEUE), vhost = config.get(VHOST), reference = config.get(REF);
        if (!"json".equals(config.get(FactoryUtil.FORMAT)) || !host.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}") || config.get(PORT) < 1 || config.get(PORT) > 65535
                || vhost.isEmpty() || vhost.length() > 255 || vhost.codePoints().anyMatch(Character::isISOControl)
                || !queue.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}") || queue.startsWith("amq.")
                || !reference.matches("[A-Za-z][A-Za-z0-9_.-]{0,99}") || config.get(PREFETCH) < 1 || config.get(PREFETCH) > 10_000
                || config.get(CONFIRM) < 100 || config.get(CONFIRM) > 60_000) throw new ValidationException("RabbitMQ 受管连接参数无效");
        return new ManagedRabbitConnection(host, config.get(PORT), vhost, queue, reference, config.get(SSL), output);
    }
}

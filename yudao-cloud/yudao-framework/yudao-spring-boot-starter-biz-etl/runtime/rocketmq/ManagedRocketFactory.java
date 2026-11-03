package cn.iocoder.yudao.framework.etl.flink.rocketmq;

import cn.iocoder.yudao.framework.etl.flink.common.EtlJsonRowGuard;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.ConfigOptions;
import org.apache.flink.table.api.ValidationException;
import org.apache.flink.table.connector.ChangelogMode;
import org.apache.flink.table.connector.source.*;
import org.apache.flink.table.connector.sink.*;
import org.apache.flink.table.factories.*;
import org.apache.flink.table.types.logical.RowType;

import java.util.Set;

/** 官方 RocketMQ SDK 与标准 Flink JSON format 的薄表适配，规划时不读取秘密。 */
public final class ManagedRocketFactory implements DynamicTableSourceFactory, DynamicTableSinkFactory {
    private static final ConfigOption<String> SERVERS = ConfigOptions.key("nameservers").stringType().noDefaultValue();
    private static final ConfigOption<String> TOPIC = ConfigOptions.key("topic").stringType().noDefaultValue();
    private static final ConfigOption<String> GROUP = ConfigOptions.key("group.id").stringType().noDefaultValue();
    private static final ConfigOption<String> REF = ConfigOptions.key("credential-ref").stringType().noDefaultValue();
    private static final ConfigOption<Boolean> TLS = ConfigOptions.key("use-tls").booleanType().defaultValue(true);
    private static final ConfigOption<String> RESET = ConfigOptions.key("offset.reset").stringType().defaultValue("earliest");
    private static final ConfigOption<Integer> PULL = ConfigOptions.key("pull.timeout.ms").intType().defaultValue(1000);
    private static final ConfigOption<Integer> SEND = ConfigOptions.key("send.timeout.ms").intType().defaultValue(10_000);
    @Override public String factoryIdentifier() { return "datahub-rocketmq"; }
    @Override public Set<ConfigOption<?>> requiredOptions() { return Set.of(SERVERS, TOPIC, GROUP, REF, FactoryUtil.FORMAT); }
    @Override public Set<ConfigOption<?>> optionalOptions() { return Set.of(TLS, RESET, PULL, SEND); }
    @Override public DynamicTableSource createDynamicTableSource(DynamicTableSourceFactory.Context context) {
        var helper = FactoryUtil.createTableFactoryHelper(this, context);
        var format = helper.discoverDecodingFormat(DeserializationFormatFactory.class, FactoryUtil.FORMAT); helper.validate();
        if (context.getCatalogTable().getOptions().containsKey(SEND.key())) throw new ValidationException("RocketMQ 输入不接受输出超时参数");
        var resource = resource(helper, false); var config = helper.getOptions(); var type = context.getPhysicalRowDataType();
        return new ScanTableSource() {
            @Override public ChangelogMode getChangelogMode() { return ChangelogMode.insertOnly(); }
            @Override public ScanRuntimeProvider getScanRuntimeProvider(ScanContext runtime) {
                var decoder = new EtlJsonRowGuard(format.createRuntimeDecoder(runtime, type), (RowType) type.getLogicalType());
                return SourceFunctionProvider.of(new ManagedRocketSource(resource, config.get(RESET), config.get(PULL), decoder), false);
            }
            @Override public DynamicTableSource copy() { return this; }
            @Override public String asSummaryString() { return "RocketMQ 受管检查点消息输入"; }
        };
    }
    @Override public DynamicTableSink createDynamicTableSink(DynamicTableSinkFactory.Context context) {
        var helper = FactoryUtil.createTableFactoryHelper(this, context);
        var format = helper.discoverEncodingFormat(SerializationFormatFactory.class, FactoryUtil.FORMAT); helper.validate();
        if (context.getCatalogTable().getOptions().containsKey(RESET.key()) || context.getCatalogTable().getOptions().containsKey(PULL.key())) throw new ValidationException("RocketMQ 输出不接受输入位点参数");
        var resource = resource(helper, true); var type = context.getPhysicalRowDataType(); int timeout = helper.getOptions().get(SEND);
        return new DynamicTableSink() {
            @Override public ChangelogMode getChangelogMode(ChangelogMode requested) { return ChangelogMode.insertOnly(); }
            @Override public SinkRuntimeProvider getSinkRuntimeProvider(Context runtime) { return SinkFunctionProvider.of(new ManagedRocketSink(resource, timeout, format.createRuntimeEncoder(runtime, type)), 1); }
            @Override public DynamicTableSink copy() { return this; }
            @Override public String asSummaryString() { return "RocketMQ 受管同步确认输出"; }
        };
    }
    private static ManagedRocketResource resource(FactoryUtil.TableFactoryHelper helper, boolean output) {
        var config = helper.getOptions(); String servers = config.get(SERVERS), topic = config.get(TOPIC), group = config.get(GROUP), reference = config.get(REF);
        if (!"json".equals(config.get(FactoryUtil.FORMAT)) || servers.length() > 2048 || !servers.matches("[A-Za-z0-9.-]+:[0-9]{1,5}(;[A-Za-z0-9.-]+:[0-9]{1,5})*")
                || !topic.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,126}") || !group.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,126}")
                || !reference.matches("[A-Za-z][A-Za-z0-9_.-]{0,99}") || !Set.of("earliest", "latest").contains(config.get(RESET))
                || config.get(PULL) < 100 || config.get(PULL) > 10_000 || config.get(SEND) < 100 || config.get(SEND) > 60_000) throw new ValidationException("RocketMQ 受管参数无效");
        String[] addresses = servers.split(";");
        if (addresses.length > 8 || new java.util.HashSet<>(java.util.Arrays.asList(addresses)).size() != addresses.length) throw new ValidationException("RocketMQ NameServer 范围无效");
        for (String address : addresses) { int port = Integer.parseInt(address.substring(address.lastIndexOf(':') + 1)); if (port < 1 || port > 65535) throw new ValidationException("RocketMQ NameServer 端口无效"); }
        return new ManagedRocketResource(servers, topic, group, reference, config.get(TLS), output);
    }
}

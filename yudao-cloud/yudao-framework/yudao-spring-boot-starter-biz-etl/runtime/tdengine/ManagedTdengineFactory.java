package cn.iocoder.yudao.framework.etl.flink.tdengine;

import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.ConfigOptions;
import org.apache.flink.table.api.ValidationException;
import org.apache.flink.table.connector.ChangelogMode;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.connector.source.ScanTableSource;
import org.apache.flink.table.connector.source.SourceProvider;
import org.apache.flink.table.factories.DynamicTableSourceFactory;
import org.apache.flink.table.factories.FactoryUtil;
import org.apache.flink.table.types.logical.RowType;

import java.util.Set;

/** 官方 TDengine TMQ 的薄资源适配；规划阶段禁止读取密码或建立连接。 */
public final class ManagedTdengineFactory implements DynamicTableSourceFactory {
    private static final ConfigOption<String> SERVERS = ConfigOptions.key("bootstrap.servers").stringType().noDefaultValue();
    private static final ConfigOption<String> TOPIC = ConfigOptions.key("topic").stringType().noDefaultValue();
    private static final ConfigOption<String> GROUP = ConfigOptions.key("group.id").stringType().noDefaultValue();
    private static final ConfigOption<String> CREDENTIAL = ConfigOptions.key("credential-ref").stringType().noDefaultValue();
    private static final ConfigOption<String> OFFSET = ConfigOptions.key("auto.offset.reset").stringType().defaultValue("earliest");
    private static final ConfigOption<Integer> POLL = ConfigOptions.key("poll.interval.ms").intType().defaultValue(1000);
    private static final ConfigOption<Boolean> SSL = ConfigOptions.key("use-ssl").booleanType().defaultValue(true);
    @Override public String factoryIdentifier() { return "datahub-tdengine-tmq"; }
    @Override public Set<ConfigOption<?>> requiredOptions() { return Set.of(SERVERS, TOPIC, GROUP, CREDENTIAL); }
    @Override public Set<ConfigOption<?>> optionalOptions() { return Set.of(OFFSET, POLL, SSL); }
    @Override public DynamicTableSource createDynamicTableSource(Context context) {
        var helper = FactoryUtil.createTableFactoryHelper(this, context); helper.validate(); var config = helper.getOptions();
        String servers = config.get(SERVERS), topic = config.get(TOPIC), group = config.get(GROUP), reference = config.get(CREDENTIAL), offset = config.get(OFFSET);
        int poll = config.get(POLL);
        if (!servers.matches("[A-Za-z0-9.-]+:[0-9]{1,5}") || !topic.matches("[A-Za-z_][A-Za-z0-9_]{0,191}")
                || !group.matches("[A-Za-z0-9._-]{1,128}") || !reference.matches("[A-Za-z][A-Za-z0-9_.-]{0,99}")
                || !Set.of("earliest", "latest").contains(offset) || poll < 100 || poll > 10_000) throw new ValidationException("TDengine TMQ 受管参数无效");
        int port = Integer.parseInt(servers.substring(servers.lastIndexOf(':') + 1));
        if (port < 1 || port > 65535) throw new ValidationException("TDengine 服务端口无效");
        RowType rowType = (RowType) context.getPhysicalRowDataType().getLogicalType();
        if (rowType.getFieldCount() < 1 || rowType.getFieldCount() > 1024) throw new ValidationException("TDengine 字段合同缺失或超限");
        return new ManagedTableSource(servers, topic, group, offset, reference, poll, config.get(SSL), rowType);
    }

    private record ManagedTableSource(String servers, String topic, String group, String offset, String reference,
                                      int poll, boolean ssl, RowType rowType) implements ScanTableSource {
        @Override public ChangelogMode getChangelogMode() { return ChangelogMode.insertOnly(); }
        @Override public ScanRuntimeProvider getScanRuntimeProvider(ScanContext context) {
            return SourceProvider.of(new ManagedTdengineSource(servers, topic, group, offset, reference, poll, ssl, rowType));
        }
        @Override public DynamicTableSource copy() { return this; }
        @Override public String asSummaryString() { return "TDengine受管TMQ持续输入"; }
    }
}

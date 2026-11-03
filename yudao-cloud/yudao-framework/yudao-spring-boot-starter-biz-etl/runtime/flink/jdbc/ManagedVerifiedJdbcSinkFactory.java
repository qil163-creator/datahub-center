package cn.iocoder.yudao.framework.etl.flink.jdbc;

import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.InputTypeConfigurable;
import org.apache.flink.configuration.*;
import org.apache.flink.runtime.state.*;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.functions.sink.*;
import org.apache.flink.table.api.ValidationException;
import org.apache.flink.table.catalog.*;
import org.apache.flink.table.connector.*;
import org.apache.flink.table.connector.sink.*;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.factories.*;
import org.apache.flink.types.RowKind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 继承已授权 Catalog JDBC 定义并验证 PK 后才交给官方 sink；不接收节点提供的连接凭据。 */
public final class ManagedVerifiedJdbcSinkFactory implements DynamicTableSinkFactory {
    @Override public String factoryIdentifier() { return "datahub-verified-jdbc"; }
    @Override public Set<ConfigOption<?>> requiredOptions() { return Set.of(); }
    @Override public Set<ConfigOption<?>> optionalOptions() { return Set.of(); }
    @Override public DynamicTableSink createDynamicTableSink(Context context) {
        Map<String,String> options = new HashMap<>(context.getCatalogTable().getOptions());
        var keys = Arrays.asList(required(options,"datahub.primary-keys").split(",",-1));
        String schema = required(options,"datahub.schema"), table = required(options,"datahub.table"), hash = required(options,"datahub.url-sha256");
        String url = options.get("url"), physical = options.get("table-name");
        if (url == null || !url.startsWith("jdbc:postgresql://") || !hash.matches("[a-f0-9]{64}") || !sha256(url).equals(hash)
                || !(Objects.equals(physical, schema + "." + table) || Objects.equals(physical, quote(schema) + "." + quote(table))))
            throw invalid("受管 JDBC 实际服务或物理表与授权事实不一致");
        var shape = context.getCatalogTable().getResolvedSchema();
        var actual = shape.getPrimaryKey().orElseThrow(() -> invalid("原 Catalog 未声明真实主键，不能执行增量更新")).getColumns();
        if (keys.isEmpty() || keys.stream().distinct().count()!=keys.size() || !actual.equals(keys)) throw invalid("原 Catalog 主键与已授权数据库主键不一致");
        for (String key : keys) if (shape.getColumn(key).orElseThrow(() -> invalid("目标主键字段不存在")).getDataType().getLogicalType().isNullable()) throw invalid("目标主键必须声明 NOT NULL");
        String nullStrategy = context.getConfiguration().getOptional(ConfigOptions.key("table.exec.sink.not-null-enforcer").stringType().noDefaultValue()).orElse("ERROR");
        if (!nullStrategy.equalsIgnoreCase("ERROR")) throw invalid("增量输出必须对空主键失败，不能静默丢弃");
        for (String key : new ArrayList<>(options.keySet())) if (key.startsWith("datahub.")) {
            if (!Set.of("datahub.primary-keys","datahub.schema","datahub.table","datahub.url-sha256").contains(key)) throw invalid("受管 JDBC 包含未知内部选项");
            options.remove(key);
        }
        options.put("connector","jdbc");
        var delegatedContext = new FactoryUtil.DefaultDynamicTableContext(context.getObjectIdentifier(), context.getCatalogTable().copy(options), Map.of(), context.getConfiguration(), context.getClassLoader(), context.isTemporary());
        var factory = FactoryUtil.discoverFactory(context.getClassLoader(), DynamicTableSinkFactory.class, "jdbc");
        var delegate = factory.createDynamicTableSink(delegatedContext);
        int[] indexes = keys.stream().mapToInt(shape.getColumnNames()::indexOf).toArray();
        return new VerifiedSink(delegate,indexes);
    }
    private record VerifiedSink(DynamicTableSink delegate,int[] indexes) implements DynamicTableSink {
        @Override public ChangelogMode getChangelogMode(ChangelogMode requested) { return delegate.getChangelogMode(requested); }
        @Override public SinkRuntimeProvider getSinkRuntimeProvider(Context context) {
            var provider = delegate.getSinkRuntimeProvider(context);
            if (!(provider instanceof SinkFunctionProvider functions)) throw invalid("官方 JDBC sink 运行接口版本未经验证");
            @SuppressWarnings("unchecked") var function=(SinkFunction<RowData>)functions.createSinkFunction();
            return SinkFunctionProvider.of(new CheckedFunction(function,indexes),functions.getParallelism().orElse(1));
        }
        @Override public DynamicTableSink copy() { return new VerifiedSink(delegate.copy(),indexes.clone()); }
        @Override public String asSummaryString() { return "受管 PostgreSQL 主键增量输出"; }
    }
    private static final class CheckedFunction extends RichSinkFunction<RowData> implements CheckpointedFunction, InputTypeConfigurable {
        private final SinkFunction<RowData> delegate; private final int[] indexes;
        CheckedFunction(SinkFunction<RowData> delegate,int[] indexes) { this.delegate=delegate; this.indexes=indexes.clone(); }
        private RichSinkFunction<RowData> rich() {
            if (!(delegate instanceof RichSinkFunction<?>)) throw invalid("官方 JDBC sink 生命周期版本未经验证");
            @SuppressWarnings("unchecked") var rich=(RichSinkFunction<RowData>)delegate; rich.setRuntimeContext(getRuntimeContext()); return rich;
        }
        @Override public void open(Configuration configuration) throws Exception { rich().open(configuration); }
        @Override public void invoke(RowData row,Context context) throws Exception {
            for(int index:indexes) if(row.isNullAt(index)) throw invalid("增量输出实际主键为空，拒绝静默丢弃或插入重复行");
            if(row.getRowKind()==RowKind.UPDATE_BEFORE) throw invalid("JDBC 增量输出不接受未规范化的更新前记录");
            delegate.invoke(row,context);
        }
        @Override public void initializeState(FunctionInitializationContext context) throws Exception { rich(); if(delegate instanceof CheckpointedFunction state) state.initializeState(context); }
        @Override public void snapshotState(FunctionSnapshotContext context) throws Exception { if(delegate instanceof CheckpointedFunction state) state.snapshotState(context); }
        @Override public void setInputType(TypeInformation<?> type,ExecutionConfig config) { if(delegate instanceof InputTypeConfigurable configurable) configurable.setInputType(type,config); }
        @Override public void finish() throws Exception { delegate.finish(); }
        @Override public void close() throws Exception { rich().close(); }
    }
    private static String required(Map<String,String> values,String key) { String value=values.get(key); if(value==null||value.isBlank()||value.length()>2048) throw invalid("受管 JDBC 验证事实缺失"); return value; }
    private static String quote(String value) { return "\""+value.replace("\"","\"\"")+"\""; }
    private static String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch(Exception impossible) { throw invalid("摘要算法不可用"); } }
    private static ValidationException invalid(String value) { return new ValidationException(value); }
}

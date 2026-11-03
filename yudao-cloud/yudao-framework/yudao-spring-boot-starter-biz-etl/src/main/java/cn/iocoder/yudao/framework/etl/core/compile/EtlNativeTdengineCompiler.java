package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;

import java.util.*;

/** TDengine 官方 TMQ Source 的受管会话编译；凭据只在原生运行端按引用读取。 */
public final class EtlNativeTdengineCompiler {
    private static final Set<String> NODE_KEYS = Set.of("databaseId", "resourceBindingId", "topic", "groupId", "offsetReset", "columns");
    private static final Set<String> COLUMN_KEYS = Set.of("name", "type", "key", "comment", "index");

    /** 只承载连接器资源约束；任务、工作组和数据源授权由业务适配层执行。 */
    public record Binding(String bootstrapServers, Set<String> sourceTopics, Set<String> consumerGroups,
                          String credentialRef, Map<String, String> columns, int pollIntervalMillis,
                          boolean useSsl, boolean flinkConnectorInstalled, String jdbcUrl, String schema, String tableName,
                          boolean sparkConnectorInstalled, boolean flinkBoundedConnectorInstalled) {
        public Binding(String bootstrapServers, Set<String> sourceTopics, Set<String> consumerGroups, String credentialRef, Map<String, String> columns, int pollIntervalMillis, boolean useSsl, boolean flinkConnectorInstalled) {
            this(bootstrapServers, sourceTopics, consumerGroups, credentialRef, columns, pollIntervalMillis, useSsl, flinkConnectorInstalled, null, null, null, false, false);
        }
        public Binding {
            sourceTopics = sourceTopics == null ? Set.of() : Set.copyOf(sourceTopics);
            consumerGroups = consumerGroups == null ? Set.of() : Set.copyOf(consumerGroups);
            columns = columns == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(columns));
        }
    }

    private EtlNativeTdengineCompiler() { }

    /** 使用 DB Switch 原生证据核验字段，不从展示 DTO 的默认时间戳小数位推断实际精度。 */
    public static void validateMetadata(Map<String, String> expected, List<ColumnDescription> actual) {
        if (expected == null || expected.isEmpty() || actual == null || actual.isEmpty()) throw invalid("TDengine 主题字段或物理元数据缺失");
        if (!new ArrayList<>(expected.keySet()).equals(actual.stream().map(ColumnDescription::getFieldName).toList()))
            throw invalid("TDengine 受管主题必须按物理表完整字段顺序声明，投影请在后续视图中执行");
        var byName = new LinkedHashMap<String, ColumnDescription>();
        for (var field : actual) if (byName.putIfAbsent(field.getFieldName(), field) != null) throw invalid("TDengine 元数据字段重复");
        for (var entry : expected.entrySet()) {
            var field = byName.get(entry.getKey());
            if (field == null || field.getDbType() != ProductTypeEnum.TDENGINE) throw invalid("TDengine 主题字段不属于已验证物理表");
            var evidence = field.getMetadataEvidence();
            if (!evidence.provenance().nativeProduct(ProductTypeEnum.TDENGINE) || evidence.nativeType().state() != MetadataValue.State.VALUE) throw invalid("TDengine 原生字段类型证据未知或服务器版本尚未验证");
            String nativeType = evidence.nativeType().value().toUpperCase(Locale.ROOT).replaceAll("\\s+", ""), mapped;
            if (nativeType.equals("TIMESTAMP")) {
                if (evidence.numericScale().state() != MetadataValue.State.VALUE || !Set.of(3, 6, 9).contains(evidence.numericScale().value())) throw invalid("TDengine 数据库时间精度未知");
                mapped = "TIMESTAMP(" + evidence.numericScale().value() + ")";
            } else if (nativeType.startsWith("VARCHAR(") || nativeType.startsWith("BINARY(") || nativeType.startsWith("NCHAR(") || Set.of("VARCHAR", "BINARY", "NCHAR").contains(nativeType)) mapped = "STRING";
            else if (nativeType.equals("BOOL")) mapped = "BOOLEAN";
            else mapped = type(nativeType);
            if (!type(entry.getValue()).equals(mapped)) throw invalid("TDengine 主题字段类型或时间精度与物理元数据不一致");
        }
    }

    public static Set<String> supportedCodes(EtlNativeSqlCompiler.Engine engine, boolean streaming) {
        return engine == EtlNativeSqlCompiler.Engine.FLINK || !streaming ? Set.of("read-tdengine") : Set.of();
    }

    public static EtlNativeConnectorCompiler.TablePlan compile(EtlNativeSqlCompiler.Engine engine, boolean streaming,
            String nodeIdentity, Map<String, Object> config, Binding binding, Set<String> authorizedSecretRefs) {
        if (!streaming) return batch(engine, nodeIdentity, config, binding, authorizedSecretRefs);
        if (engine != EtlNativeSqlCompiler.Engine.FLINK) throw invalid("TDengine TMQ 当前仅支持 Flink 实时模式，不能把全量 JDBC 读取标为持续采集");
        if (config == null || JSONUtil.toJsonStr(config).length() > 500_000) throw invalid("TDengine 组件配置缺失或超限");
        var values = JSONUtil.parseObj(config);
        if (!NODE_KEYS.containsAll(values.keySet())) throw invalid("TDengine 配置包含未适配参数");
        if (binding == null || !binding.flinkConnectorInstalled()) throw invalid("TDengine 官方 TMQ 连接器尚未安装并绑定到当前工作环境");
        String servers = binding.bootstrapServers();
        if (servers == null || !servers.matches("[A-Za-z0-9.-]+:[0-9]{1,5}")) throw invalid("TDengine 受管 WebSocket 服务地址无效");
        int port = Integer.parseInt(servers.substring(servers.lastIndexOf(':') + 1));
        if (port < 1 || port > 65535 || binding.pollIntervalMillis() < 100 || binding.pollIntervalMillis() > 10_000) throw invalid("TDengine 端口或轮询间隔无效");
        String reference = binding.credentialRef();
        if (reference == null || !reference.matches("[A-Za-z][A-Za-z0-9_.-]{0,99}") || authorizedSecretRefs == null || !authorizedSecretRefs.contains(reference))
            throw invalid("TDengine 凭据引用未获当前任务授权");
        String topic = values.getStr("topic", ""), group = values.getStr("groupId", "");
        if (!topic.matches("[A-Za-z_][A-Za-z0-9_]{0,191}") || !binding.sourceTopics().contains(topic)) throw invalid("TDengine 主题未在受管读取白名单中");
        if (!group.matches("[A-Za-z0-9._-]{1,128}") || !binding.consumerGroups().contains(group)) throw invalid("TDengine 消费组未在受管白名单中");
        String offset = values.getStr("offsetReset", "earliest");
        if (!Set.of("earliest", "latest").contains(offset)) throw invalid("TDengine 起始位点无效");
        if (binding.columns().isEmpty() || binding.columns().size() > 1024) throw invalid("TDengine 主题缺少经元数据验证的有序字段合同");
        var all = new LinkedHashMap<String, String>();
        binding.columns().forEach((name, type) -> { identifier(name); all.put(name, type(type)); });
        var selected = new LinkedHashMap<String, String>();
        var columns = values.getJSONArray("columns");
        if (columns == null || columns.isEmpty()) selected.putAll(all);
        else {
            if (columns.size() > 1024) throw invalid("TDengine 选择字段超限");
            for (Object item : columns) {
                var column = JSONUtil.parseObj(item);
                if (!COLUMN_KEYS.containsAll(column.keySet())) throw invalid("TDengine 属性字段包含未适配参数");
                String name = column.getStr("name", "");
                if (!all.containsKey(name) || selected.putIfAbsent(name, all.get(name)) != null) throw invalid("TDengine 选择字段不存在或重复");
                if (!column.getStr("key", name).equals(name)) throw invalid("TDengine 字段重命名请使用字段选择组件");
                String declared = column.getStr("type", "");
                if (!declared.isBlank() && !compatible(declared, all.get(name))) throw invalid("TDengine 属性类型与受管主题元数据不一致");
            }
        }
        String alias = "dh_native_tdengine_" + DigestUtil.sha256Hex(Objects.requireNonNull(nodeIdentity)).substring(0, 24), raw = alias + "_raw";
        var options = new LinkedHashMap<String, String>();
        options.put("connector", "datahub-tdengine-tmq"); options.put("bootstrap.servers", servers); options.put("topic", topic);
        options.put("group.id", group); options.put("auto.offset.reset", offset); options.put("credential-ref", reference);
        options.put("poll.interval.ms", Integer.toString(binding.pollIntervalMillis()));
        options.put("use-ssl", Boolean.toString(binding.useSsl()));
        String ddl = "CREATE TEMPORARY TABLE " + identifier(raw) + " (" + String.join(", ", all.entrySet().stream().map(entry -> identifier(entry.getKey()) + " " + entry.getValue()).toList())
                + ") WITH (" + String.join(", ", options.entrySet().stream().map(entry -> literal(entry.getKey()) + "=" + literal(entry.getValue())).toList()) + ");\n"
                + "CREATE TEMPORARY VIEW " + identifier(alias) + " AS SELECT " + String.join(", ", selected.keySet().stream().map(EtlNativeTdengineCompiler::identifier).toList()) + " FROM " + identifier(raw) + ";\n";
        return new EtlNativeConnectorCompiler.TablePlan(ddl, new EtlNativeSqlCompiler.Table(List.of(alias), selected, ProductTypeEnum.TDENGINE, null, alias, true));
    }

    /** 离线读取只使用固定表的 JDBC 查询；消息主题和持续位点不会被改称有界。 */
    private static EtlNativeConnectorCompiler.TablePlan batch(EtlNativeSqlCompiler.Engine engine, String identity,
            Map<String, Object> input, Binding binding, Set<String> authorizedSecretRefs) {
        if (input == null || JSONUtil.toJsonStr(input).length() > 500_000) throw invalid("TDengine批处理配置缺失或超限");
        var config = JSONUtil.parseObj(input);
        if (!Set.of("databaseId", "resourceBindingId", "tableName", "readMode", "columns").containsAll(config.keySet()) || !"TABLE".equals(config.getStr("readMode", "TABLE").toUpperCase(Locale.ROOT)))
            throw invalid("TDengine离线读取仅支持已授权的固定物理表，不能携带TMQ位点或任意SQL");
        if (binding == null || (engine == EtlNativeSqlCompiler.Engine.SPARK ? !binding.sparkConnectorInstalled() : !binding.flinkBoundedConnectorInstalled()))
            throw invalid("TDengine离线JDBC连接器未安装到当前授权引擎");
        if (binding.jdbcUrl() == null || !binding.jdbcUrl().matches("jdbc:TAOS-(WS|RS)://[A-Za-z0-9.-]+:[0-9]{1,5}/[A-Za-z_][A-Za-z0-9_]{0,62}")
                || !Objects.equals(binding.tableName(), config.getStr("tableName"))) throw invalid("TDengine离线物理地址或表名与受管绑定不一致");
        String reference = binding.credentialRef();
        if (reference == null || !reference.matches("[A-Za-z][A-Za-z0-9_.-]{0,99}") || authorizedSecretRefs == null || !authorizedSecretRefs.contains(reference))
            throw invalid("TDengine离线凭据引用未获当前任务授权");
        var selected = new LinkedHashMap<String, String>(); var declared = config.getJSONArray("columns");
        if (binding.columns().isEmpty() || binding.columns().size() > 1024) throw invalid("TDengine离线读取缺少真实元数据字段合同");
        if (declared == null || declared.isEmpty()) binding.columns().forEach((name, raw) -> selected.put(name, type(raw)));
        else for (Object item : declared) {
            var field = JSONUtil.parseObj(item); String name = field.getStr("name", ""), nativeType = binding.columns().get(name);
            if (!COLUMN_KEYS.containsAll(field.keySet()) || nativeType == null || selected.putIfAbsent(name, type(nativeType)) != null
                    || !field.getStr("key", name).equals(name) || !field.getStr("type", "").isBlank() && !compatible(field.getStr("type"), nativeType))
                throw invalid("TDengine离线选择字段不属于固定物理表或改变了原生类型");
        }
        String relation = RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.TDENGINE, binding.schema(), binding.tableName());
        String query = "SELECT " + String.join(", ", selected.keySet().stream().map(name -> RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.TDENGINE, name)).toList()) + " FROM " + relation;
        var plan = new JSONObject().set("jdbcUrl", binding.jdbcUrl()).set("schema", binding.schema()).set("tableName", binding.tableName())
                .set("query", query).set("credentialRef", reference);
        return EtlNativeBoundedMessageSql.table(engine, identity, "tdengine", false, selected, plan, List.of());
    }

    private static String type(String raw) {
        if (raw == null) throw invalid("TDengine 字段类型缺失");
        String type = raw.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        if (Set.of("STRING", "BOOLEAN", "TINYINT", "SMALLINT", "INT", "BIGINT", "FLOAT", "DOUBLE").contains(type) || type.matches("TIMESTAMP\\([0-9]\\)")) return type;
        if (type.matches("DECIMAL\\(([1-9]|[12][0-9]|3[0-8]),([0-9]|[12][0-9]|3[0-8])\\)")) {
            String[] parts = type.substring(8, type.length() - 1).split(","); if (Integer.parseInt(parts[1]) <= Integer.parseInt(parts[0])) return type;
        }
        throw invalid("TDengine 原生字段类型尚未验证");
    }
    private static boolean compatible(String declared, String nativeType) {
        String value = declared.toUpperCase(Locale.ROOT);
        if (value.equals(nativeType)) return true;
        return switch (value) { case "VARCHAR", "CHAR", "TEXT" -> nativeType.equals("STRING"); case "LONG" -> nativeType.equals("BIGINT"); case "INTEGER" -> Set.of("INT", "TINYINT", "SMALLINT").contains(nativeType); case "NUMBER" -> Set.of("FLOAT", "DOUBLE").contains(nativeType); case "BIGNUMBER", "BIGDECIMAL", "DECIMAL", "NUMERIC" -> nativeType.startsWith("DECIMAL("); case "DATE", "TIMESTAMP" -> nativeType.startsWith("TIMESTAMP("); default -> false; };
    }
    private static String identifier(String name) { if (name == null || name.isBlank() || name.length() > 128 || name.codePoints().anyMatch(Character::isISOControl)) throw invalid("TDengine 字段标识符无效"); return "`" + name.replace("`", "``") + "`"; }
    private static String literal(String value) { if (value == null || value.length() > 8192 || value.indexOf('\\') >= 0 || value.codePoints().anyMatch(Character::isISOControl)) throw invalid("TDengine 连接器字符串无效"); return "'" + value.replace("'", "''") + "'"; }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}

package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 将经过各组件完整准入的有界计划映射为 Flink 原生表，不在控制面读取业务数据。 */
public final class EtlNativeFlinkConnectorSql {
    private EtlNativeFlinkConnectorSql() { }

    public static EtlNativeConnectorCompiler.TablePlan table(String identity, String kind, boolean output,
            Map<String, String> columns, JSONObject plan, List<Map<String, Object>> mappings) {
        if (!Set.of("api", "soap", "ftp", "redis", "odps", "file", "excel", "hdfs", "mongodb", "kafka", "rabbitmq", "rocketmq", "tdengine").contains(kind)
                || identity == null || identity.isBlank() || identity.length() > 256 || columns == null || columns.isEmpty() || columns.size() > 1024)
            throw new IllegalArgumentException("Flink 有界连接器标识或字段合同无效");
        if (plan == null || plan.toString().length() > 750_000) throw new IllegalArgumentException("Flink 有界连接器计划缺失或超限");
        var guards = plan.getJSONObject("resultTypes");
        if (guards == null) guards = new JSONObject();
        var declarations = new ArrayList<String>();
        for (var field : columns.entrySet()) {
            String type = type(field.getValue());
            declarations.add(quote(field.getKey()) + " " + type);
            var guard = guards.getJSONObject(field.getKey());
            if (guard == null) guards.set(field.getKey(), new JSONObject().set("sqlType", type).set("nullable", true).set("length", 0));
        }
        plan.set("resultTypes", guards);
        String payload = Base64.getEncoder().encodeToString(plan.toString().getBytes(StandardCharsets.UTF_8));
        String alias = "dh_native_" + kind + "_" + DigestUtil.sha256Hex(identity).substring(0, 24);
        String sql = "CREATE TEMPORARY TABLE " + quote(alias) + " (" + String.join(", ", declarations)
                + ") WITH ('connector' = 'datahub-bounded', 'kind' = '" + kind + "', 'direction' = '" + (output ? "sink" : "source")
                + "', 'plan' = '" + payload + "');\n";
        return new EtlNativeConnectorCompiler.TablePlan(sql, new EtlNativeSqlCompiler.Table(List.of(alias),
                Collections.unmodifiableMap(new LinkedHashMap<>(columns)), ProductTypeEnum.UNKNOWN, null, alias, false), mappings);
    }

    private static String type(String value) {
        String type = Objects.toString(value, "").toUpperCase(Locale.ROOT).replace(" ", "");
        if ("BINARY".equals(type) || "BYTES".equals(type)) return "BYTES";
        if (Set.of("STRING", "BOOLEAN", "INT", "BIGINT", "SMALLINT", "TINYINT", "FLOAT", "DOUBLE", "DATE", "TIMESTAMP").contains(type)) return type;
        if (type.matches("DECIMAL\\(([1-9]|[12][0-9]|3[0-8]),([0-9]|[12][0-9]|3[0-8])\\)")) {
            String[] sizes = type.substring(8, type.length() - 1).split(",");
            if (Integer.parseInt(sizes[1]) <= Integer.parseInt(sizes[0])) return type;
        }
        if (type.matches("TIMESTAMP\\([0-9]\\)")) return type;
        throw new IllegalArgumentException("Flink 有界连接器字段类型尚未提供无损适配");
    }
    private static String quote(String value) {
        if (value == null || value.isBlank() || value.length() > 128 || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Flink 有界连接器字段名称无效");
        return "`" + value.replace("`", "``") + "`";
    }
}

package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 已完成资源准入的有限消息计划共用同一传输SPI，Spark与Flink不交换执行引擎。 */
public final class EtlNativeBoundedMessageSql {
    private EtlNativeBoundedMessageSql() { }

    public static EtlNativeConnectorCompiler.TablePlan table(EtlNativeSqlCompiler.Engine engine, String identity,
            String kind, boolean output, Map<String, String> fields, JSONObject plan, List<Map<String, Object>> mappings) {
        if (!Set.of("kafka", "rabbitmq", "rocketmq", "tdengine").contains(kind) || fields.isEmpty() || fields.size() > 1024)
            throw new IllegalArgumentException("有界消息计划或字段合同无效");
        plan.set("maxRows", 1_000_000).set("maxBytes", 67_108_864).set("timeoutMillis", 30_000);
        var guards = new JSONObject();
        fields.forEach((name, type) -> guards.set(name, new JSONObject().set("sqlType", engine == EtlNativeSqlCompiler.Engine.SPARK && type.startsWith("TIMESTAMP") ? "TIMESTAMP" : type).set("nullable", true).set("length", 0)));
        plan.set("resultTypes", guards);
        if (engine == EtlNativeSqlCompiler.Engine.FLINK)
            return EtlNativeFlinkConnectorSql.table(identity, kind, output, fields, plan, mappings);
        String payload = Base64.getEncoder().encodeToString(plan.toString().getBytes(StandardCharsets.UTF_8));
        String alias = "dh_native_" + kind + "_" + DigestUtil.sha256Hex(identity).substring(0, 24);
        var columns = new LinkedHashMap<String, String>(); fields.forEach((name, type) -> { String canonical = EtlNativeScriptCompiler.canonical(type, engine); columns.put(name, canonical.startsWith("TIMESTAMP") ? "TIMESTAMP" : canonical); });
        String sql = "CREATE TEMPORARY VIEW `" + alias + "` (" + String.join(", ", columns.entrySet().stream().map(field -> quote(field.getKey()) + " " + field.getValue()).toList())
                + ") USING cn.iocoder.yudao.framework.etl.spark.EtlBoundedMessageProvider OPTIONS (`datahub.kind` '" + kind
                + "', `datahub.direction` '" + (output ? "sink" : "source") + "', `datahub.plan` '" + payload + "');\n";
        return new EtlNativeConnectorCompiler.TablePlan(sql, new EtlNativeSqlCompiler.Table(List.of(alias), columns, ProductTypeEnum.UNKNOWN, null, alias, false), mappings);
    }
    private static String quote(String value) {
        if (value == null || value.isBlank() || value.length() > 128 || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("消息字段名称无效");
        return "`" + value.replace("`", "``") + "`";
    }
}

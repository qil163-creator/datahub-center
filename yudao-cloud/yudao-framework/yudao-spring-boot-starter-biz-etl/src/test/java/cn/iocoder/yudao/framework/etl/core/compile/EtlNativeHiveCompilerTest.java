package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static cn.iocoder.yudao.framework.etl.core.compile.EtlNativeSqlCompiler.Engine.*;
import static org.junit.jupiter.api.Assertions.*;

class EtlNativeHiveCompilerTest {
    private static EtlNativeSqlCompiler.Table table(String name) {
        var columns = new LinkedHashMap<String, String>(); columns.put("id", "BIGINT"); columns.put("name", "STRING"); columns.put("amount", "DECIMAL(20,3)");
        return new EtlNativeSqlCompiler.Table(List.of("spark_catalog", "hive_native_components", name), columns, ProductTypeEnum.HIVE, "hive_native_components", name);
    }
    private static Map<String, Object> read() {
        return new LinkedHashMap<>(Map.of("databaseId", "9007199254740993", "tableName", "source", "readMode", "FULL", "whereCondition", "id >= 2 AND name <> 'skip'",
                "selectedColumns", List.of("id", "name", "amount"), "columns", List.of(Map.of("name", "id", "type", "LONG"),
                        Map.of("name", "name", "type", "STRING", "alias", "label"), Map.of("name", "amount", "type", "DECIMAL", "length", 20, "precision", 3))));
    }
    private static Map<String, Object> write() {
        return new LinkedHashMap<>(Map.of("databaseId", 2, "tableName", "target", "writeModeType", 2, "columns", List.of(
                Map.of("name", "id", "type", "BIGINT", "key", "id"), Map.of("name", "name", "type", "STRING", "key", "label"),
                Map.of("name", "amount", "type", "DECIMAL(20,3)", "key", "amount"))));
    }

    @Test void flinkReusesAuthorizedBoundedCatalogAndWritesNativeProjectionAndAppendSql() throws Exception {
        var sourceTable = new EtlNativeSqlCompiler.Table(List.of("default_catalog", "hive_native_components", "source"), table("source").columns(), ProductTypeEnum.HIVE, "hive_native_components", "source");
        var targetTable = new EtlNativeSqlCompiler.Table(List.of("default_catalog", "hive_native_components", "target"), table("target").columns(), ProductTypeEnum.HIVE, "hive_native_components", "target");
        var source = EtlNativeHiveCompiler.compile(FLINK, false, "flink-hive-source", "read-hive", read(), sourceTable);
        var target = EtlNativeHiveCompiler.compile(FLINK, false, "flink-hive-target", "write-hive", write(), targetTable);
        var edge = new EtlEdgeDef(); edge.setSourceNo("read"); edge.setTargetNo("write");
        String sql = source.initializationSql() + EtlNativeSqlCompiler.compile(FLINK, false,
                List.of(node("read", "read-db", source.nodeConfig()), node("write", "insert-db", target.nodeConfig())), List.of(edge),
                (database, name, schema) -> name.equals(source.table().physicalTable()) ? source.table() : target.table());
        assertTrue(sql.contains("INSERT INTO `default_catalog`.`hive_native_components`.`target` (`id`, `name`, `amount`)"));
        assertTrue(sql.contains("WHERE id >= 2 AND name <> 'skip'"));
        assertFalse(source.table().continuousSource());
        Path fixture = Path.of("target/native-hive-sql/hive-flink.sql"); Files.createDirectories(fixture.getParent()); Files.writeString(fixture, sql);
        assertThrows(IllegalArgumentException.class, () -> EtlNativeHiveCompiler.compile(FLINK, false, "n", "read-hive", read(),
                new EtlNativeSqlCompiler.Table(sourceTable.path(), sourceTable.columns(), ProductTypeEnum.HIVE, sourceTable.schema(), "source", true)));
    }
    @Test void consumesFieldsAliasesAndBoundedFiltersAndWritesExplicitAppendMappings() throws Exception {
        var source = EtlNativeHiveCompiler.compile(SPARK, false, "hive-source", "read-hive", read(), table("source"));
        var target = EtlNativeHiveCompiler.compile(SPARK, false, "hive-target", "write-hive", write(), table("target"));
        assertTrue(source.initializationSql().contains("`name` AS `label`"));
        assertTrue(source.initializationSql().contains("WHERE id >= 2 AND name <> 'skip'"));
        assertEquals(List.of("id", "label", "amount"), new ArrayList<>(source.table().columns().keySet()));
        assertTrue(target.output()); assertFalse(source.output());
        assertEquals("INSERT", target.nodeConfig().get("writeMode"));
        var input = node("read", "read-db", source.nodeConfig()); var output = node("write", "insert-db", target.nodeConfig());
        var edge = new EtlEdgeDef(); edge.setSourceNo("read"); edge.setTargetNo("write");
        String sql = source.initializationSql() + EtlNativeSqlCompiler.compile(SPARK, false, List.of(input, output), List.of(edge),
                (database, name, schema) -> name.equals(source.table().physicalTable()) ? source.table() : target.table());
        assertTrue(sql.contains("INSERT INTO `spark_catalog`.`hive_native_components`.`target` (`id`, `name`, `amount`)"));
        assertTrue(sql.contains("SELECT `id`, `label`, `amount` FROM"));
        Path fixture = Path.of("target/native-hive-sql/hive.sql"); Files.createDirectories(fixture.getParent()); Files.writeString(fixture, sql);
    }

    @Test void rejectsNonHiveCatalogsUnknownFieldsPredicateInjectionAndUnsupportedExecutionParameters() {
        var nonHive = new EtlNativeSqlCompiler.Table(table("source").path(), table("source").columns(), ProductTypeEnum.POSTGRESQL, "hive_native_components", "source");
        assertThrows(IllegalArgumentException.class, () -> EtlNativeHiveCompiler.compile(SPARK, false, "n", "read-hive", read(), nonHive));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeHiveCompiler.compile(FLINK, true, "n", "read-hive", read(), table("source")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeHiveCompiler.compile(SPARK, true, "n", "read-hive", read(), table("source")));
        for (var option : List.of(Map.<String, Object>of("whereCondition", "id > 0; DROP TABLE target"), Map.<String, Object>of("whereCondition", "unknown > 1"),
                Map.<String, Object>of("whereCondition", "EXISTS(SELECT 1 FROM other)"), Map.<String, Object>of("readMode", "ID_INCREMENT"),
                Map.<String, Object>of("ifSql", 1), Map.<String, Object>of("preSql", "DELETE FROM target"), Map.<String, Object>of("batchSize", 1000))) {
            var config = read(); config.putAll(option);
            assertThrows(IllegalArgumentException.class, () -> EtlNativeHiveCompiler.compile(SPARK, false, "n", "read-hive", config, table("source")));
        }
        var fields = read(); fields.put("columns", List.of(Map.of("name", "amount", "type", "DECIMAL", "length", 20, "precision", 4)));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeHiveCompiler.compile(SPARK, false, "n", "read-hive", fields, table("source")));
        fields.put("columns", List.of(Map.of("name", "id", "type", "INTEGER")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeHiveCompiler.compile(SPARK, false, "n", "read-hive", fields, table("source")));
    }

    @Test void outputCannotSilentlyTruncateUpdateRunHooksOrContradictTheFieldMappings() {
        for (var option : List.of(Map.<String, Object>of("writeModeType", 1), Map.<String, Object>of("writeMode", "UPDATE"),
                Map.<String, Object>of("primaryKeys", "id"), Map.<String, Object>of("targetTruncate", true),
                Map.<String, Object>of("postSql", "DROP TABLE target"), Map.<String, Object>of("whereCondition", "id > 0"),
                Map.<String, Object>of("fieldMappings", List.of(Map.of("source", "wrong", "target", "id"))))) {
            var config = write(); config.putAll(option);
            assertThrows(IllegalArgumentException.class, () -> EtlNativeHiveCompiler.compile(SPARK, false, "n", "write-hive", config, table("target")));
        }
    }
    @Test void actualUiDefaultsKeepUnicodeOutputMappingAndRejectQuotedNameInjection() throws Exception {
        var fixtures = EtlCompilerUiFixtures.configs();
        var sourceTable = new EtlNativeSqlCompiler.Table(table("source_table").path(), Map.of("id", "BIGINT", "name", "STRING"), ProductTypeEnum.HIVE, "hive_native_components", "source_table");
        var targetTable = new EtlNativeSqlCompiler.Table(table("target_table").path(), Map.of("id", "BIGINT", "标签", "STRING"), ProductTypeEnum.HIVE, "hive_native_components", "target_table");
        var source = EtlNativeHiveCompiler.compile(SPARK, false, "ui-source", "read-hive", fixtures.getJSONObject("read-hive"), sourceTable);
        var target = EtlNativeHiveCompiler.compile(SPARK, false, "ui-target", "write-hive", fixtures.getJSONObject("write-hive"), targetTable);
        assertEquals(fixtures.getJSONObject("write-hive").getJSONArray("fieldMappings").toList(Map.class), target.nodeConfig().get("fieldMappings"));
        var edge = new EtlEdgeDef(); edge.setSourceNo("read"); edge.setTargetNo("write");
        String sql = source.initializationSql() + EtlNativeSqlCompiler.compile(SPARK, false,
                List.of(node("read", "read-db", source.nodeConfig()), node("write", "insert-db", target.nodeConfig())), List.of(edge),
                (database, name, schema) -> name.equals(source.table().physicalTable()) ? source.table() : target.table());
        assertTrue(sql.contains("(`id`, `标签`)")); assertTrue(sql.contains("SELECT `id`, `name` FROM"));
        var malicious = fixtures.getJSONObject("write-hive"); malicious.set("columns", List.of(Map.of("name", "标签` FROM other --", "type", "STRING")));
        assertThrows(IllegalArgumentException.class, () -> EtlNativeHiveCompiler.compile(SPARK, false, "bad", "write-hive", malicious, targetTable));
        var unicodeRead = fixtures.getJSONObject("read-hive"); unicodeRead.set("tableName", "target_table"); unicodeRead.set("columns", List.of(Map.of("name", "标签", "alias", "名称", "type", "STRING")));
        assertTrue(EtlNativeHiveCompiler.compile(SPARK, false, "unicode", "read-hive", unicodeRead, targetTable).initializationSql().contains("`标签` AS `名称`"));
    }
    private static EtlNodeDef node(String no, String code, Map<String, Object> config) {
        var node = new EtlNodeDef(); node.setNo(no); node.setName(no); node.setToolCode(code); node.setNodeJson(JSONUtil.toJsonStr(config)); return node;
    }
}

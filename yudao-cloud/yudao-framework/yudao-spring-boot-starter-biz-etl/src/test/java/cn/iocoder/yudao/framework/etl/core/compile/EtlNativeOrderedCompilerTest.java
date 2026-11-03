package cn.iocoder.yudao.framework.etl.core.compile;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class EtlNativeOrderedCompilerTest {
    @Test void sequenceRequiresExplicitUniqueOrderAndRejectsStreamingAndLossyNumbers() {
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            assertThrows(IllegalArgumentException.class, () -> compile(engine, false, sequence(Map.of())));
            assertThrows(IllegalArgumentException.class, () -> compile(engine, false, sequence(Map.of("orderBy", List.of(Map.of("field", "gid", "order", "ASC")), "uniqueFields", List.of("ord")))));
            assertThrows(IllegalArgumentException.class, () -> compile(engine, false, sequence(Map.of("start", 9_007_199_254_740_992d, "orderBy", order("ord"), "uniqueFields", List.of("ord")))));
        }
        assertThrows(IllegalArgumentException.class, () -> compile(EtlNativeSqlCompiler.Engine.FLINK, true, sequence(Map.of("orderBy", order("ord"), "uniqueFields", List.of("ord")))));
    }

    @Test void perFieldCaseInsensitiveDedupKeepsOriginalValuesAndLegacyDefaults() {
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            var config = new LinkedHashMap<String, Object>(Map.of("enableDeduplication", true, "deduplicationStrategy", "KEY_FIELDS", "keyFields", List.of("gid", "key"), "ignoreCaseFields", List.of("gid"), "orderBy", order("ord"), "uniqueFields", List.of("ord")));
            String sql = compile(engine, false, node("dedup", "data-clean", config));
            assertTrue(sql.contains("PARTITION BY LOWER(`gid`), `key`"), sql);
            assertFalse(sql.contains("PARTITION BY LOWER(`gid`), LOWER(`key`)"), sql);
            config.remove("ignoreCaseFields"); config.put("ignoreCase", true);
            assertTrue(compile(engine, false, node("dedup", "data-clean", config)).contains("PARTITION BY LOWER(`gid`), LOWER(`key`)"));
            for (var invalid : List.of(List.of("missing"), List.of("ord"), List.of("gid", "gid"))) {
                config.put("ignoreCaseFields", invalid);
                assertThrows(IllegalArgumentException.class, () -> compile(engine, false, node("dedup", "data-clean", config)));
            }
        }
    }

    @Test void pivotNeedsOrderAndUsesRankedLastValueInsteadOfArbitraryValueAggregation() {
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            var config = pivot(); config.remove("orderBy");
            assertThrows(IllegalArgumentException.class, () -> compile(engine, false, node("pivot", "row-column", config)));
            String sql = compile(engine, false, node("pivot", "row-column", pivot()));
            assertTrue(sql.contains("ROW_NUMBER() OVER (PARTITION BY `gid`, `key` ORDER BY `ord` DESC NULLS FIRST)"), sql);
            assertTrue(sql.contains("COUNT(*) OVER (PARTITION BY `ord`)"), sql);
            assertTrue(sql.contains("HAVING COUNT(*) > 0"), sql);
        }
    }

    @Test void writesOrderedRuntimeFixturesWithLargeLosslessIdsAndDuplicateRejection() throws Exception {
        for (var engine : EtlNativeSqlCompiler.Engine.values()) for (boolean duplicates : List.of(false, true)) {
            String sql = compile(engine, false, node("pivot", "row-column", pivot()), sequence(Map.of("start", "9007199254740993", "step", "7", "max", "0", "orderBy", order("gid"), "uniqueFields", List.of("gid"))));
            String source = "CREATE TEMPORARY VIEW source AS SELECT * FROM (VALUES ('g1','A',CAST(1.1 AS DECIMAL(20,4)),1),('g1','A',CAST(2.2 AS DECIMAL(20,4))," + (duplicates ? "1" : "2") + "),('g1','B',CAST(3.3 AS DECIMAL(20,4)),3),('g2','A',CAST(NULL AS DECIMAL(20,4)),4)) AS t(gid, `key`, `value`, ord);\n";
            String suffix = engine.name().toLowerCase(Locale.ROOT) + (duplicates ? "-duplicate" : "");
            String columns = "gid STRING, a DECIMAL(20,4), b DECIMAL(20,4), sequence_id BIGINT";
            String sink = engine == EtlNativeSqlCompiler.Engine.FLINK ? "SET 'execution.runtime-mode'='batch';\nSET 'table.dml-sync'='true';\nCREATE TEMPORARY TABLE ordered_sink (" + columns + ") WITH ('connector'='filesystem','path'='file:///tmp/datahub-native-ordered-" + suffix + "-result','format'='json');\n"
                    : "CREATE TABLE ordered_sink (" + columns + ") USING json OPTIONS (path '/tmp/datahub-native-ordered-" + suffix + "-result');\n";
            Path file = Path.of("target", "native-sql", "ordered-" + suffix + ".sql"); Files.createDirectories(file.getParent()); Files.writeString(file, source + sink + sql);
        }
    }

    private static Map<String, Object> pivot() { return new LinkedHashMap<>(Map.of("mode", "ROW2COL", "groupFields", List.of("gid"), "keyField", "key", "valueField", "value", "mappings", List.of(Map.of("keyValue", "A", "fieldName", "a"), Map.of("keyValue", "B", "fieldName", "b")), "orderBy", order("ord"), "uniqueFields", List.of("ord"))); }
    private static List<Map<String, String>> order(String field) { return List.of(Map.of("field", field, "order", "ASC")); }
    private static EtlNodeDef sequence(Map<String, Object> values) { var config = new LinkedHashMap<String, Object>(); config.put("mode", "SEQUENCE"); config.put("targetField", "sequence_id"); config.putAll(values); return node("sequence", "id-generator", config); }
    private static String compile(EtlNativeSqlCompiler.Engine engine, boolean streaming, EtlNodeDef... transforms) {
        var nodes = new ArrayList<EtlNodeDef>(); var edges = new ArrayList<EtlEdgeDef>();
        nodes.add(node("source", "read-db", Map.of("databaseId", 1, "tableName", "source"))); String previous = "source";
        for (var transform : transforms) { nodes.add(transform); edges.add(edge(previous, transform.getNo())); previous = transform.getNo(); }
        nodes.add(node("sink", "insert-db", Map.of("databaseId", 2, "tableName", "ordered_sink"))); edges.add(edge(previous, "sink"));
        return EtlNativeSqlCompiler.compile(engine, streaming, nodes, edges, (id, table, schema) -> {
            var fields = new LinkedHashMap<String, String>(); fields.put("gid", "STRING"); fields.put("key", "STRING"); fields.put("value", "DECIMAL(20,4)"); fields.put("ord", "INT");
            if (id == 2L) { fields.put("a", "DECIMAL(20,4)"); fields.put("b", "DECIMAL(20,4)"); fields.put("sequence_id", "BIGINT"); }
            return new EtlNativeSqlCompiler.Table(List.of(table), fields, ProductTypeEnum.POSTGRESQL, "public", table, true);
        });
    }
    private static EtlNodeDef node(String id, String code, Map<String, Object> config) { var node = new EtlNodeDef(); node.setNo(id); node.setName(id); node.setToolCode(code); node.setNodeJson(JSONUtil.toJsonStr(config)); return node; }
    private static EtlEdgeDef edge(String source, String target) { var edge = new EtlEdgeDef(); edge.setSourceNo(source); edge.setTargetNo(target); return edge; }
}

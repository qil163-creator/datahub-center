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

class EtlNativeSqlGraphCompilerTest {
    @Test void branchPortsKeepOrderedFirstMatchAndFlinkOutputsShareOneJob() throws Exception {
        var nodes = List.of(read("source", "source"), node("branch", "data-condition", Map.of("branches", List.of(
                Map.of("id", "high", "type", "IF", "fieldName", "id", "operator", "GT", "value", "1"),
                Map.of("id", "low", "type", "ELSE")))), write("high", "target_high"), write("low", "target_low"));
        var edges = List.of(edge("source", "branch", "main"), edge("branch", "high", "high"), edge("branch", "low", "low"));
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            String sql = compile(engine, false, nodes, edges);
            assertTrue(sql.contains("CASE WHEN (`id` > 1) THEN 0 ELSE 1 END = 0"), sql);
            assertTrue(sql.contains("CASE WHEN (`id` > 1) THEN 0 ELSE 1 END = 1"), sql);
            assertEquals(engine == EtlNativeSqlCompiler.Engine.FLINK, sql.startsWith("EXECUTE STATEMENT SET"));
            artifact("branch", engine, sql);
        }
        String stream = compile(EtlNativeSqlCompiler.Engine.FLINK, true, nodes, edges);
        assertTrue(stream.startsWith("EXECUTE STATEMENT SET")); assertTrue(stream.endsWith("END;"));
        assertThrows(IllegalArgumentException.class, () -> compile(EtlNativeSqlCompiler.Engine.FLINK, false, nodes,
                List.of(edge("source", "branch", "main"), edge("branch", "high", "missing"), edge("branch", "low", "low"))));
    }

    @Test void conditionGroupsAndIndependentBranchFieldsCompileWithoutChangingLegacyPorts() {
        var branch = node("branch", "data-condition", Map.of("branches", List.of(
                Map.of("id", "yes", "type", "IF", "conditionRelation", "AND", "conditions", List.of(Map.of("fieldName", "id", "operator", "GT", "value", "1"), Map.of("fieldName", "name", "operator", "NOT_NULL")), "fields", List.of("id")),
                Map.of("id", "no", "type", "ELSE", "fields", List.of("name")))));
        var nodes = List.of(read("source", "source"), branch, write("yes", "target_high"), write("no", "target_low"));
        var edges = List.of(edge("source", "branch", "main"), edge("branch", "yes", "yes"), edge("branch", "no", "no"));
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            String sql = compile(engine, false, nodes, edges);
            assertTrue(sql.contains("SELECT `id` FROM (SELECT"), sql); assertTrue(sql.contains("SELECT `name` FROM (SELECT"), sql);
            assertTrue(sql.contains("AND (`name` IS NOT NULL"), sql);
        }
        var invalid = node("branch", "data-condition", Map.of("branches", List.of(Map.of("id", "yes", "type", "ELSE", "fields", List.of()))));
        assertThrows(IllegalArgumentException.class, () -> compile(EtlNativeSqlCompiler.Engine.SPARK, false,
                List.of(read("source", "source"), invalid, write("yes", "target_high")), List.of(edge("source", "branch", "main"), edge("branch", "yes", "yes"))));
    }

    @Test void joinHonorsExplicitSourcesAllConditionsAndIndependentOutputProjections() {
        var config = Map.<String, Object>of("joinType", "LEFT", "conditionRelation", "AND", "leftNodeNo", "right", "rightNodeNo", "left",
                "joinConditions", List.of(Map.of("leftField", "id", "rightField", "id", "operator", "EQ"), Map.of("leftField", "id", "rightField", "id", "operator", "GTE")),
                "leftFields", List.of(Map.of("name", "extra", "alias", "extra")), "rightFields", List.of(Map.of("name", "id", "alias", "id")));
        var nodes = List.of(read("left", "source"), read("right", "right_source"), node("join", "multi-join", config), write("sink", "join_target"));
        var edges = List.of(edge("left", "join", "main"), edge("right", "join", "main"), edge("join", "sink", "main"));
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            String sql = compile(engine, false, nodes, edges);
            assertTrue(sql.contains("dh_left.`extra` AS `extra`"), sql); assertTrue(sql.contains("dh_right.`id` AS `id`"), sql);
            assertTrue(sql.contains("dh_left.`id` >= dh_right.`id`"), sql); assertFalse(sql.contains("COALESCE(dh_left.`name`"), sql);
        }
        var invalid = new LinkedHashMap<>(config); invalid.put("leftNodeNo", "not-connected");
        assertThrows(IllegalArgumentException.class, () -> compile(EtlNativeSqlCompiler.Engine.SPARK, false,
                List.of(nodes.get(0), nodes.get(1), node("join", "multi-join", invalid), nodes.get(3)), edges));
    }

    @Test void cleaningWhereUsesKnownFieldsAndCannotSmuggleAnotherStatementOrUnknownColumn() {
        var edges = List.of(edge("source", "clean", "main"), edge("clean", "sink", "main"));
        String sql = compile(EtlNativeSqlCompiler.Engine.SPARK, false,
                List.of(read("source", "source"), node("clean", "data-clean", Map.of("whereCondition", "id > 1 AND name IS NOT NULL")), write("sink", "target_low")), edges);
        assertTrue(sql.contains("dh_clean_filter WHERE id > 1 AND name IS NOT NULL"), sql);
        for (String where : List.of("missing > 1", "id > 1; DELETE FROM private_table", "EXISTS (SELECT 1 FROM private_table)"))
            assertThrows(IllegalArgumentException.class, () -> compile(EtlNativeSqlCompiler.Engine.SPARK, false,
                    List.of(read("source", "source"), node("clean", "data-clean", Map.of("whereCondition", where)), write("sink", "target_low")), edges));
    }

    @Test void nativeJoinRetainsLeftColumnsUsesNullSafeKeysAndRejectsUnboundedStreamState() throws Exception {
        var nodes = List.of(read("left", "source"), read("right", "right_source"), node("join", "multi-join", Map.of(
                "joinType", "LEFT", "joinConditions", List.of(Map.of("leftField", "id", "rightField", "id")))), write("sink", "join_target"));
        var edges = List.of(edge("left", "join", "main"), edge("right", "join", "main"), edge("join", "sink", "main"));
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            String sql = compile(engine, false, nodes, edges);
            assertTrue(sql.contains("COALESCE(dh_left.`name`, dh_right.`name`) AS `name`"), sql);
            assertTrue(sql.contains("dh_left.`id` IS NULL AND dh_right.`id` IS NULL"), sql);
            assertTrue(sql.contains("dh_right.`extra` AS `extra`"), sql);
            artifact("join", engine, sql);
            var overflowNodes = List.of(read("left", "source"), read("right", "right_source"), node("join", "multi-join", Map.of("joinType", "LEFT", "maxOutputRows", 2,
                    "joinConditions", List.of(Map.of("leftField", "id", "rightField", "id")))), write("sink", "join_target"));
            artifact("join-limit", engine, compile(engine, false, overflowNodes, edges));
        }
        assertThrows(IllegalArgumentException.class, () -> compile(EtlNativeSqlCompiler.Engine.FLINK, true, nodes, edges));
    }

    @Test void nativeJoinSupportsRightJoinAndRejectsUnknownKinds() throws Exception {
        var edges = List.of(edge("left", "join", "main"), edge("right", "join", "main"), edge("join", "sink", "main"));
        for (String kind : List.of("RIGHT", "FULL")) {
            var nodes = List.of(read("left", "source"), read("right", "right_source"), node("join", "multi-join", Map.of(
                    "joinType", kind, "joinConditions", List.of(Map.of("leftField", "id", "rightField", "id")))), write("sink", "join_target"));
            if ("RIGHT".equals(kind)) {
                for (var engine : EtlNativeSqlCompiler.Engine.values())
                    assertTrue(compile(engine, false, nodes, edges).contains(" RIGHT JOIN "));
            } else {
                assertThrows(IllegalArgumentException.class, () -> compile(EtlNativeSqlCompiler.Engine.SPARK, false, nodes, edges));
            }
        }
    }

    @Test void stringNumericLiteralsRemainTextAndSplitMissingValuesRemainNull() throws Exception {
        var nodes = List.of(read("source", "source"), node("split", "field-split", Map.of("sourceField", "name", "delimiter", "|",
                "outputs", List.of(Map.of("name", "first", "trimType", "BOTH"), Map.of("name", "second")), "keepSource", false)),
                write("sink", "split_target"));
        for (var engine : EtlNativeSqlCompiler.Engine.values()) artifact("split", engine,
                compile(engine, false, nodes, List.of(edge("source", "split", "main"), edge("split", "sink", "main"))));
        assertThrows(IllegalArgumentException.class, () -> compile(EtlNativeSqlCompiler.Engine.SPARK, false, List.of(read("source", "source"),
                node("filter", "data-filter", Map.of("filterConditions", List.of(Map.of("fieldName", "name", "operator", "EQUALS", "value", "001")))),
                write("sink", "target_low")), List.of(edge("source", "filter", "main"), edge("filter", "sink", "main"))));
        assertThrows(IllegalArgumentException.class, () -> compile(EtlNativeSqlCompiler.Engine.SPARK, false,
                List.of(read("source", "source"), node("sink", "insert-db", Map.of("databaseId", 2, "tableName", "target_low", "whereCondition", "id=1"))),
                List.of(edge("source", "sink", "main"))));
    }

    @Test void unpivotAndUuidPreserveNullRowsWithoutUnsafeMixedTypeCoercion() throws Exception {
        var nodes = List.of(read("source", "source"), node("copy", "set-field-value", Map.of("rules", List.of(Map.of("sourceField", "name", "targetField", "copy")))),
                node("unpivot", "row-column", Map.of("mode", "COL2ROW", "unpivotFields", List.of("name", "copy"))),
                node("uuid", "id-generator", Map.of("mode", "UUID", "targetField", "guid")), write("sink", "unpivot_target"));
        var edges = List.of(edge("source", "copy", "main"), edge("copy", "unpivot", "main"), edge("unpivot", "uuid", "main"), edge("uuid", "sink", "main"));
        for (var engine : EtlNativeSqlCompiler.Engine.values()) {
            String sql = compile(engine, false, nodes, edges);
            assertTrue(sql.contains("UUID() AS `guid`"), sql);
            assertTrue(sql.contains(engine == EtlNativeSqlCompiler.Engine.FLINK ? "CROSS JOIN UNNEST" : "LATERAL VIEW EXPLODE"), sql);
            artifact("unpivot", engine, sql);
        }
        assertThrows(IllegalArgumentException.class, () -> compile(EtlNativeSqlCompiler.Engine.SPARK, false,
                List.of(read("source", "source"), node("unpivot", "row-column", Map.of("unpivotFields", List.of("id", "name"))), write("sink", "unpivot_target")),
                List.of(edge("source", "unpivot", "main"), edge("unpivot", "sink", "main"))));
    }

    @Test void errorOnUnmatchedCannotSilentlyDropRowsAndEveryBranchNeedsAnOutput() {
        var branch = node("branch", "data-condition", Map.of("unmatchedPolicy", "ERROR", "branches", List.of(
                Map.of("id", "first", "fieldName", "id", "operator", "EQ", "value", "1"))));
        assertThrows(IllegalArgumentException.class, () -> compile(EtlNativeSqlCompiler.Engine.SPARK, false,
                List.of(read("source", "source"), branch, write("sink", "target_low")), List.of(edge("source", "branch", "main"), edge("branch", "sink", "first"))));
    }

    private static void artifact(String name, EtlNativeSqlCompiler.Engine engine, String sql) throws Exception {
        Path path = Path.of("target", "native-sql", name + "-" + engine.name().toLowerCase(Locale.ROOT) + ".sql");
        Files.createDirectories(path.getParent()); Files.writeString(path, sql);
    }
    private static String compile(EtlNativeSqlCompiler.Engine engine, boolean stream, List<EtlNodeDef> nodes, List<EtlEdgeDef> edges) {
        return EtlNativeSqlCompiler.compile(engine, stream, nodes, edges, (id, name, schema) -> {
            var columns = new LinkedHashMap<String, String>(); columns.put("id", "BIGINT");
            if (name.equals("unpivot_target")) { columns.put("key", "VARCHAR"); columns.put("value", "VARCHAR"); columns.put("guid", "VARCHAR"); }
            else if (name.equals("split_target")) { columns.put("first", "VARCHAR"); columns.put("second", "VARCHAR"); }
            else columns.put("name", "VARCHAR");
            if (name.equals("right_source") || name.equals("join_target")) columns.put("extra", "VARCHAR");
            return new EtlNativeSqlCompiler.Table(List.of(name), columns, ProductTypeEnum.POSTGRESQL, "public", name, stream);
        });
    }
    private static EtlNodeDef read(String id, String table) { return node(id, "read-db", Map.of("databaseId", 1, "tableName", table)); }
    private static EtlNodeDef write(String id, String table) { return node(id, "insert-db", Map.of("databaseId", 2, "tableName", table)); }
    private static EtlNodeDef node(String id, String code, Map<String, Object> config) {
        var node = new EtlNodeDef(); node.setNo(id); node.setName(id); node.setToolCode(code); node.setNodeJson(JSONUtil.toJsonStr(config)); return node;
    }
    private static EtlEdgeDef edge(String from, String to, String port) {
        var edge = new EtlEdgeDef(); edge.setSourceNo(from); edge.setTargetNo(to); edge.setOutputPort(port); return edge;
    }
}

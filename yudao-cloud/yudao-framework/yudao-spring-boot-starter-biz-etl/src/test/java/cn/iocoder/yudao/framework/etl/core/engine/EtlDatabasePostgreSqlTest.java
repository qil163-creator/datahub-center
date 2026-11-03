package cn.iocoder.yudao.framework.etl.core.engine;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import cn.iocoder.yudao.framework.etl.core.executor.AbstractEtlNodeExecutor;
import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.model.EtlDag;
import cn.iocoder.yudao.framework.etl.core.model.EtlEdgeDef;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class EtlDatabasePostgreSqlTest {
    private Connection control;
    private String schema;
    private EtlConnectionInfo info;
    @BeforeEach void setup() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        String user = System.getProperty("datahub.test.postgres.user", "dataforge_test");
        control = DriverManager.getConnection(url, user, ""); schema = "etl_db_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
        info = new EtlConnectionInfo(); info.setDatabaseId(1L); info.setName("local fixture");
        info.setJdbcUrl(url); info.setUsername(user); info.setPassword(""); info.setProductType(ProductTypeEnum.POSTGRESQL); info.setSchema(schema);
    }
    @AfterEach void cleanup() throws Exception {
        if (control != null) { try { sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); } finally { control.close(); } }
    }

    @ParameterizedTest @ValueSource(strings = {"INSERT", "COPY"})
    void readDbToInsertDbPreservesPrecisionAndPublishesActualBoundedBatchEvidence(String mode) throws Exception {
        sql("CREATE TABLE " + schema + ".source_rows(id integer PRIMARY KEY, amount numeric, note text)");
        sql("INSERT INTO " + schema + ".source_rows VALUES (1,12345678901234567890.0123456789,'中文'),(2,1.0001,NULL),(3,0,'')");
        sql("CREATE TABLE " + schema + ".target_rows(LIKE " + schema + ".source_rows INCLUDING ALL)");
        var source = node("source", "read-db", Map.of("databaseId", 1, "ifSql", 1,
                "sql", "SELECT * FROM " + schema + ".source_rows ORDER BY id", "fetchSize", 1));
        var target = target(1, mode);
        try (var engine = new EtlPipelineEngine(new EtlExecutorRegistry())) {
            var result = engine.execute(dag(source, target), options());
            assertTrue(result.isSuccess(), result.getFirstError());
            var progress = result.getDatabaseProgress().get("target");
            assertEquals(3, progress.acknowledgedRows()); assertEquals(2, progress.acknowledgedBatches());
            assertEquals(0, progress.unknownRows()); assertTrue(progress.terminated());
            if (control.getMetaData().getDatabaseMajorVersion() == 16) assertEquals(2, result.getDatabaseCommits().get("target").witnessedBatches());
        }
        try (var statement = control.createStatement(); var rows = statement.executeQuery("SELECT amount::text,note FROM " + schema + ".target_rows ORDER BY id")) {
            assertTrue(rows.next()); assertEquals("12345678901234567890.0123456789", rows.getString(1)); assertEquals("中文", rows.getString(2));
            assertTrue(rows.next()); assertNull(rows.getString(2)); assertTrue(rows.next()); assertEquals("", rows.getString(2));
        }
    }

    @Test void nativeUpsertKeepsRepeatedKeyAndExistingRowWithoutAnExistenceQuery() throws Exception {
        sql("CREATE TABLE " + schema + ".target_rows(id integer PRIMARY KEY, note text)");
        sql("INSERT INTO " + schema + ".target_rows VALUES(1,'before')");
        var result = withRows(List.of(Map.of("id", 1, "note", "first"), Map.of("id", 1, "note", "last"), Map.of("id", 2, "note", "new")), target(2, "INSERT"));
        assertTrue(result.isSuccess(), result.getFirstError()); assertEquals(2, count());
        assertEquals(3, result.getDatabaseProgress().get("target").acknowledgedRows());
        try (var statement = control.createStatement(); var rows = statement.executeQuery("SELECT note FROM " + schema + ".target_rows WHERE id=1")) {
            assertTrue(rows.next()); assertEquals("last", rows.getString(1));
        }
    }

    @Test void changedFieldSetFailsBeforePendingBatchCanWriteMissingValuesAsNull() throws Exception {
        sql("CREATE TABLE " + schema + ".target_rows(id integer PRIMARY KEY, note text)");
        var result = withRows(List.of(Map.of("id", 1, "note", "first"), Map.of("id", 2)), target(1, "INSERT"));
        assertFalse(result.isSuccess()); assertEquals(0, count());
        assertEquals(1, result.getDatabaseProgress().get("target").rejectedRows());
        assertEquals(0, result.getDatabaseProgress().get("target").unknownRows());
    }

    @Test void truncateIsRejectedForUpsertBeforeExistingDataChanges() throws Exception {
        sql("CREATE TABLE " + schema + ".target_rows(id integer PRIMARY KEY, note text)");
        sql("INSERT INTO " + schema + ".target_rows VALUES(99,'untouched')");
        var target = target(2, "INSERT");
        target.setNodeJson(JSONUtil.toJsonStr(Map.of("databaseId", 1, "tableName", "target_rows", "dataSyncType", 2,
                "sourcePrimaryKeys", "id", "targetTruncate", true)));
        var result = withRows(List.of(Map.of("id", 1, "note", "new")), target);
        assertFalse(result.isSuccess()); assertEquals(1, count());
    }

    private cn.iocoder.yudao.framework.etl.core.runtime.EtlRunResult withRows(List<Map<String, Object>> rows, EtlNodeDef target) {
        var registry = new EtlExecutorRegistry();
        registry.register("fixture", (n, c) -> new AbstractEtlNodeExecutor(n, c) {
            @Override protected void execute() throws Exception { for (var row : rows) pushRow(new LinkedHashMap<>(row)); }
        });
        try (var engine = new EtlPipelineEngine(registry)) { return engine.execute(dag(node("source", "fixture", Map.of()), target), options()); }
    }
    private EtlRunOptions options() {
        return EtlRunOptions.builder().connectionResolver(id -> info)
                .databaseBatchLimits(new BatchLimits(2, 1024, 512, 10, 10_000)).build();
    }
    private EtlNodeDef target(int type, String mode) {
        return node("target", "insert-db", Map.of("databaseId", 1, "tableName", "target_rows", "dataSyncType", type,
                "sourcePrimaryKeys", "id", "writeMode", mode, "batchSize", 1000));
    }
    private static EtlNodeDef node(String no, String code, Map<String, Object> config) {
        var node = new EtlNodeDef(); node.setNo(no); node.setName(no); node.setToolCode(code);
        node.setToolType(no.equals("source") ? EtlNodeDef.TOOL_TYPE_INPUT : EtlNodeDef.TOOL_TYPE_OUTPUT);
        node.setNodeJson(JSONUtil.toJsonStr(config)); return node;
    }
    private static EtlDag dag(EtlNodeDef source, EtlNodeDef target) {
        var edge = new EtlEdgeDef(); edge.setSourceNo(source.getNo()); edge.setTargetNo(target.getNo()); edge.setEdgeType("DATA");
        return EtlDag.build(List.of(source, target), List.of(edge));
    }
    private void sql(String sql) throws Exception { try (var statement = control.createStatement()) { statement.execute(sql); } }
    private long count() throws Exception { try (var statement = control.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM " + schema + ".target_rows")) { assertTrue(rows.next()); return rows.getLong(1); } }
}

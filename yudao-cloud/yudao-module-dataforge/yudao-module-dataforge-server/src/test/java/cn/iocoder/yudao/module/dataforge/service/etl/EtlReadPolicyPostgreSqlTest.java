package cn.iocoder.yudao.module.dataforge.service.etl;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import cn.iocoder.yudao.framework.etl.core.connection.EtlReadPolicyPort;
import cn.iocoder.yudao.framework.etl.core.engine.*;
import cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry;
import cn.iocoder.yudao.framework.etl.core.model.*;
import cn.iocoder.yudao.framework.etl.core.runtime.*;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import cn.iocoder.yudao.module.dataforge.service.security.DatabaseQuerySupport;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers
class EtlReadPolicyPostgreSqlTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new cn.iocoder.yudao.module.dataforge.support.DataforgePostgreSqlContainer();
    private Connection control;
    private String schema;
    private EtlConnectionInfo info;
    private final DatabaseQuerySupport queries = mock(DatabaseQuerySupport.class);
    private final EtlNodeAccessService access = new EtlNodeAccessService();
    private final List<List<Map<String, Object>>> batches = new CopyOnWriteArrayList<>();
    private final AtomicLong read = new AtomicLong(), written = new AtomicLong();

    @BeforeEach void setup() throws Exception {
        control = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        schema = "etl_policy_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
        sql("CREATE TABLE " + schema + ".source_rows(id integer PRIMARY KEY, note text)");
        sql("CREATE TABLE " + schema + ".target_rows(LIKE " + schema + ".source_rows INCLUDING ALL)");
        info = new EtlConnectionInfo(); info.setDatabaseId(1L); info.setName("isolated policy fixture");
        info.setJdbcUrl(POSTGRES.getJdbcUrl()); info.setUsername(POSTGRES.getUsername()); info.setPassword(POSTGRES.getPassword());
        info.setProductType(ProductTypeEnum.POSTGRESQL); info.setSchema(schema);
        ReflectionTestUtils.setField(access, "executionAccessService", mock(ExecutionAccessService.class));
        ReflectionTestUtils.setField(access, "queries", queries);
        when(queries.normalizeSelect(eq(1L), anyString())).thenAnswer(inv -> inv.getArgument(1));
    }
    @AfterEach void cleanup() throws Exception {
        if (control != null) { try { sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); } finally { control.close(); } }
    }

    @Test void sourcePreservesEveryOriginalRowAndFieldAcrossBatches() throws Exception {
        sql("INSERT INTO " + schema + ".source_rows SELECT i, 'private-fixture' FROM generate_series(1,1002) AS i");
        var result = execute(4 * 1024 * 1024, 10_000);
        assertTrue(result.isSuccess(), result.getFirstError());
        assertEquals(1002, read.get()); assertEquals(1002, written.get());
        assertEquals(3, batches.size()); assertTrue(batches.stream().allMatch(rows -> rows.size() <= 500));
        assertEquals(1002, count("true")); assertEquals(0, count("note <> 'private-fixture'"));
        verify(queries).normalizeSelect(eq(1L), contains("source_rows"));
    }

    @Test void rawReadBatchesRespectByteBudget() throws Exception {
        sql("INSERT INTO " + schema + ".source_rows SELECT i, repeat('x',30) FROM generate_series(1,20) AS i");
        var result = execute(2048, 100);
        assertTrue(result.isSuccess(), result.getFirstError());
        assertEquals(20, count("true")); assertTrue(batches.size() > 1 && batches.size() < 20);
        assertTrue(batches.stream().allMatch(rows -> rows.stream().mapToLong(EtlRunContext::estimateRowBytes).sum() <= 2048));
    }

    @Test void invalidQueryFailsBeforeAnyTargetWrites() throws Exception {
        sql("INSERT INTO " + schema + ".source_rows VALUES (1,'private-fixture')");
        when(queries.normalizeSelect(eq(1L), anyString())).thenThrow(new IllegalArgumentException("invalid query"));
        assertFalse(execute(2048, 100).isSuccess()); assertEquals(0, count("true"));
    }

    @Test void sourceScanBudgetStillBoundsRawReads() throws Exception {
        sql("INSERT INTO " + schema + ".source_rows SELECT i, 'private-fixture' FROM generate_series(1,11) AS i");
        assertFalse(execute(2048, 10).isSuccess());
        assertTrue(count("true") <= 10);
    }

    @Test void explicitSchemaAndQuotedRelationReadTheSameTableAsMetadata() throws Exception {
        sql("CREATE TABLE " + schema + ".\"Source.Rows\"(id integer, note text)");
        sql("INSERT INTO " + schema + ".\"Source.Rows\" VALUES (7,'bound-schema')");
        var source = node("source", "read-db", 1, Map.of("databaseId", 1, "ifSql", 0,
                "schemaName", schema, "tableName", "\"" + schema + "\".\"Source.Rows\""));
        var sourceInfo = new EtlConnectionInfo(); sourceInfo.setDatabaseId(1L); sourceInfo.setName(info.getName());
        sourceInfo.setJdbcUrl(info.getJdbcUrl()); sourceInfo.setUsername(info.getUsername()); sourceInfo.setPassword(info.getPassword());
        sourceInfo.setProductType(info.getProductType()); sourceInfo.setSchema("public");
        var target = node("target", "insert-db", 3, Map.of("databaseId", 2, "tableName", "target_rows"));
        var edge = new EtlEdgeDef(); edge.setSourceNo("source"); edge.setTargetNo("target");
        var dag = EtlDag.build(List.of(source, target), List.of(edge));
        var options = EtlRunOptions.builder().connectionResolver(id -> id == 1L ? sourceInfo : info)
                .readPolicyPort(trackingReadPort()).build();
        try (var engine = new EtlPipelineEngine(new EtlExecutorRegistry())) {
            var result = engine.execute(dag, options); assertTrue(result.isSuccess(), result.getFirstError());
        }
        assertEquals(1, count("id=7 AND note='bound-schema'"));
        verify(queries).normalizeSelect(1L, "SELECT * FROM \"" + schema + "\".\"Source.Rows\"");
        try (var runtime = cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime.standalone()) {
            var resolved = new cn.iocoder.yudao.framework.etl.core.schema.EtlSchemaService(runtime)
                    .resolve(dag, id -> id == 1L ? sourceInfo : info, null);
            assertTrue(resolved.getErrors().isEmpty(), resolved.getErrors().toString());
            assertEquals(List.of("id", "note"), resolved.getNodes().get("source").getOutputFields().stream().map(
                    cn.iocoder.yudao.framework.etl.core.schema.EtlFieldMeta::getName).toList());
        }
    }

    private EtlReadPolicyPort trackingReadPort() {
        var delegate = access.createReadPort(7L);
        return new EtlReadPolicyPort() {
            public String rewrite(String node, Long database, String sql) {
                return delegate.rewrite(node, database, sql);
            }
            public List<Map<String, Object>> filter(String node, Long database, String sql, List<Map<String, Object>> rows) {
                batches.add(rows);
                return delegate.filter(node, database, sql, rows);
            }
        };
    }

    private EtlRunResult execute(long maxBytes, long maxRows) {
        var source = node("source", "read-db", 1, Map.of("databaseId", 1, "ifSql", 1, "sql", "SELECT * FROM " + schema + ".source_rows ORDER BY id"));
        var target = node("target", "insert-db", 3, Map.of("databaseId", 1, "tableName", "target_rows", "batchSize", 100));
        var edge = new EtlEdgeDef(); edge.setSourceNo("source"); edge.setTargetNo("target");
        var options = EtlRunOptions.builder().connectionResolver(id -> info).readPolicyPort(trackingReadPort())
                .databaseBatchLimits(new BatchLimits(1000, maxBytes, Math.min(maxBytes, 1024), 10_000, 10_000))
                .maxRows(maxRows).listener(new EtlRunListener() {
                    public void onNodeSuccess(String no, long input, long output) { if ("source".equals(no)) { read.set(input); written.set(output); } }
                }).build();
        try (var engine = new EtlPipelineEngine(new EtlExecutorRegistry())) { return engine.execute(EtlDag.build(List.of(source, target), List.of(edge)), options); }
    }
    private static EtlNodeDef node(String id, String code, int type, Map<String, Object> config) {
        var node = new EtlNodeDef(); node.setNo(id); node.setName(id); node.setToolCode(code); node.setToolType(type); node.setNodeJson(JSONUtil.toJsonStr(config)); return node;
    }
    private void sql(String statement) throws Exception { try (var command = control.createStatement()) { command.execute(statement); } }
    private long count(String condition) throws Exception { try (var command = control.createStatement(); var rows = command.executeQuery("SELECT count(*) FROM " + schema + ".target_rows WHERE " + condition)) { assertTrue(rows.next()); return rows.getLong(1); } }
}

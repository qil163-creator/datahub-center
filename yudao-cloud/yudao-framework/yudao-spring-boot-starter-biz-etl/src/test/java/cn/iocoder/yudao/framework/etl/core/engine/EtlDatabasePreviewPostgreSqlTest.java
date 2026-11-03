package cn.iocoder.yudao.framework.etl.core.engine;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.etl.core.connection.*;
import cn.iocoder.yudao.framework.etl.core.model.*;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EtlDatabasePreviewPostgreSqlTest {
    static PostgreSQLContainer<?> container;
    static String url, user, password;
    @BeforeAll static void postgres() {
        url = System.getProperty("datahub.test.postgres.url");
        if (url == null || url.isBlank()) {
            container = new PostgreSQLContainer<>("postgres:16-alpine"); container.start();
            url = container.getJdbcUrl(); user = container.getUsername(); password = container.getPassword();
        } else {
            user = System.getProperty("datahub.test.postgres.user");
            password = System.getProperty("datahub.test.postgres.password", "");
        }
    }
    @AfterAll static void stop() { if (container != null) container.stop(); }
    private Connection control;
    private String schema;
    private EtlConnectionInfo connection;
    private DbSwitchRuntime runtime;
    private EtlReadPolicyPort policy;
    private final AtomicInteger resolutions = new AtomicInteger(), filters = new AtomicInteger();

    @BeforeEach void setup() throws Exception {
        control = DriverManager.getConnection(url, user, password);
        schema = "preview_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
        sql("CREATE TABLE " + schema + ".source_rows(id integer PRIMARY KEY, secret text)");
        connection = new EtlConnectionInfo(); connection.setDatabaseId(1L); connection.setSchema(schema);
        connection.setProductType(ProductTypeEnum.POSTGRESQL); connection.setJdbcUrl(url);
        connection.setUsername(user); connection.setPassword(password);
        runtime = DbSwitchRuntime.standalone();
        policy = policy(sql -> sql, rows -> rows);
    }
    @AfterEach void close() throws Exception {
        if (control != null) {
            if (!control.getAutoCommit()) control.rollback();
            control.setAutoCommit(true);
            sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); control.close();
        }
        if (runtime != null) runtime.close();
    }

    @Test void samplesBeforeTransformAndAppliesPermissionsAndMaskingBeforeDownstream() throws Exception {
        seed(1002);
        policy = policy(sql -> sql + " WHERE id <= 500 ORDER BY id", rows -> {
            assertEquals(101, rows.size());
            assertEquals(0, runtime.queries().activeOperations(), "Masking cannot hold a source transaction");
            return rows.stream().filter(row -> ((Number) row.get("id")).intValue() % 2 == 1)
                    .<Map<String, Object>>map(row -> new LinkedHashMap<>(Map.of("id", row.get("id"), "secret", "masked"))).toList();
        });
        var result = preview(List.of(source("source_rows"), node("sorted", "sort-records", 2,
                Map.of("sorts", List.of(Map.of("field", "id", "order", "DESC"))))), List.of(edge()), "sorted", 100);
        assertTrue(result.success(), result.issues().toString()); assertTrue(result.truncated());
        assertEquals(51, result.rows().size()); assertEquals(101, result.rows().getFirst().get("id"));
        assertEquals(51, result.inputRows()); assertEquals(51, result.outputRows());
        assertTrue(result.rows().stream().allMatch(row -> "masked".equals(row.get("secret"))));
        var input = result.inputSamples().getFirst();
        assertEquals("source", input.sourceNodeNo()); assertEquals("main", input.sourceOutputPort());
        assertEquals(51, input.rowCount()); assertEquals(51, result.targetInputRows());
        assertEquals(1, input.rows().getFirst().get("id"));
        assertTrue(input.rows().stream().allMatch(row -> "masked".equals(row.get("secret"))));
        assertFalse(cn.iocoder.yudao.framework.etl.core.executor.support.EtlJsonValues.write(result).contains("private-fixture"));
        assertEquals(1, filters.get()); assertEquals(1002, count());
    }

    @Test void exactBoundaryAndEmptySourceAreNotTruncatedAndBorrowedRuntimeStaysOpen() throws Exception {
        seed(100);
        var exact = preview(List.of(source("source_rows")), List.of(), "source", 100);
        assertTrue(exact.success(), exact.issues().toString()); assertEquals(100, exact.rows().size()); assertFalse(exact.truncated());
        sql("DELETE FROM " + schema + ".source_rows");
        var empty = preview(List.of(source("source_rows")), List.of(), "source", 100);
        assertTrue(empty.success(), empty.issues().toString()); assertTrue(empty.rows().isEmpty()); assertFalse(empty.truncated());
        assertEquals(0, runtime.queries().activeOperations());
    }

    @Test void exactDecimalsAndUnsafeIntegersAreStringsBeforeJsonReachesTheBrowser() throws Exception {
        sql("ALTER TABLE " + schema + ".source_rows ADD COLUMN amount numeric(38,18), ADD COLUMN external_id bigint");
        sql("INSERT INTO " + schema + ".source_rows VALUES(1,'sample',12345678901234567890.123456789012345678,9007199254740993)");
        var result = preview(List.of(source("source_rows"), node("sorted", "sort-records", 2,
                Map.of("sorts", List.of(Map.of("field", "id", "order", "ASC"))))), List.of(edge()), "sorted", 10);
        assertTrue(result.success(), result.issues().toString());
        assertEquals("12345678901234567890.123456789012345678", result.rows().getFirst().get("amount"));
        assertEquals("9007199254740993", result.rows().getFirst().get("external_id"));
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var wire = mapper.readTree(mapper.writeValueAsString(result.rows()));
        assertTrue(wire.get(0).get("amount").isString());
        assertTrue(wire.get(0).get("external_id").isString());
        var input = result.inputSamples().getFirst();
        assertEquals("12345678901234567890.123456789012345678", input.rows().getFirst().get("amount"));
        assertEquals("9007199254740993", input.rows().getFirst().get("external_id"));
        var completeWire = mapper.readTree(mapper.writeValueAsString(result));
        assertTrue(completeWire.get("inputSamples").get(0).get("rows").get(0).get("amount").isString());
        assertTrue(completeWire.get("inputSamples").get(0).get("rows").get(0).get("external_id").isString());
    }

    @Test void deniedOrMissingMaskingFailsClosedWithoutReturningSensitiveRowsOrVendorErrors() throws Exception {
        seed(1);
        policy = policy(sql -> { throw new SecurityException("sensitive jdbc://fixture/private"); }, rows -> rows);
        assertHiddenFailure(preview(List.of(source("source_rows")), List.of(), "source", 10));
        assertEquals(0, filters.get());
        policy = policy(sql -> sql, rows -> null);
        assertHiddenFailure(preview(List.of(source("source_rows")), List.of(), "source", 10));
        policy = policy(sql -> sql, rows -> { throw new SecurityException("private-fixture"); });
        assertHiddenFailure(preview(List.of(source("source_rows")), List.of(), "source", 10));
        assertEquals(0, runtime.queries().activeOperations());
    }

    @Test void authorizationRewriteCannotChangeObjectOrIntroduceSqlSideEffects() throws Exception {
        seed(1);
        for (String sql : List.of("SELECT * FROM " + schema + ".other_rows",
                "DELETE FROM " + schema + ".source_rows RETURNING *",
                "SELECT * FROM " + schema + ".source_rows; DELETE FROM " + schema + ".source_rows",
                "SELECT * FROM " + schema + ".source_rows FOR UPDATE")) {
            policy = policy(ignored -> sql, rows -> rows);
            assertHiddenFailure(preview(List.of(source("source_rows")), List.of(), "source", 10));
        }
        assertEquals(0, filters.get()); assertEquals(1, count());
    }

    @Test void sourceCellsAndRowsHaveByteBudgetsEvenWhenMaskingWouldDiscardThem() throws Exception {
        sql("INSERT INTO " + schema + ".source_rows VALUES(1,repeat('x',300000))");
        policy = policy(sql -> sql, rows -> List.of());
        assertHiddenFailure(preview(List.of(source("source_rows")), List.of(), "source", 100));
        assertEquals(0, filters.get()); assertEquals(0, runtime.queries().activeOperations());
    }

    @Test void sqlModeScriptsWritesAndInvalidLimitAreRejectedBeforeConnectionResolution() {
        for (String tool : List.of("sql-script", "js-script", "insert-db", "read-json"))
            assertFalse(preview(List.of(node("source", tool, 1, Map.of())), List.of(), "source", 100).success());
        var sqlSource = node("source", "read-db", 1, Map.of("databaseId", 1, "ifSql", 1, "sql", "SELECT * FROM source_rows"));
        assertFalse(preview(List.of(sqlSource), List.of(), "source", 100).success());
        assertFalse(preview(List.of(source("source_rows")), List.of(), "source", 101).success());
        assertEquals(0, resolutions.get());
    }

    @Test void explicitQuotedSchemaAndLiteralTableNamesFollowTheProductionReaderIdentity() throws Exception {
        sql("CREATE TABLE " + schema + ".\"Source.Rows\"(id integer, secret text)");
        sql("INSERT INTO " + schema + ".\"Source.Rows\" VALUES(7,'fixture')");
        var literal = preview(List.of(source("Source.Rows")), List.of(), "source", 100);
        assertTrue(literal.success(), literal.issues().toString()); assertEquals(7, literal.rows().getFirst().get("id"));
        connection.setSchema("public");
        var explicit = node("source", "read-db", 1, Map.of("databaseId", 1, "schemaName", schema,
                "tableName", "\"" + schema + "\".\"Source.Rows\""));
        var result = preview(List.of(explicit), List.of(), "source", 100);
        assertTrue(result.success(), result.issues().toString()); assertEquals(literal.rows(), result.rows());
    }

    @Test void queryDeadlineCancelsBlockedPostgresReadAndConfirmsResourceExit() throws Exception {
        seed(1); control.setAutoCommit(false);
        sql("LOCK TABLE " + schema + ".source_rows IN ACCESS EXCLUSIVE MODE");
        long started = System.nanoTime();
        var result = preview(List.of(source("source_rows")), List.of(), "source", 100);
        long elapsed = java.time.Duration.ofNanos(System.nanoTime() - started).toSeconds();
        assertHiddenFailure(result); assertTrue(elapsed >= 25 && elapsed < 40, "elapsed=" + elapsed);
        assertEquals(0, filters.get()); assertEquals(0, runtime.queries().activeOperations());
        control.rollback();
    }

    @Test void directReadToQualityCapturesTypedRawAndMaskedProofOutsideBusinessColumns() throws Exception {
        seed(3); connection.setConfigurationRevision("revision-1");
        policy = policy(sql -> sql, rows -> rows.stream().<Map<String, Object>>map(row ->
                new LinkedHashMap<>(Map.of("id", row.get("id"), "secret", "masked"))).toList());
        var proofs = capture(false);
        assertEquals(3, proofs.size());
        for (int i = 0; i < proofs.size(); i++) {
            var proof = proofs.get(i); assertNotNull(proof); assertEquals("source", proof.sourceNode());
            assertEquals("revision-1", proof.configurationRevision());
            long id = Long.parseLong(proof.keys().get("id").value());
            assertEquals(cn.iocoder.yudao.framework.dbswitch.common.util.TypedRowFingerprint.hash(
                    Map.of("id", id, "secret", "private-fixture")), proof.sourceHash());
            assertNotEquals(proof.sourceHash(), proof.emittedHash());
        }
    }

    @Test void transformationsDiscardOriginAndMaskedPrimaryKeysCannotAcquireAnotherRowsProof() throws Exception {
        seed(3); connection.setConfigurationRevision("revision-1");
        assertTrue(capture(true).stream().allMatch(Objects::isNull));
        policy = policy(sql -> sql, rows -> rows.stream().<Map<String, Object>>map(row ->
                new LinkedHashMap<>(Map.of("id", ((Number) row.get("id")).intValue() == 1 ? 2 : 1, "secret", "masked"))).toList());
        assertTrue(capture(false).stream().allMatch(Objects::isNull));
    }

    @Test void absentPrimaryKeyOrConfigurationRevisionLeavesEvidenceRestricted() throws Exception {
        seed(1); assertEquals(Collections.singletonList(null), capture(false));
        connection.setConfigurationRevision("revision-1");
        sql("ALTER TABLE " + schema + ".source_rows DROP CONSTRAINT source_rows_pkey");
        assertEquals(Collections.singletonList(null), capture(false));
    }

    private List<cn.iocoder.yudao.framework.etl.core.runtime.EtlRowOrigin> capture(boolean transform) {
        var proofs = new java.util.concurrent.CopyOnWriteArrayList<cn.iocoder.yudao.framework.etl.core.runtime.EtlRowOrigin>();
        var quality = node("quality", "quality-check", 2, Map.of());
        var definitions = new ArrayList<EtlNodeDef>(); definitions.add(source("source_rows"));
        var edges = new ArrayList<EtlEdgeDef>(); String previous = "source";
        if (transform) {
            definitions.add(node("transform", "add-constant", 2, Map.of("fields", List.of(Map.of("name", "added", "value", "x")))));
            var edge = new EtlEdgeDef(); edge.setSourceNo(previous); edge.setTargetNo("transform"); edges.add(edge); previous = "transform";
        }
        definitions.add(quality); var edge = new EtlEdgeDef(); edge.setSourceNo(previous); edge.setTargetNo("quality"); edges.add(edge);
        var rowPolicy = new EtlRowPolicyPort() {
            public List<Violation> evaluate(String node, Map<String, Object> row) { return List.of(new Violation("rule", "secret", "ERROR", "INVALID", "invalid")); }
            public void recordIssue(String node, long sequence, Map<String, Object> row, List<Violation> issues) { fail("Origin overload is required"); }
            public void recordIssue(String node, long sequence, Map<String, Object> row, List<Violation> issues,
                                    cn.iocoder.yudao.framework.etl.core.runtime.EtlRowOrigin origin) {
                assertFalse(row.containsKey("origin")); proofs.add(origin);
            }
            public void complete(String node, Counts counts) { }
        };
        try (var engine = new EtlPipelineEngine(new cn.iocoder.yudao.framework.etl.core.executor.EtlExecutorRegistry(), runtime)) {
            var result = engine.execute(EtlDag.build(definitions, edges), EtlRunOptions.builder()
                    .connectionResolver(id -> connection).readPolicyPort(policy).rowPolicyPort(rowPolicy).captureSourceOrigins(true).build());
            assertTrue(result.isSuccess(), result.getFirstError());
        }
        return proofs;
    }

    private EtlPreviewEngine.Result preview(List<EtlNodeDef> nodes, List<EtlEdgeDef> edges, String target, int limit) {
        return new EtlPreviewEngine().preview(nodes, edges, target, limit, Map.of(), null,
                new EtlPreviewEngine.DatabaseSource(runtime, id -> { resolutions.incrementAndGet(); return connection; },
                        policy, "fixture-owner", task -> task));
    }
    private EtlReadPolicyPort policy(java.util.function.UnaryOperator<String> rewrite,
                                    java.util.function.UnaryOperator<List<Map<String, Object>>> filter) {
        return new EtlReadPolicyPort() {
            public String rewrite(String node, Long id, String sql) { return rewrite.apply(sql); }
            public List<Map<String, Object>> filter(String node, Long id, String sql, List<Map<String, Object>> rows) {
                filters.incrementAndGet(); return filter.apply(rows);
            }
        };
    }
    private static void assertHiddenFailure(EtlPreviewEngine.Result result) {
        assertFalse(result.success()); assertTrue(result.rows().isEmpty()); assertTrue(result.columns().isEmpty());
        assertTrue(result.inputSamples().isEmpty()); assertEquals(0, result.targetInputRows());
        assertFalse(result.issues().toString().contains("private")); assertFalse(result.issues().toString().contains("jdbc:"));
    }
    private EtlNodeDef source(String table) { return node("source", "read-db", 1, Map.of("databaseId", 1, "tableName", table)); }
    private static EtlNodeDef node(String id, String tool, int type, Map<String, Object> config) {
        var node = new EtlNodeDef(); node.setNo(id); node.setName(id); node.setToolCode(tool); node.setToolType(type);
        node.setNodeJson(JSONUtil.toJsonStr(config)); return node;
    }
    private static EtlEdgeDef edge() { var edge = new EtlEdgeDef(); edge.setSourceNo("source"); edge.setTargetNo("sorted"); return edge; }
    private void seed(int rows) throws Exception { sql("INSERT INTO " + schema + ".source_rows SELECT i,'private-fixture' FROM generate_series(1," + rows + ") i"); }
    private int count() throws Exception { try (var st = control.createStatement(); var rs = st.executeQuery("SELECT count(*) FROM " + schema + ".source_rows")) { rs.next(); return rs.getInt(1); } }
    private void sql(String sql) throws Exception { try (var st = control.createStatement()) { st.execute(sql); } }
}

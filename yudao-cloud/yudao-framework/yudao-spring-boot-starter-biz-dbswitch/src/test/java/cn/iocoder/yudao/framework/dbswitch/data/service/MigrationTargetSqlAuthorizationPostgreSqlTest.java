package cn.iocoder.yudao.framework.dbswitch.data.service;

import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.ManagedSchemaScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.domain.MigrationTermination;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 实际 PostgreSQL 验证授权发生在 DDL 前，拒绝不落表，批准后批次消费同一参数计划。 */
@Timeout(40)
class MigrationTargetSqlAuthorizationPostgreSqlTest {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static String url, user, password;
    String schema;
    Connection connection;

    @BeforeAll static void database() {
        url = System.getProperty("datahub.test.postgres.url");
        if (url != null) {
            assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
            user = System.getProperty("datahub.test.postgres.user", "dataforge_test");
            password = "";
        } else {
            Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "需要独立 PostgreSQL 测试实例");
            POSTGRES.start();
            url = POSTGRES.getJdbcUrl(); user = POSTGRES.getUsername(); password = POSTGRES.getPassword();
        }
    }

    @AfterAll static void stop() { if (POSTGRES.isRunning()) POSTGRES.stop(); }

    @BeforeEach void setup() throws Exception {
        schema = "authorized_" + UUID.randomUUID().toString().replace("-", "");
        connection = DriverManager.getConnection(url, user, password);
        sql("CREATE SCHEMA " + schema);
        sql("CREATE TABLE " + schema + ".source_rows(id integer PRIMARY KEY, note text)");
        sql("INSERT INTO " + schema + ".source_rows SELECT n,CASE WHEN n % 2 = 0 THEN NULL ELSE 'value-' || n END FROM generate_series(1,503) n");
    }

    @AfterEach void cleanup() throws Exception {
        if (connection != null) {
            try (var owned = connection) {
                assertTrue(schema.matches("authorized_[a-f0-9]{32}"));
                sql("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void authorizesActualCreateAndInsertBeforeWritingAndCopiesEveryBatch(boolean pipeline) throws Exception {
        var calls = new AtomicInteger();
        var result = execute(request(), pipeline, (table, columns, plan) -> {
            calls.incrementAndGet();
            assertEquals("target_rows", table);
            assertEquals(List.of("id", "note"), columns);
            assertTargetAbsent();
            assertEquals(ProductTypeEnum.POSTGRESQL, plan.product());
            assertEquals(schema, plan.schemaName());
            assertEquals(2, plan.steps().size());
            assertEquals(List.of(0, 1), plan.steps().get(1).parameterOrder());
            assertTrue(plan.steps().get(0).sql().contains("CREATE TABLE"));
            assertTrue(plan.steps().get(1).sql().contains("INSERT INTO"));
            return new ManagedSchemaScriptPlan(plan.product(), plan.schemaName(), plan.steps());
        });
        assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString());
        assertEquals(MigrationTermination.CONFIRMED, result.getTermination());
        assertEquals(1, calls.get());
        var table = result.getTableResultList().getFirst();
        assertEquals(503, table.getTransferSummary().readRows());
        assertEquals(503, table.getTransferSummary().acknowledgedRows());
        assertEquals(2, table.getTransferSummary().commits().totalBatches());
        assertEquals(2, table.getTransferSummary().commits().witnessedBatches());
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT count(*) FROM " + schema + ".source_rows s FULL JOIN " + schema
                        + ".target_rows t USING(id) WHERE s.id IS NULL OR t.id IS NULL OR s.note IS DISTINCT FROM t.note")) {
            assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"deny", "null", "sql", "order", "operation", "object", "extra"})
    void rejectedOrChangedAuthorizationNeverCreatesTarget(String mutation) throws Exception {
        var result = execute(request(), true, (table, columns, plan) -> {
            if (mutation.equals("deny")) throw new IllegalArgumentException("Caller denied target plan");
            if (mutation.equals("null")) return null;
            var steps = new ArrayList<>(plan.steps());
            var insert = steps.get(1);
            steps.set(1, new ManagedSchemaScriptPlan.Step(
                    mutation.equals("sql") ? insert.sql().replace("target_rows", "other_rows") : insert.sql(),
                    mutation.equals("operation") ? "QUERY" : insert.operation(),
                    mutation.equals("object") ? java.util.Set.of("other_rows") : insert.objectNames(),
                    mutation.equals("order") ? List.of(1, 0) : insert.parameterOrder(), null));
            if (mutation.equals("extra")) steps.add(insert);
            return new ManagedSchemaScriptPlan(plan.product(), plan.schemaName(), steps);
        });
        assertFalse(result.getIfAllSuccess().get());
        assertTargetAbsent();
    }

    @Test void catalogVarchar64PlanCopiesThousandRowsAndPreservesPrimaryKey() throws Exception {
        sql("ALTER TABLE " + schema + ".source_rows ALTER COLUMN note TYPE varchar(64)");
        sql("ALTER TABLE " + schema + ".source_rows RENAME COLUMN note TO payload");
        sql("INSERT INTO " + schema + ".source_rows SELECT n,'value-' || n FROM generate_series(504,1000) n");
        var calls = new AtomicInteger();
        var result = execute(request(), true, (table, columns, plan) -> {
            calls.incrementAndGet();
            assertTargetAbsent();
            assertEquals(List.of("id", "payload"), columns);
            assertTrue(plan.steps().getFirst().sql().toLowerCase(java.util.Locale.ROOT).contains("character varying(64)"));
            assertTrue(plan.steps().getFirst().sql().contains("PRIMARY KEY"));
            assertEquals(List.of(0, 1), plan.steps().getLast().parameterOrder());
            return plan;
        });
        assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString());
        assertEquals(1, calls.get());
        var transfer = result.getTableResultList().getFirst().getTransferSummary();
        assertEquals(1000, transfer.readRows());
        assertEquals(1000, transfer.acknowledgedRows());
        assertEquals(2, transfer.commits().witnessedBatches());
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT count(*) FROM " + schema + ".source_rows s FULL JOIN " + schema
                        + ".target_rows t USING(id) WHERE s.id IS NULL OR t.id IS NULL OR s.payload IS DISTINCT FROM t.payload")) {
            assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
        }
        try (var statement = connection.prepareStatement(
                "SELECT character_maximum_length FROM information_schema.columns WHERE table_schema=? AND table_name='target_rows' AND column_name='payload'")) {
            statement.setString(1, schema);
            try (var rows = statement.executeQuery()) { assertTrue(rows.next()); assertEquals(64, rows.getInt(1)); }
        }
        try (var keys = connection.getMetaData().getPrimaryKeys(null, schema, "target_rows")) {
            assertTrue(keys.next()); assertEquals("id", keys.getString("COLUMN_NAME")); assertFalse(keys.next());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"default", "identity", "generated", "index", "comment", "columnComment", "inheritance", "trigger", "partition", "view", "disabledPolicy", "rewriteRule"})
    void unsupportedSourceSemanticsFailBeforeAuthorizationAndTargetWrites(String feature) throws Exception {
        switch (feature) {
            case "default" -> sql("ALTER TABLE " + schema + ".source_rows ALTER COLUMN note SET DEFAULT 'x'");
            case "identity" -> sql("ALTER TABLE " + schema + ".source_rows ADD COLUMN generated_id bigint GENERATED ALWAYS AS IDENTITY");
            case "generated" -> sql("ALTER TABLE " + schema + ".source_rows ADD COLUMN computed integer GENERATED ALWAYS AS (id + 1) STORED");
            case "index" -> sql("CREATE INDEX source_note ON " + schema + ".source_rows(note)");
            case "comment" -> sql("COMMENT ON TABLE " + schema + ".source_rows IS 'source comment'");
            case "columnComment" -> sql("COMMENT ON COLUMN " + schema + ".source_rows.note IS 'source comment'");
            case "inheritance" -> sql("CREATE TABLE " + schema + ".child_rows() INHERITS (" + schema + ".source_rows)");
            case "trigger" -> {
                sql("CREATE FUNCTION " + schema + ".keep_row() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END'");
                sql("CREATE TRIGGER source_trigger BEFORE INSERT ON " + schema + ".source_rows FOR EACH ROW EXECUTE FUNCTION " + schema + ".keep_row()");
            }
            case "partition" -> {
                sql("ALTER TABLE " + schema + ".source_rows RENAME TO original_rows");
                sql("CREATE TABLE " + schema + ".source_rows(id integer PRIMARY KEY, note text) PARTITION BY RANGE (id)");
            }
            case "view" -> {
                sql("ALTER TABLE " + schema + ".source_rows RENAME TO original_rows");
                sql("CREATE VIEW " + schema + ".source_rows AS SELECT * FROM " + schema + ".original_rows");
            }
            case "disabledPolicy" -> sql("CREATE POLICY source_policy ON " + schema + ".source_rows USING (true)");
            case "rewriteRule" -> sql("CREATE RULE source_rule AS ON DELETE TO " + schema + ".source_rows DO INSTEAD NOTHING");
            default -> fail("Unknown fixture");
        }
        if (feature.equals("disabledPolicy") || feature.equals("rewriteRule")) {
            var nativeSchema = cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader.read(connection,
                    ProductTypeEnum.POSTGRESQL, schema, "source_rows", 30);
            assertTrue(nativeSchema.additionalObjects().value().contains(
                    feature.equals("disabledPolicy") ? "policy:source_policy" : "rule:source_rule"));
        }
        var calls = new AtomicInteger();
        var result = execute(request(), false, (table, columns, plan) -> { calls.incrementAndGet(); return plan; });
        assertFalse(result.getIfAllSuccess().get());
        assertEquals(0, calls.get());
        assertTargetAbsent();
    }

    @Test void existingTargetRemainsUntouched() throws Exception {
        sql("CREATE TABLE " + schema + ".target_rows(id integer PRIMARY KEY, note text)");
        sql("INSERT INTO " + schema + ".target_rows VALUES (99,'keep')");
        var result = execute(request(), false, (table, columns, plan) -> fail("Existing target must fail before authorization"));
        assertFalse(result.getIfAllSuccess().get());
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT note FROM " + schema + ".target_rows")) {
            assertTrue(rows.next()); assertEquals("keep", rows.getString(1)); assertFalse(rows.next());
        }
    }

    private MigrationRequest request() {
        var request = new MigrationRequest();
        var source = new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.POSTGRESQL); source.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());
        source.setUrl(url); source.setUsername(user); source.setPassword(password); source.setPoolSize(2);
        source.setSourceSchema(schema); source.setSourceIncludes("source_rows"); source.setFetchSize(500);
        source.setRegexTableMapper(List.of(new PatternMapper("^source_rows$", "target_rows")));
        request.getSource().add(source);
        var target = request.getTarget();
        target.setTargetProductType(ProductTypeEnum.POSTGRESQL); target.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());
        target.setUrl(url); target.setUsername(user); target.setPassword(password); target.setPoolSize(2);
        target.setTargetSchema(schema); target.setWriteMode("CREATE_NEW");
        return request;
    }

    private DbSwitchResult execute(MigrationRequest request, boolean pipeline, MigrationTargetSqlAuthorizer authorizer)
            throws Exception {
        return new MigrationService(request, ignored -> { }, null,
                new DbSwitchRuntime(DbSwitchProductRegistry.discover(),
                        new DbSwitchOptions(128L * 1024 * 1024, pipeline, true, 3600, 0, 300_000, 30_000, 0), null),
                authorizer).run();
    }

    private void sql(String sql) throws Exception { try (var statement = connection.createStatement()) { statement.execute(sql); } }

    private void assertTargetAbsent() {
        try (var statement = connection.prepareStatement("SELECT to_regclass(?)")) {
            statement.setString(1, schema + ".target_rows");
            try (var rows = statement.executeQuery()) { assertTrue(rows.next()); assertNull(rows.getString(1)); }
        } catch (java.sql.SQLException failure) {
            throw new AssertionError(failure);
        }
    }
}

package cn.iocoder.yudao.framework.dbswitch.data.service;

import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.common.entity.RecordTransformer;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.domain.MigrationTermination;
import cn.iocoder.yudao.framework.dbswitch.data.domain.TableTransferSummary.Operation;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** 实际 MigrationService→JDBC→目标表验证；每个用例只操作自身的随机命名空间。 */
@Timeout(40)
class MigrationTransferPostgreSqlTest {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static String url, user, password;
    String schema;
    Connection connection;
    boolean pipeline = true;

    @BeforeAll static void database() {
        url = System.getProperty("datahub.test.postgres.url");
        if (url != null) {
            assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
            user = System.getProperty("datahub.test.postgres.user", "dataforge_test"); password = "";
        } else {
            Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "需要独立 PostgreSQL 测试实例");
            POSTGRES.start(); url = POSTGRES.getJdbcUrl(); user = POSTGRES.getUsername(); password = POSTGRES.getPassword();
        }
    }
    @AfterAll static void stop() { if (POSTGRES.isRunning()) POSTGRES.stop(); }
    @BeforeEach void setup() throws Exception {
        schema = "transfer_" + UUID.randomUUID().toString().replace("-", "");
        connection = DriverManager.getConnection(url, user, password);
        sql("CREATE SCHEMA " + schema);
        sql("CREATE TABLE " + schema + ".source_rows(id integer PRIMARY KEY, note text)");
        sql("INSERT INTO " + schema + ".source_rows VALUES (1,'one'), (2,NULL), (3,'three')");
    }
    @AfterEach void cleanup() throws Exception {
        if (connection != null) {
            try (var ownedConnection = connection) {
                assertTrue(schema.matches("transfer_[a-f0-9]{32}"));
                sql("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void actualFullScanAcknowledgesAllBatches(boolean async, boolean insert) throws Exception {
        pipeline = async;
        sql("INSERT INTO " + schema + ".source_rows SELECT n,'many' FROM generate_series(4,503) n");
        var result = run(p -> p.getTarget().setWriterEngineInsert(insert), null);
        assertEquals(Operation.FULL_SCAN_TABLE, result.getTransferSummary().operation());
        assertEquals(503, result.getTransferSummary().readRows());
        assertEquals(503, result.getTransferSummary().acknowledgedRows());
        assertEquals(2, result.getTransferSummary().sourceColumns());
        assertEquals(2, result.getTransferSummary().copiedColumns());
        assertEquals(0, result.getTransferSummary().substitutedNullValues());
        assertFalse(result.getTransferSummary().transformed());
        assertFalse(result.getTransferSummary().generatedColumns());
        assertFalse(result.getTransferSummary().customScripts());
        assertEquals(503, count("target_rows"));
        {
            var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(result.getTransferSummary());
            assertTrue(json.path("commits").path("receipts").size() > 0,
                    "实际 PostgreSQL INSERT/COPY 批次必须留存提交见证，不能只有确认行数");
            var commits = result.getTransferSummary().commits();
            assertEquals(2, commits.totalBatches()); assertEquals(2, commits.witnessedBatches());
            assertEquals(2, commits.receipts().size());
            assertEquals(2, commits.receipts().stream().map(r -> r.receipt().transactionId()).distinct().count());
            for (var entry : commits.receipts()) {
                try (var statement = connection.prepareStatement("SELECT pg_catalog.pg_xact_status(?::xid8)")) {
                    statement.setString(1, entry.receipt().transactionId());
                    try (var rows = statement.executeQuery()) { assertTrue(rows.next()); assertEquals("committed", rows.getString(1)); }
                }
            }
        }
        try (var statement = connection.createStatement(); var rs = statement.executeQuery(
                "SELECT count(*) FROM " + schema + ".source_rows s FULL JOIN " + schema
                        + ".target_rows t USING(id) WHERE s.id IS NULL OR t.id IS NULL OR s.note IS DISTINCT FROM t.note")) {
            rs.next(); assertEquals(0, rs.getInt(1));
        }
    }

    @Test void skippedExistingIsNotAFullScan() throws Exception {
        sql("CREATE TABLE " + schema + ".target_rows(id integer PRIMARY KEY, note text)");
        sql("INSERT INTO " + schema + ".target_rows VALUES (99,'keep')");
        var result = run(p -> p.getTarget().setSyncExist(false), null);
        assertEquals(Operation.SKIPPED_EXISTING, result.getTransferSummary().operation());
        assertEquals(0, result.getTransferSummary().acknowledgedRows());
        assertEquals(1, count("target_rows"));
    }

    @Test void manySourcesMappedToOneTargetFailBeforeAnyDropOrRowWrite() throws Exception {
        sql("CREATE TABLE " + schema + ".source_sibling(id integer PRIMARY KEY, note text)");
        sql("INSERT INTO " + schema + ".source_sibling VALUES (50,'sibling')");
        sql("CREATE TABLE " + schema + ".target_rows(id integer PRIMARY KEY, note text)");
        sql("INSERT INTO " + schema + ".target_rows VALUES (99,'keep')");
        assertThrows(IllegalArgumentException.class, () -> execute(p -> {
            p.getSource().getFirst().setSourceIncludes("source_rows,source_sibling");
            p.getSource().getFirst().setRegexTableMapper(java.util.List.of(
                    new cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper("^source_.*$", "target_rows")));
            p.getTarget().setTargetDrop(true);
        }, null));
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT id,note FROM " + schema + ".target_rows")) {
            assertTrue(rows.next()); assertEquals(99, rows.getInt(1)); assertEquals("keep", rows.getString(2)); assertFalse(rows.next());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void quotedCaseVariantDoesNotBlockCreationOrSubstituteForTheTarget(boolean structureOnly) throws Exception {
        sql("CREATE TABLE " + schema + ".\"Target_Rows\"(id integer PRIMARY KEY, note text)");
        sql("INSERT INTO " + schema + ".\"Target_Rows\" VALUES (99,'untouched')");
        var result = run(p -> {
            p.getTarget().setWriteMode("CREATE_NEW");
            p.getTarget().setSyncExist(false);
            p.getTarget().setOnlyCreate(structureOnly);
        }, null);
        assertEquals(structureOnly ? Operation.DDL_ONLY : Operation.FULL_SCAN_TABLE,
                result.getTransferSummary().operation());
        assertEquals(structureOnly ? 0 : 3, count("target_rows"));
        assertEquals(1, count("\"Target_Rows\""));
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT id,note FROM " + schema + ".\"Target_Rows\"")) {
            assertTrue(rows.next()); assertEquals(99, rows.getInt(1)); assertEquals("untouched", rows.getString(2));
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void actualManyBatchReceiptsAreBoundedWithoutLosingCoverageCounters(boolean async, boolean insert) throws Exception {
        pipeline = async;
        // The fixture explicitly requests 500 rows so receipt coverage crosses the 16-entry diagnostic bound.
        sql("INSERT INTO " + schema + ".source_rows SELECT n,'many' FROM generate_series(4,8503) n");
        var result = run(p -> p.getTarget().setWriterEngineInsert(insert), null);
        var commits = result.getTransferSummary().commits();
        assertEquals(18, commits.totalBatches()); assertEquals(18, commits.witnessedBatches());
        assertEquals(16, commits.receipts().size()); assertEquals(16, commits.receipts().getLast().batch());
        assertEquals(8503, count("target_rows")); assertEquals(8503, result.getTransferSummary().acknowledgedRows());
        var coverage = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(commits).path("coverage");
        assertEquals(18, coverage.path("batchCount").asLong(), "必须覆盖被截断清单之外的批次");
        assertEquals(8503, coverage.path("acknowledgedRows").asLong());
        var dialect = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL);
        try (var snapshot = dialect.openReadSnapshot(connection,5)) {
            assertEquals("RANGE_VISIBLE",snapshot.inspectCommitRanges(java.util.List.of(commits.coverage()))
                    .observations().getFirst().status());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ddlOnlyNeverReportsRowsCopied(boolean drop) throws Exception {
        sql("CREATE TABLE " + schema + ".target_rows(id integer PRIMARY KEY, note text)");
        var result = run(p -> { p.getTarget().setOnlyCreate(true); p.getTarget().setTargetDrop(drop); }, null);
        assertEquals(Operation.DDL_ONLY, result.getTransferSummary().operation());
        assertEquals(0, count("target_rows"));
        assertEquals(0, result.getTransferSummary().readRows());
    }

    @Test void emptyFullScanIsDifferentFromSkipping() throws Exception {
        sql("TRUNCATE " + schema + ".source_rows");
        var result = run(p -> { }, null);
        assertEquals(Operation.FULL_SCAN_TABLE, result.getTransferSummary().operation());
        assertEquals(0, result.getTransferSummary().readRows());
        assertEquals(0, result.getTransferSummary().acknowledgedRows());
        assertEquals(2, result.getTransferSummary().copiedColumns());
        assertEquals(0, count("target_rows"));
    }

    @Test void missingTargetColumnIsRejectedBeforeClearingExistingRows() throws Exception {
        sql("CREATE TABLE " + schema + ".target_rows(id integer PRIMARY KEY)");
        sql("INSERT INTO " + schema + ".target_rows VALUES(99)");
        var result = execute(p -> p.getTarget().setTargetTruncateData(true), null);
        assertFalse(result.getIfAllSuccess().get());
        assertEquals(0, result.getTotalRowCount().get());
        assertEquals(1, count("target_rows"));
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT id FROM " + schema + ".target_rows")) {
            assertTrue(rows.next()); assertEquals(99, rows.getInt(1));
        }
    }

    @Test void explicitlyDroppedSourceColumnIsStillAnAuthorizedPartialMapping() throws Exception {
        sql("CREATE TABLE " + schema + ".target_rows(id integer PRIMARY KEY)");
        var result = run(p -> p.getSource().getFirst().setRegexColumnMapper(java.util.List.of(
                new cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper("^note$", ""))), null);
        assertEquals(Operation.FULL_SCAN_TABLE, result.getTransferSummary().operation());
        assertEquals(2, result.getTransferSummary().sourceColumns());
        assertEquals(1, result.getTransferSummary().copiedColumns());
        assertEquals(3, count("target_rows"));
    }

    @Test void noMatchedColumnsCannotBecomeASuccessfulEmptyTransfer() throws Exception {
        sql("CREATE TABLE " + schema + ".target_rows(unrelated integer)");
        sql("INSERT INTO " + schema + ".target_rows VALUES(99)");
        var result = execute(p -> p.getTarget().setTargetTruncateData(true), null);
        assertFalse(result.getIfAllSuccess().get());
        assertEquals(0, result.getTotalRowCount().get());
        assertNull(result.getTableResultList().getFirst().getTransferSummary());
        assertEquals(1, count("target_rows"));
    }

    @Test void quotedCaseVariantColumnDoesNotAuthorizeTruncation() throws Exception {
        sql("CREATE TABLE " + schema + ".target_rows(id integer PRIMARY KEY,\"Note\" text)");
        sql("INSERT INTO " + schema + ".target_rows VALUES(99,'preserved')");
        var result = execute(p -> p.getTarget().setTargetTruncateData(true), null);
        assertFalse(result.getIfAllSuccess().get());
        assertEquals(1, count("target_rows"));
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT \"Note\" FROM " + schema + ".target_rows")) {
            assertTrue(rows.next()); assertEquals("preserved", rows.getString(1));
        }
    }

    @Test void explicitQuotedColumnMappingPreservesValues() throws Exception {
        sql("CREATE TABLE " + schema + ".target_rows(id integer PRIMARY KEY,\"Note\" text)");
        var result = run(p -> p.getSource().getFirst().setRegexColumnMapper(java.util.List.of(
                new cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper("^note$", "Note"))), null);
        assertEquals(3, result.getTransferSummary().acknowledgedRows());
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT count(*) FROM " + schema + ".source_rows s FULL JOIN " + schema
                        + ".target_rows t USING(id) WHERE s.id IS NULL OR t.id IS NULL OR s.note IS DISTINCT FROM t.\"Note\"")) {
            assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
        }
    }

    @Test void rowTransformationAndScriptsAreNotHidden() throws Exception {
        var result = run(p -> p.getTarget().setAfterSqlScripts("SELECT 1"),
                (s, t, fields, row) -> { row[1] = "changed"; return row; });
        assertTrue(result.getTransferSummary().transformed());
        assertTrue(result.getTransferSummary().customScripts());
        assertEquals(3, result.getTransferSummary().acknowledgedRows());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void explicitRowAndByteLimitsReachTheActualPipelineAndRetainCommitFacts(boolean async) throws Exception {
        pipeline = async;
        var result = run(p -> p.getTarget().setBatchLimits(
                new cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits(2, 96, 64, 10, 10_000)), null);
        assertEquals(2, result.getTransferSummary().commits().totalBatches());
        var progress = result.getBatchProgress();
        assertNotNull(progress); assertEquals(3, progress.readRows()); assertEquals(3, progress.submittedRows());
        assertEquals(3, progress.acknowledgedRows()); assertEquals(3, progress.commitWitnessedInputRows());
        assertEquals(0, progress.rejectedRows()); assertEquals(0, progress.unknownRows());
        assertEquals(0, progress.bufferedBytes()); assertTrue(progress.peakBufferedBytes() <= 3L * 96);
        assertTrue(progress.terminated()); assertEquals(3, count("target_rows"));
    }

    @Test void byteLimitAloneSplitsBeforeTheRowWouldExceedIt() throws Exception {
        var result = run(p -> p.getTarget().setBatchLimits(
                new cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits(5000, 96, 64, 10, 10_000)), null);
        assertEquals(2, result.getTransferSummary().commits().totalBatches());
        assertEquals(3, result.getBatchProgress().acknowledgedRows());
    }

    @Test void configuredSmallFetchSizeIsHonoredWithoutAnImplicitFiveHundredRowFloor() throws Exception {
        var result = run(p -> p.getSource().getFirst().setFetchSize(1), null);
        assertEquals(3, result.getTransferSummary().commits().totalBatches());
    }

    @Test void batchAgeFlushesAtTheNextReadBoundaryWithoutANewTimerThread() throws Exception {
        var result = run(p -> p.getTarget().setBatchLimits(
                new cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits(5000, 1024, 512, 10, 1)),
                (schema, table, fields, row) -> {
                    try { Thread.sleep(3); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted fixture"); }
                    return row;
                });
        assertEquals(3, result.getTransferSummary().commits().totalBatches());
    }

    @Test void oversizedCellFailsWithNoPrefixWriteAndPreservesRejectedProgress() throws Exception {
        var result = execute(p -> p.getTarget().setBatchLimits(
                new cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits(5000, 128, 8, 10, 10_000)),
                (schema, table, fields, row) -> { row[1] = "oversized fixture text"; return row; });
        assertFalse(result.getIfAllSuccess().get()); assertEquals(0, count("target_rows"));
        var progress = result.getTableResultList().getFirst().getBatchProgress();
        assertNotNull(progress); assertEquals(0, progress.acknowledgedRows());
        assertEquals(1, progress.rejectedRows()); assertEquals(0, progress.unknownRows());
    }

    @Test void copyDoesNotApplyPreparedStatementParameterLimitToItsColumns() throws Exception {
        var result = run(p -> {
            p.getTarget().setWriterEngineInsert(false);
            p.getTarget().setBatchLimits(new cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits(5000, 1024, 512, 1, 10_000));
        }, null);
        assertEquals(3, result.getBatchProgress().acknowledgedRows()); assertEquals(3, count("target_rows"));
    }

    @Test void differenceReleasesSnapshotBeforeBorrowingTheOnlyTargetWriteConnection() throws Exception {
        sql("CREATE TABLE " + schema + ".target_rows(id integer PRIMARY KEY, note text)");
        sql("INSERT INTO " + schema + ".target_rows VALUES(1,'old'),(99,'delete')");
        var result = execute(p -> { p.getTarget().setChangeDataSync(true); p.getTarget().setPoolSize(1); }, null);
        assertEquals(MigrationTermination.CONFIRMED, result.getTermination());
        assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString());
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT count(*) FROM " + schema + ".source_rows s FULL JOIN " + schema
                        + ".target_rows t USING(id) WHERE s.id IS NULL OR t.id IS NULL OR s.note IS DISTINCT FROM t.note")) {
            assertTrue(rows.next()); assertEquals(0, rows.getLong(1));
        }
    }

    @Test void configuredIncrementalRefusesImplicitBootstrap() throws Exception {
        var result = execute(p -> p.getTarget().setChangeDataSync(true), null);
        assertFalse(result.getIfAllSuccess().get());
        assertEquals(MigrationTermination.CONFIRMED, result.getTermination());
        assertEquals(0, result.getTotalRowCount().get());
        assertTargetAbsent();
    }

    @Test void beforeScriptPartialCommitStopsMigrationWithoutClaimingRollback() throws Exception {
        var failure = assertThrows(MigrationService.ScriptExecutionException.class, () -> execute(p ->
                p.getTarget().setBeforeSqlScripts("CREATE TABLE " + schema + ".hook_journal(id integer);"
                        + "INSERT INTO " + schema + ".hook_journal VALUES (1);"
                        + "INSERT INTO " + schema + ".missing_hook_table VALUES (2);"), null));
        assertEquals(2, failure.execution().acknowledgedStatements());
        assertEquals(3, failure.execution().inFlightStatement());
        assertEquals(MigrationService.ScriptState.UNKNOWN, failure.execution().state());
        assertEquals(1, count("hook_journal"));
        assertTargetAbsent();
    }

    @Test void afterScriptFailurePreservesCommittedCopyEvidence() throws Exception {
        var result = execute(p -> p.getTarget().setAfterSqlScripts(
                "INSERT INTO " + schema + ".missing_hook_table VALUES (2)"), null);
        assertFalse(result.getIfAllSuccess().get());
        assertEquals(MigrationTermination.UNCONFIRMED, result.getTermination());
        assertEquals(3, count("target_rows"));
        assertEquals(3, result.getTotalRowCount().get());
        var table = result.getTableResultList().getFirst();
        assertTrue(table.getIfSuccess().get());
        assertEquals(3, table.getTransferSummary().acknowledgedRows());
        assertTrue(table.getTransferSummary().commits().witnessedBatches() > 0);
    }

    @Test void incrementalMissingTargetKeyPreservesExistingRows() throws Exception {
        sql("CREATE TABLE " + schema + ".target_rows(id integer, note text)");
        sql("INSERT INTO " + schema + ".target_rows VALUES (99, 'untouched')");
        var result = execute(p -> p.getTarget().setChangeDataSync(true), null);
        assertFalse(result.getIfAllSuccess().get());
        assertEquals(1, count("target_rows"));
        assertEquals(0, result.getTotalRowCount().get());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void actualCopyShortWriteRemainsUnconfirmedThroughMigrationService(boolean async) throws Exception {
        pipeline = async;
        sql("CREATE TABLE " + schema + ".target_rows(id integer PRIMARY KEY, note text)");
        sql("CREATE FUNCTION " + schema + ".skip_all() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NULL; END'");
        sql("CREATE TRIGGER skip_all BEFORE INSERT ON " + schema + ".target_rows FOR EACH ROW EXECUTE FUNCTION "
                + schema + ".skip_all()");
        var result = execute(p -> { p.getTarget().setWriterEngineInsert(false); p.getTarget().setTargetDrop(false); }, null);
        assertEquals(MigrationTermination.UNCONFIRMED, result.getTermination());
        assertFalse(result.getIfAllSuccess().get());
        assertEquals(1, result.getTableResultList().size());
        assertFalse(result.getTableResultList().getFirst().getIfSuccess().get());
        assertEquals(0, count("target_rows"));
        var progress = result.getTableResultList().getFirst().getBatchProgress();
        assertNotNull(progress); assertEquals(3, progress.unknownRows());
        assertEquals(0, progress.acknowledgedRows()); assertEquals(0, progress.commitWitnessedInputRows());
    }

    private DbSwitchTableResult run(Consumer<MigrationRequest> customize, RecordTransformer transformer) throws Exception {
        var result = execute(customize, transformer);
        assertEquals(MigrationTermination.CONFIRMED, result.getTermination());
        assertTrue(result.getIfAllSuccess().get());
        assertEquals(1, result.getTableResultList().size());
        var table = result.getTableResultList().getFirst();
        assertTrue(table.getIfSuccess().get(), table::getErrorMsg);
        assertNotNull(table.getTransferSummary());
        return table;
    }

    private DbSwitchResult execute(Consumer<MigrationRequest> customize, RecordTransformer transformer) throws Exception {
        var p = new MigrationRequest();
        var source = new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.POSTGRESQL); source.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());
        source.setUrl(url); source.setUsername(user); source.setPassword(password); source.setPoolSize(2);
        source.setSourceSchema(schema); source.setSourceIncludes("source_rows"); source.setFetchSize(500);
        source.setRegexTableMapper(java.util.List.of(new cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper(
                "^source_rows$", "target_rows")));
        p.getSource().add(source);
        var target = p.getTarget();
        target.setTargetProductType(ProductTypeEnum.POSTGRESQL); target.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());
        target.setUrl(url); target.setUsername(user); target.setPassword(password); target.setPoolSize(2); target.setTargetSchema(schema);
        customize.accept(p);
        return new MigrationService(p, ignored -> { }, transformer,
                new DbSwitchRuntime(DbSwitchProductRegistry.discover(),
                        new DbSwitchOptions(128L * 1024 * 1024, pipeline, true, 3600, 0, 300_000, 30_000, 0), null)).run();
    }
    private void sql(String sql) throws Exception { try (var s = connection.createStatement()) { s.execute(sql); } }
    private void assertTargetAbsent() throws Exception {
        try (var s = connection.prepareStatement("SELECT to_regclass(?)")) {
            s.setString(1, schema + ".target_rows");
            try (var rs = s.executeQuery()) { assertTrue(rs.next()); assertNull(rs.getString(1)); }
        }
    }
    private long count(String table) throws Exception {
        try (var s = connection.createStatement(); var rs = s.executeQuery("SELECT count(*) FROM " + schema + "." + table)) {
            assertTrue(rs.next()); return rs.getLong(1);
        }
    }
}

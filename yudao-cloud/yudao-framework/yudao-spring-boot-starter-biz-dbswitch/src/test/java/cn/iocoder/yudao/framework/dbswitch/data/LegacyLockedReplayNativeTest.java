package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.entity.TypedCursor;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.SqlServer2000NativeCatalog;
import cn.iocoder.yudao.framework.dbswitch.core.util.OscarSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.core.util.SqlServer2000SourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.*;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.*;
import cn.iocoder.yudao.framework.dbswitch.driver.VerifiedDriverTestHandle;
import cn.iocoder.yudao.framework.dbswitch.product.oscar.OscarReplayMetadata;
import cn.iocoder.yudao.framework.dbswitch.product.sqlserver2000.SqlServer2000ReplayMetadata;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.cursor.*;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Every case runs on the original vendor engine and isolated driver, never on a compatibility substitute. */
@Timeout(120)
class LegacyLockedReplayNativeTest {
    private static final List<String> FIELDS = List.of("ID", "PART", "WM", "NOTE");
    private static final List<String> KEYS = List.of("PART", "ID");

    @ParameterizedTest @EnumSource(value = ProductTypeEnum.class, names = {"OSCAR", "SQLSERVER2000"})
    void registryUpsertPreservesCompositeKeyAndExactDecimal(ProductTypeEnum product) throws Exception {
        try (var f = new Fixture(product)) {
            f.inspect(); var writer = f.writer();
            var result = writer.writeWithReceipt(FIELDS, List.<Object[]>of(row(1, 2, "99999999999999999999999999999999.123456", "first"), row(2, 2, "1.000001", "next")));
            assertEquals(2, result.acknowledgedRows()); assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED, result.commitAcknowledgement());
            assertEquals(1, writer.write(FIELDS, List.<Object[]>of(row(1, 2, "2.000002", "changed"))));
            assertEquals(List.of("1:2:2.000002:changed", "2:2:1.000001:next"), f.rows());
            assertEquals(2, writer.keyContract().keys().size());
        }
    }
    @ParameterizedTest @EnumSource(value = ProductTypeEnum.class, names = {"OSCAR", "SQLSERVER2000"})
    void entireLossyBatchIsRejectedBeforeWriting(ProductTypeEnum product) throws Exception {
        try (var f = new Fixture(product)) {
            var writer = f.writer();
            for (Object invalid : List.of(new BigDecimal("1.0000001"), new BigDecimal("1E32"), 0.1d)) {
                assertThrows(BatchRejectedException.class, () -> writer.write(FIELDS, List.<Object[]>of(row(1, 1, "1", "ok"), new Object[]{2, 1, invalid, "bad"})));
                assertTrue(f.rows().isEmpty());
            }
            assertThrows(BatchRejectedException.class, () -> writer.write(FIELDS, List.<Object[]>of(new Object[]{null, 1, BigDecimal.ONE, "bad"})));
            assertThrows(BatchRejectedException.class, () -> writer.write(FIELDS, List.<Object[]>of(row(1, 1, "1", "非ASCII"))));
            assertTrue(f.rows().isEmpty());
        }
    }
    @ParameterizedTest @EnumSource(value = ProductTypeEnum.class, names = {"OSCAR", "SQLSERVER2000"})
    void lockedKeysetPagesAndResumeRewindTheWatermarkGroup(ProductTypeEnum product) throws Exception {
        try (var f = new Fixture(product)) {
            var writer = f.writer(); writer.write(FIELDS, List.<Object[]>of(row(1, 1, "1.123456", "a"), row(2, 1, "1.123456", "b"), row(3, 1, "2.000001", "c")));
            SourceGenerationPin pin = f.initialize(); TypedCursor position, upper; String fingerprint;
            try (var reader = f.cursor(pin, null, null, null)) {
                upper = reader.upperBound(); fingerprint = reader.schemaFingerprint();
                assertTrue(reader.next()); assertEquals(1, reader.row()[0]); position = reader.position();
                assertTrue(reader.next()); assertEquals(2, reader.row()[0]);
                assertTrue(reader.next()); assertEquals(3, reader.row()[0]); assertFalse(reader.next());
            }
            writer.write(FIELDS, List.<Object[]>of(row(2, 1, "1.123456", "b2"), row(4, 1, "3.000001", "later")));
            try (var resumed = f.cursor(pin, position, upper, fingerprint)) {
                var ids = new ArrayList<Integer>(); while (resumed.next()) ids.add((Integer) resumed.row()[0]);
                assertEquals(List.of(1, 2, 3), ids); // Replay the complete watermark group, respecting the frozen upper bound.
            }
        }
    }
    @ParameterizedTest @EnumSource(value = ProductTypeEnum.class, names = {"OSCAR", "SQLSERVER2000"})
    void fencedAndRotatedGenerationCannotResumeOldCheckpoint(ProductTypeEnum product) throws Exception {
        try (var f = new Fixture(product)) {
            f.writer().write(FIELDS, List.<Object[]>of(row(1, 1, "1", "a")));
            var pin = f.initialize(); String fingerprint; TypedCursor upper;
            try (var reader = f.cursor(pin, null, null, null)) { fingerprint = reader.schemaFingerprint(); upper = reader.upperBound(); }
            f.fence(pin); assertThrows(IllegalArgumentException.class, () -> f.cursor(pin, null, upper, fingerprint));
            var next = f.rotate(pin, UUID.randomUUID().toString());
            assertThrows(IllegalArgumentException.class, () -> f.cursor(pin, null, upper, fingerprint));
            assertThrows(IllegalArgumentException.class, () -> f.cursor(next, null, upper, fingerprint));
            try (var reader = f.cursor(next, null, null, null)) { assertTrue(reader.next()); }
            assertEquals(next, f.rotate(pin, next.generation()));
        }
    }
    @Test void sql2000AutomaticStatisticsHaveReplayEvidenceButRemainOutsideStructureClone() throws Exception {
        try (var f = new Fixture(ProductTypeEnum.SQLSERVER2000); var c = f.source.getConnection()) {
            for (int i = 0; i < 50; i++) f.execute("INSERT INTO " + f.relation + " VALUES(" + i + ",1," + i + ",'row')");
            f.execute("SELECT * FROM " + f.relation + " WHERE WM>=1 ORDER BY WM");
            assertThrows(SQLFeatureNotSupportedException.class, () -> SqlServer2000NativeCatalog.read(c, f.schema, f.table, 10));
            assertEquals(4, SqlServer2000NativeCatalog.readForReplay(c, f.schema, f.table, 10).columns().size());
            f.execute("CREATE INDEX IDX_" + f.table + " ON " + f.relation + "(NOTE)");
            assertThrows(SQLFeatureNotSupportedException.class, () -> SqlServer2000NativeCatalog.readForReplay(c, f.schema, f.table, 10));
        }
    }
    @Test void sql2000NvarcharPayloadRetainsUnicodeInNativeUpsertAndCursor() throws Exception {
        try (var f = new Fixture(ProductTypeEnum.SQLSERVER2000)) {
            f.execute("ALTER TABLE " + f.relation + " ALTER COLUMN NOTE NVARCHAR(32) NULL");
            var writer = f.writer();
            writer.write(FIELDS, List.<Object[]>of(row(-1, -2, "-1.123456", "中文\uD83D\uDE00"), new Object[]{0, -2, BigDecimal.ZERO, null}));
            assertEquals(List.of("-1:-2:-1.123456:中文\uD83D\uDE00", "0:-2:0.000000:null"), f.rows());
            try (var reader = f.cursor(f.initialize(), null, null, null)) {
                assertTrue(reader.next()); assertEquals("中文\uD83D\uDE00", reader.row()[3]);
                assertTrue(reader.next()); assertNull(reader.row()[3]); assertFalse(reader.next());
            }
            assertThrows(BatchRejectedException.class, () -> writer.write(FIELDS, List.<Object[]>of(row(1, -2, "1", "\uD800"))));
            assertEquals(2, f.rows().size());
        }
    }
    @ParameterizedTest @EnumSource(value = ProductTypeEnum.class, names = {"OSCAR", "SQLSERVER2000"})
    void concurrentOverlappingUpsertsSerializeWithoutDuplicateOrPartialRows(ProductTypeEnum product) throws Exception {
        try (var f = new Fixture(product); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = f.writer(); var second = f.writer(); var gate = new CountDownLatch(1);
            var a = executor.submit(() -> { gate.await(); return first.write(FIELDS, List.<Object[]>of(row(1, 1, "1", "a"), row(2, 1, "1", "a"))); });
            var b = executor.submit(() -> { gate.await(); return second.write(FIELDS, List.<Object[]>of(row(2, 1, "2", "b"), row(1, 1, "2", "b"))); });
            gate.countDown(); assertEquals(2L, a.get(20, TimeUnit.SECONDS)); assertEquals(2L, b.get(20, TimeUnit.SECONDS));
            var rows = f.rows(); assertEquals(2, rows.size());
            assertEquals(rows.getFirst().substring(2), rows.getLast().substring(2));
        }
    }
    @ParameterizedTest @EnumSource(value = ProductTypeEnum.class, names = {"OSCAR", "SQLSERVER2000"})
    void keysOnlyReplayIsIdempotent(ProductTypeEnum product) throws Exception {
        try (var f = new Fixture(product)) {
            f.execute("DROP TABLE " + f.relation);
            f.execute("CREATE TABLE " + f.relation + "(ID INT NOT NULL,PART INT NOT NULL,PRIMARY KEY(PART,ID))");
            var writer = DbSwitchProductRegistry.discover().upsertWriter(f.source, product);
            writer.prepareUpsert(f.schema, f.table, List.of("ID", "PART"), KEYS);
            for (int pass = 0; pass < 2; pass++) assertEquals(2, writer.write(List.of("ID", "PART"), List.<Object[]>of(new Object[]{1, 2}, new Object[]{2, 2})));
            try (var c = f.source.getConnection(); var statement = c.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM " + f.relation)) {
                assertTrue(rows.next()); assertEquals(2, rows.getInt(1));
            }
        }
    }
    @ParameterizedTest @EnumSource(value = ProductTypeEnum.class, names = {"OSCAR", "SQLSERVER2000"})
    void failedResponseAfterActualBatchExecutionRollsBackAndReportsUnknown(ProductTypeEnum product) throws Exception {
        try (var f = new Fixture(product)) {
            var executions = new AtomicInteger(); var commits = new AtomicInteger();
            DataSource faulty = new org.springframework.jdbc.datasource.AbstractDataSource() {
                @Override public Connection getConnection() throws SQLException {
                    var actual = f.source.getConnection();
                    return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("commit")) commits.incrementAndGet();
                        try {
                            Object value = method.invoke(actual, args);
                            if (!method.getName().equals("prepareStatement") || !(value instanceof PreparedStatement statement)) return value;
                            return java.lang.reflect.Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (unused, operation, parameters) -> {
                                try {
                                    Object result = operation.invoke(statement, parameters);
                                    if (operation.getName().equals("executeBatch")) { executions.incrementAndGet(); throw new SQLException("Synthetic lost batch response", "08006"); }
                                    return result;
                                } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                            });
                        } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                    });
                }
                @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
            };
            var writer = DbSwitchProductRegistry.discover().upsertWriter(faulty, product);
            writer.prepareUpsert(f.schema, f.table, FIELDS, KEYS);
            assertThrows(UnconfirmedWriteException.class, () -> writer.write(FIELDS, List.<Object[]>of(row(1, 1, "1", "one"), row(2, 1, "2", "two"))));
            assertEquals(1, executions.get()); assertEquals(0, commits.get()); assertTrue(f.rows().isEmpty());
        }
    }
    @ParameterizedTest @EnumSource(value = ProductTypeEnum.class, names = {"OSCAR", "SQLSERVER2000"})
    void sourceLockIncludesEmptyTableAndBlocksDmlUntilPhysicalClose(ProductTypeEnum product) throws Exception {
        try (var f = new Fixture(product); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = f.writer(); var pin = f.initialize();
            for (int pass = 0; pass < 2; pass++) {
                var reader = f.cursor(pin, null, null, null); var entered = new CountDownLatch(1);
                try {
                    var blocked = executor.submit(() -> { entered.countDown(); return writer.write(FIELDS, List.<Object[]>of(row(1, 1, "1", "writer"))); });
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> blocked.get(500, TimeUnit.MILLISECONDS));
                    assertEquals(pass != 0, reader.next());
                    reader.close(); assertEquals(1L, blocked.get(20, TimeUnit.SECONDS));
                } finally { reader.close(); }
            }
        }
    }
    @ParameterizedTest @EnumSource(value = ProductTypeEnum.class, names = {"OSCAR", "SQLSERVER2000"})
    void lostCommitResponseRemainsUnknownBeforeAndAfterNativeCommit(ProductTypeEnum product) throws Exception {
        for (boolean committed : new boolean[]{false, true}) try (var f = new Fixture(product)) {
            var commits = new AtomicInteger();
            DataSource faulty = new org.springframework.jdbc.datasource.AbstractDataSource() {
                @Override public Connection getConnection() throws SQLException {
                    var actual = f.source.getConnection();
                    return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("commit")) {
                            commits.incrementAndGet(); if (committed) actual.commit(); throw new SQLException("Synthetic lost commit response", "08006");
                        }
                        try { return method.invoke(actual, args); }
                        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                    });
                }
                @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
            };
            var writer = DbSwitchProductRegistry.discover().upsertWriter(faulty, product);
            writer.prepareUpsert(f.schema, f.table, FIELDS, KEYS);
            var failure = assertThrows(UnconfirmedWriteException.class, () -> writer.writeWithReceipt(FIELDS, List.<Object[]>of(row(1, 1, "1", "maybe"))));
            assertNull(failure.getCause()); assertEquals(1, commits.get()); assertEquals(committed ? 1 : 0, f.rows().size());
        }
    }
    @ParameterizedTest @EnumSource(value = ProductTypeEnum.class, names = {"OSCAR", "SQLSERVER2000"})
    void changedTargetSchemaIsRejectedBeforeAnyDataStatement(ProductTypeEnum product) throws Exception {
        try (var f = new Fixture(product)) {
            var writer = f.writer(); f.execute("ALTER TABLE " + f.relation + " ADD EXTRA INT NULL");
            assertThrows(IllegalStateException.class, () -> writer.write(FIELDS, List.<Object[]>of(row(1, 1, "1", "not-written"))));
            assertTrue(f.rows().isEmpty());
        }
    }
    @ParameterizedTest @EnumSource(value = ProductTypeEnum.class, names = {"OSCAR", "SQLSERVER2000"})
    void publicMigrationServiceReplaysEqualWatermarkAndPublishesOnlyConfirmedCheckpoint(ProductTypeEnum product) throws Exception {
        try (var source = new Fixture(product); var target = new Fixture(product)) {
            source.writer().write(FIELDS, List.<Object[]>of(row(1, 1, "1.123456", "first"), row(2, 1, "1.123456", "second")));
            var pin = source.initialize(); var saved = new AtomicReference<CursorCheckpoint>();
            String prefix = "DATAHUB_TEST_" + (product == ProductTypeEnum.OSCAR ? "OSCAR" : "SQL2000") + "_";
            Path artifact = Path.of(System.getenv(prefix + "DRIVER"));
            var spec = new VerifiedDriverTestHandle.Spec(artifact.toString(), VerifiedDriverTestHandle.sha256(artifact), product == ProductTypeEnum.OSCAR ? "com.oscar.Driver" : "com.microsoft.sqlserver.jdbc.SQLServerDriver");
            try (var drivers = new ConfiguredJdbcDrivers(Map.of(product, spec));
                 var runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), DbSwitchOptions.defaults(), drivers)) {
                var request = new MigrationRequest(); var input = new SourceDataSourceProperties();
                input.setSourceProductType(product); input.setUrl(System.getenv(prefix + "URL"));
                input.setUsername(System.getenv(prefix + "USER")); input.setPassword(System.getenv(prefix + "PASSWORD"));
                input.setPoolSize(2); input.setSourceSchema(source.schema); input.setSourceTableNames(List.of(source.table)); input.setIncludeOrExclude(1);
                input.setFetchSize(1); input.setIncreaseColumnName("WM"); input.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1");
                input.setRegexTableMapper(List.of(new PatternMapper("^" + source.table + "$", target.table))); request.getSource().add(input);
                var output = request.getTarget(); output.setTargetProductType(product); output.setUrl(input.getUrl()); output.setUsername(input.getUsername()); output.setPassword(input.getPassword());
                output.setTargetSchema(target.schema); output.setPoolSize(2); output.setChangeDataSync(true); output.setChangeDataSyncType("INCREASE_COLUMN");
                for (int pass = 0; pass < 2; pass++) {
                    output.setCursorRunBindings(Map.of(source.table, new CursorRunBinding("b".repeat(64), saved.get(), checkpoint -> { saved.set(checkpoint); return true; }, pin,
                            product == ProductTypeEnum.SQLSERVER2000 ? target.initialize() : null)));
                    var result = new MigrationService(request, ignored -> { }, null, runtime).run();
                    assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString());
                    assertTrue(saved.get().complete()); assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED, saved.get().lastCommitAcknowledgement());
                    assertEquals(source.rows(), target.rows());
                    if (pass == 0) source.writer().write(FIELDS, List.<Object[]>of(row(0, 1, "1.123456", "late equal watermark")));
                }
            }
        }
    }
    @ParameterizedTest @EnumSource(value = ProductTypeEnum.class, names = {"OSCAR", "SQLSERVER2000"})
    void committedBatchWithoutCheckpointReplaysFrozenWindowThenAdvances(ProductTypeEnum product) throws Exception {
        try (var source = new Fixture(product); var target = new Fixture(product); var runtime = DbSwitchRuntime.standalone()) {
            source.writer().write(FIELDS, List.<Object[]>of(row(1, 1, "1", "one"), row(2, 1, "1", "two"), row(3, 1, "2", "three")));
            var pin = source.initialize(); var saved = new AtomicReference<CursorCheckpoint>(); var calls = new AtomicInteger();
            CursorRunBinding.CheckpointStore failing = checkpoint -> { if (calls.incrementAndGet() == 3) return false; saved.set(checkpoint); return true; };
            assertThrows(IllegalStateException.class, () -> transfer(runtime, source, target, pin, saved.get(), failing));
            assertEquals(1, saved.get().sequence()); assertFalse(saved.get().complete()); assertEquals(2, target.rows().size());
            source.writer().write(FIELDS, List.<Object[]>of(row(0, 1, "1", "late"), row(4, 1, "3", "later window")));
            transfer(runtime, source, target, pin, saved.get(), checkpoint -> { saved.set(checkpoint); return true; });
            assertEquals(4, target.rows().size()); assertTrue(saved.get().complete());
            transfer(runtime, source, target, pin, saved.get(), checkpoint -> { saved.set(checkpoint); return true; });
            assertEquals(source.rows(), target.rows());
        }
    }
    private static void transfer(DbSwitchRuntime runtime, Fixture source, Fixture target, SourceGenerationPin pin,
                                 CursorCheckpoint previous, CursorRunBinding.CheckpointStore store) throws Exception {
        CursorTransfer.execute(runtime, source.source, target.source, source.product, target.product,
                source.schema, source.table, target.schema, target.table, FIELDS, FIELDS, KEYS, KEYS, "WM",
                BatchLimits.defaults(), 1, new CursorRunBinding("b".repeat(64), previous, store, pin,
                        target.product == ProductTypeEnum.SQLSERVER2000 ? target.initialize() : null), new DbSwitchTableResult(), () -> { });
    }
    @Test void sql2000TargetTruncateRequiresRotationAndRejectsOldCheckpointBeforeRefilling() throws Exception {
        try (var source = new Fixture(ProductTypeEnum.SQLSERVER2000); var target = new Fixture(ProductTypeEnum.SQLSERVER2000); var runtime = DbSwitchRuntime.standalone()) {
            source.writer().write(FIELDS, List.<Object[]>of(row(1, 1, "1", "earlier"), row(2, 1, "2", "latest")));
            var pin = source.initialize(); var saved = new AtomicReference<CursorCheckpoint>();
            transfer(runtime, source, target, pin, null, checkpoint -> { saved.set(checkpoint); return true; });
            var targetPin = target.initialize(); target.fence(targetPin); target.execute("TRUNCATE TABLE " + target.relation);
            assertThrows(IllegalArgumentException.class, () -> transfer(runtime, source, target, pin, saved.get(), checkpoint -> true));
            target.rotate(targetPin, UUID.randomUUID().toString());
            assertThrows(IllegalArgumentException.class, () -> transfer(runtime, source, target, pin, saved.get(), checkpoint -> true));
            assertTrue(target.rows().isEmpty());
            transfer(runtime, source, target, pin, null, checkpoint -> { saved.set(checkpoint); return true; });
            assertEquals(source.rows(), target.rows());
        }
    }
    @Test void sql2000CursorTargetWithoutDeploymentPinCannotOpenTheSourceOrWrite() throws Exception {
        try (var source = new Fixture(ProductTypeEnum.SQLSERVER2000); var target = new Fixture(ProductTypeEnum.SQLSERVER2000); var runtime = DbSwitchRuntime.standalone()) {
            var sourceData = org.mockito.Mockito.mock(DataSource.class);
            assertThrows(IllegalArgumentException.class, () -> CursorTransfer.execute(runtime, sourceData, target.source, source.product, target.product,
                    source.schema, source.table, target.schema, target.table, FIELDS, FIELDS, KEYS, KEYS, "WM", BatchLimits.defaults(), 1,
                    new CursorRunBinding("b".repeat(64), null, checkpoint -> true, source.initialize()), new DbSwitchTableResult(), () -> { }));
            org.mockito.Mockito.verifyNoInteractions(sourceData); assertTrue(target.rows().isEmpty());
        }
    }
    private static Object[] row(int id, int part, String watermark, String note) { return new Object[]{id, part, new BigDecimal(watermark), note}; }

    private static final class Fixture implements AutoCloseable {
        final ProductTypeEnum product;
        final String token = "R89_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
        final String schema, table = token, relation, generationNamespace;
        final VerifiedDriverTestHandle driver;
        final DataSource source;
        boolean generation;
        SourceGenerationPin initializedPin;
        Fixture(ProductTypeEnum product) throws Exception {
            this.product = product; boolean oscar = product == ProductTypeEnum.OSCAR; String prefix = "DATAHUB_TEST_" + (oscar ? "OSCAR" : "SQL2000") + "_";
            Assumptions.assumeTrue(System.getenv(prefix + "URL") != null, "Original vendor fixture was not selected");
            driver = new VerifiedDriverTestHandle(Path.of(System.getenv(prefix + "DRIVER")), product);
            source = driver.dataSource(System.getenv(prefix + "URL"), oscar ? "com.oscar.Driver" : "com.microsoft.sqlserver.jdbc.SQLServerDriver",
                    System.getenv(prefix + "USER"), System.getenv(prefix + "PASSWORD"), new Properties());
            schema = oscar ? token : "dbo"; generationNamespace = token;
            relation = oscar ? OscarReplayMetadata.relation(schema, table) : SqlServer2000ReplayMetadata.relation(schema, table);
            if (oscar) execute("CREATE SCHEMA " + schema);
            try { execute("CREATE TABLE " + relation + "(ID INT NOT NULL,PART INT NOT NULL,WM DECIMAL(38,6) NOT NULL,NOTE VARCHAR(32) NULL,PRIMARY KEY(PART,ID))"); }
            catch (Exception failure) { if (oscar) execute("DROP SCHEMA " + schema + " CASCADE"); driver.close(); throw failure; }
        }
        void inspect() throws SQLException { try (var c = source.getConnection()) { if (product == ProductTypeEnum.OSCAR) OscarReplayMetadata.keys(OscarReplayMetadata.read(c, schema, table, 10), KEYS); else SqlServer2000ReplayMetadata.keys(SqlServer2000ReplayMetadata.read(c, schema, table, 10), KEYS); } }
        IDatabaseUpsertWriter writer() { var writer = DbSwitchProductRegistry.discover().upsertWriter(source, product); writer.prepareUpsert(schema, table, FIELDS, KEYS); return writer; }
        CursorReadSession cursor(SourceGenerationPin pin, TypedCursor lower, TypedCursor upper, String expected) {
            var operator = DbSwitchProductRegistry.discover().reader(source, product); operator.setFetchSize(1);
            return operator.openCursor(new CursorReadRequest(schema, table, FIELDS, "WM", KEYS, lower, upper, expected, BatchLimits.defaults(), upper != null, pin));
        }
        SourceGenerationPin initialize() throws SQLException { if (initializedPin != null) return initializedPin; generation = true; try (var c = source.getConnection()) { return initializedPin = product == ProductTypeEnum.OSCAR ? OscarSourceGeneration.initialize(c, generationNamespace, 10) : SqlServer2000SourceGeneration.initialize(c, generationNamespace, 10); } }
        void fence(SourceGenerationPin pin) throws SQLException { try (var c = source.getConnection()) { if (product == ProductTypeEnum.OSCAR) OscarSourceGeneration.fence(c, pin, 10); else SqlServer2000SourceGeneration.fence(c, pin, 10); } }
        SourceGenerationPin rotate(SourceGenerationPin pin, String next) throws SQLException { try (var c = source.getConnection()) { return initializedPin = product == ProductTypeEnum.OSCAR ? OscarSourceGeneration.rotate(c, pin, next, 10) : SqlServer2000SourceGeneration.rotate(c, pin, next, 10); } }
        List<String> rows() throws SQLException {
            var result = new ArrayList<String>(); try (var c = source.getConnection(); var p = c.createStatement(); var r = p.executeQuery("SELECT ID,PART,WM,NOTE FROM " + relation + " ORDER BY ID,PART")) {
                while (r.next()) result.add(r.getInt(1) + ":" + r.getInt(2) + ":" + r.getBigDecimal(3).toPlainString() + ":" + r.getString(4));
            } return result;
        }
        void execute(String sql) throws SQLException { try (var c = source.getConnection(); var s = c.createStatement()) { s.setQueryTimeout(10); s.execute(sql); } }
        public void close() throws Exception {
            try {
                if (product == ProductTypeEnum.OSCAR) execute("DROP SCHEMA " + schema + " CASCADE");
                else { execute("DROP TABLE " + relation); if (generation) try (var c = source.getConnection()) { SqlServer2000SourceGeneration.drop(c, generationNamespace, 10); } }
            } finally { driver.close(); }
        }
    }
}

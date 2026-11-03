package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.data.recovery.PostgresGroupRewriteStore;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.*;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.tdengine.TdengineDatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.tdengine.TdengineWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineNativeTables;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.springframework.jdbc.datasource.*;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.math.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(120)
@EnabledIfSystemProperty(named="datahub.test.tdengine.ws.url", matches="jdbc:TAOS-WS://127[.]0[.]0[.]1:[0-9]+/")
@EnabledIfSystemProperty(named="datahub.test.postgres.url", matches="jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+")
@EnabledIfEnvironmentVariable(named="DBSWITCH_TEST_TDENGINE_PASSWORD", matches=".+")
class TdengineGroupRecoveryTdengineTest {
    private static final List<String> FIELDS = List.of("ts", "id", "amount", "u", "raw", "note");
    private static final List<String> KEYS = List.of("ts", "id");
    private static final BatchLimits LIMITS = new BatchLimits(20, 32768, 8192, 100, 1000);
    private static final DbSwitchOptions OPTIONS = new DbSwitchOptions(32768, false, false, 15, 0, 0, 250, 0);
    private static final Timestamp TS = Timestamp.from(Instant.parse("2026-10-03T00:00:00.123456789Z"));
    private String database, controlSchema, physical;
    private DataSource control, target;
    private PostgresGroupRewriteStore store;
    private final AtomicReference<String> fault = new AtomicReference<>();
    private final AtomicBoolean owned = new AtomicBoolean(true);
    private final AtomicInteger writes = new AtomicInteger();
    @BeforeEach void setup() throws Exception {
        Class.forName("com.taosdata.jdbc.ws.WebSocketDriver");
        database = "group_td_" + UUID.randomUUID().toString().replace("-", "");
        target = target(); control = new DriverManagerDataSource(System.getProperty("datahub.test.postgres.url"), System.getProperty("datahub.test.postgres.user", "dataforge_test"), "");
        sql(target, "CREATE DATABASE " + database + " VGROUPS 1 BUFFER 8 PRECISION 'ns'"); create();
        controlSchema = "td_recovery_" + UUID.randomUUID().toString().replace("-", ""); sql(control, "CREATE SCHEMA " + controlSchema);
        for (String ddl : PostgresGroupRewriteStore.schemaStatements(controlSchema + ".journal")) sql(control, ddl);
        byte[] secret = new byte[32]; new java.security.SecureRandom().nextBytes(secret);
        store = new PostgresGroupRewriteStore(control, controlSchema + ".journal", scope(), "fixture_key", Map.of("fixture_key", new SecretKeySpec(secret, "AES")), LIMITS, 10,
                (c,k) -> { if (!owned.get()) throw new SQLException("fixture ownership revoked"); });
        try (var c = target.getConnection()) { physical = TdengineNativeTables.recoveryIdentity(c, database, "rows", 15).physical(); }
        var writer = new TdengineWriterImpl(target); writer.configure(OPTIONS); writer.configureBatchLimits(LIMITS); writer.prepareWrite(database, "rows", FIELDS);
        assertEquals(3, writer.write(FIELDS, List.of(row(1), row(2), row(3)))); writes.set(0);
    }
    @AfterEach void cleanup() throws Exception {
        fault.set(null); owned.set(true);
        if (database != null) sql(target, "DROP DATABASE IF EXISTS " + database);
        if (controlSchema != null) sql(control, "DROP SCHEMA " + controlSchema + " CASCADE");
    }
    @Test void durableGroupDeleteKeepsExactSiblingsAndSupportsCompositeInsertAndUpdate() throws Exception {
        var sync = sync(store, "owner_initial_0001", false, LIMITS);
        assertEquals(1, sync.executeDelete(Collections.singletonList(row(2)))); assertRows(1, 3);
        assertEquals(1, sync.executeInsert(Collections.singletonList(row(4))));
        var changed = row(3); changed[5] = "changed中文'\\\n";
        assertEquals(1, sync.executeUpdate(Collections.singletonList(changed)));
        assertEquals(GroupRewriteRecord.Phase.APPLIED, onlyRecord().phase());
        try (var c = target.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT note FROM " + name() + " WHERE id=3")) {
            assertTrue(r.next()); assertEquals(changed[5], r.getString(1));
        }
    }
    @Test void lostDeleteReplyLeavesEmptyGroupButPrepareReconcilesBeforeAnyFutureDiffCallback() throws Exception {
        var sync = sync(store, "owner_initial_0001", false, LIMITS); fault.set("DELETE");
        assertThrows(UnconfirmedWriteException.class, () -> sync.executeDelete(Collections.singletonList(row(2))));
        assertRows(); assertEquals(GroupRewriteRecord.Phase.DELETE_INTENT, onlyRecord().phase());
        assertThrows(IllegalStateException.class, () -> sync(store, "owner_restarted_02", false, LIMITS)); assertRows();
        sync(store, "owner_restarted_02", true, LIMITS); assertRows(1, 3);
        assertEquals(GroupRewriteRecord.Phase.APPLIED, onlyRecord().phase());
    }
    @Test void lostRestoreReplyReplaysOnlyMissingSurvivorsAfterRestart() throws Exception {
        var sync = sync(store, "owner_initial_0001", false, LIMITS); fault.set("INSERT");
        assertThrows(UnconfirmedWriteException.class, () -> sync.executeDelete(Collections.singletonList(row(2))));
        assertRows(1); assertEquals(GroupRewriteRecord.Phase.RESTORE_INTENT, onlyRecord().phase()); int sent = writes.get();
        sync(store, "owner_restarted_02", true, LIMITS); assertRows(1, 3);
        assertEquals(sent + 1, writes.get(), "The already visible survivor is not blindly resent");
    }
    @Test void durableIntentResponseLossSendsNoDeleteAndCanBeRecoveredExplicitly() throws Exception {
        var uncertain = new ForwardStore(store) {
            @Override public Optional<GroupRewriteRecord> transition(GroupRewriteKey key, String owner, long revision, GroupRewriteRecord.Phase expected, GroupRewriteRecord.Phase next) {
                var result = super.transition(key, owner, revision, expected, next);
                if (next == GroupRewriteRecord.Phase.DELETE_INTENT) throw new IllegalStateException("fixture control commit reply lost"); return result;
            }
        };
        var sync = sync(uncertain, "owner_initial_0001", false, LIMITS);
        assertThrows(IllegalStateException.class, () -> sync.executeDelete(Collections.singletonList(row(2))));
        assertEquals(0, writes.get()); assertRows(1, 2, 3);
        assertEquals(GroupRewriteRecord.Phase.DELETE_INTENT, onlyRecord().phase());
        sync(store, "owner_restarted_02", true, LIMITS); assertRows(1, 3);
    }
    @Test void revokedOwnershipAfterIntentPreventsTargetSendAndLeavesRecoveryEvidence() throws Exception {
        var revoking = new ForwardStore(store) {
            @Override public Optional<GroupRewriteRecord> transition(GroupRewriteKey key, String owner, long revision, GroupRewriteRecord.Phase expected, GroupRewriteRecord.Phase next) {
                var result = super.transition(key, owner, revision, expected, next); if (next == GroupRewriteRecord.Phase.DELETE_INTENT) owned.set(false); return result;
            }
        };
        var sync = sync(revoking, "owner_initial_0001", false, LIMITS);
        assertThrows(IllegalStateException.class, () -> sync.executeDelete(Collections.singletonList(row(2))));
        assertEquals(0, writes.get()); owned.set(true); assertRows(1, 2, 3);
        sync(store, "owner_restarted_02", true, LIMITS); assertRows(1, 3);
    }
    @Test void completeGroupBudgetFailureDoesNotPersistIntentOrWriteAPrefix() throws Exception {
        var small = new BatchLimits(2, 32768, 8192, 100, 1000);
        var sync = sync(store, "owner_initial_0001", false, small);
        assertThrows(IllegalStateException.class, () -> sync.executeDelete(Collections.singletonList(row(2))));
        assertEquals(0, writes.get()); assertRows(1, 2, 3);
        assertTrue(store.pendingKeys(scope(), physical, null, 10).keys().isEmpty());
    }
    @Test void staleDeleteBeforeImageDoesNotRemoveAnUpdatedTargetRow() throws Exception {
        var sync = sync(store, "owner_initial_0001", false, LIMITS); var stale = row(2); stale[5] = "old value";
        assertThrows(IllegalStateException.class, () -> sync.executeDelete(Collections.singletonList(stale)));
        assertEquals(0, writes.get()); assertRows(1, 2, 3);
        assertTrue(store.pendingKeys(scope(), physical, null, 10).keys().isEmpty());
    }
    @Test void changedSurvivorAfterUnknownOutcomeIsNeverOverwrittenByRecovery() throws Exception {
        var sync = sync(store, "owner_initial_0001", false, LIMITS); fault.set("INSERT");
        assertThrows(UnconfirmedWriteException.class, () -> sync.executeDelete(Collections.singletonList(row(2))));
        sql(target, "INSERT INTO " + name() + "(ts,id,note) VALUES(" + epoch() + ",1,'external')");
        int before = writes.get(); assertThrows(UnconfirmedWriteException.class, () -> sync(store, "owner_restarted_02", true, LIMITS));
        assertEquals(before, writes.get());
    }
    @Test void droppedAndRecreatedTargetCannotRefreshThePersistedPhysicalPin() throws Exception {
        var sync = sync(store, "owner_initial_0001", false, LIMITS); fault.set("DELETE");
        assertThrows(UnconfirmedWriteException.class, () -> sync.executeDelete(Collections.singletonList(row(2))));
        sql(target, "DROP TABLE " + name()); create(); int before = writes.get();
        assertThrows(IllegalStateException.class, () -> sync(store, "owner_restarted_02", true, LIMITS));
        assertEquals(before, writes.get()); assertRows();
    }
    @Test void runtimeProviderReceivesNativeLogicalAndPhysicalIdentityAfterDiscovery() throws Exception {
        var calls = new AtomicInteger(); var sync = new TdengineDatabaseSyncImpl(target); sync.configure(OPTIONS); sync.configureBatchLimits(LIMITS);
        sync.bindGroupRewriteProvider((schema, table, logical, discovered, structure) -> {
            assertEquals(database, schema); assertEquals("rows", table); assertEquals(physical, discovered);
            assertTrue(logical.matches("[0-9a-f]{64}")); assertTrue(structure.matches("[0-9a-f]{64}")); assertNotEquals(logical, discovered);
            calls.incrementAndGet(); return binding(store, "owner_provider_001", false, LIMITS);
        });
        sync.prepare(database, "rows", FIELDS, KEYS); assertEquals(1, calls.get());
        assertEquals(1, sync.executeDelete(Collections.singletonList(row(2)))); assertRows(1, 3);
    }
    @Test void sameUidAlterDuringAdmissionCannotPairOldCodecsWithANewStructureBaseline() throws Exception {
        var sync = new TdengineDatabaseSyncImpl(target); sync.configure(OPTIONS); sync.configureBatchLimits(LIMITS);
        sync.bindGroupRewriteProvider((schema, table, logical, discovered, structure) -> {
            try { sql(target, "ALTER TABLE " + name() + " MODIFY COLUMN note NCHAR(120)"); }
            catch (Exception failure) { throw new AssertionError(failure); }
            return binding(store, "owner_provider_001", false, LIMITS);
        });
        assertThrows(IllegalStateException.class, () -> sync.prepare(database, "rows", FIELDS, KEYS));
        try (var c = target.getConnection()) { assertEquals(physical, TdengineNativeTables.recoveryIdentity(c, database, "rows", 15).physical()); }
        assertEquals(0, writes.get()); assertRows(1, 2, 3);
        assertTrue(store.pendingKeys(scope(), physical, null, 10).keys().isEmpty());
    }
    @Test void deletingEveryCompositeKeyLeavesAnAuthenticatedEmptyAfterImage() throws Exception {
        var sync = sync(store, "owner_initial_0001", false, LIMITS);
        assertEquals(3, sync.executeDelete(List.of(row(1), row(2), row(3)))); assertRows();
        var record = onlyRecord(); assertEquals(0, record.material().afterCount()); assertEquals(GroupRewriteRecord.Phase.APPLIED, record.phase());
    }
    @Test void byteStringCompositeKeysKeepQuotesBackslashesAndChineseDistinct() throws Exception {
        String table = "text_keys";
        sql(target, "CREATE TABLE " + database + "." + table + "(ts TIMESTAMP,id VARCHAR(40) COMPOSITE KEY,amount DECIMAL(38,9),u BIGINT UNSIGNED,raw VARBINARY(16),note NCHAR(100))");
        Object[] first = row(1), second = row(2); first[1] = "中文'\\"; second[1] = "中文'\\survivor";
        var writer = new TdengineWriterImpl(target); writer.configure(OPTIONS); writer.configureBatchLimits(LIMITS); writer.prepareWrite(database, table, FIELDS);
        assertEquals(2, writer.write(FIELDS, List.of(first, second)));
        String pin; try (var c = target.getConnection()) { pin = TdengineNativeTables.recoveryIdentity(c, database, table, 15).physical(); }
        var sync = new TdengineDatabaseSyncImpl(target); sync.configure(OPTIONS); sync.configureBatchLimits(LIMITS);
        sync.bindGroupRewrite(new GroupRewriteBinding(scope(), "a".repeat(64), pin, "owner_textkey_001", store, LIMITS, true,
                GroupRewriteBinding.ResumeMode.NEW_OPERATION_ONLY, () -> {}));
        sync.prepare(database, table, FIELDS, KEYS); assertEquals(1, sync.executeDelete(Collections.singletonList(first)));
        try (var c = target.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT id,amount,raw FROM " + database + "." + table)) {
            assertTrue(r.next()); assertArrayEquals(((String)second[1]).getBytes(java.nio.charset.StandardCharsets.UTF_8), r.getBytes(1));
            assertEquals(second[2], r.getBigDecimal(2)); assertArrayEquals((byte[])second[4], r.getBytes(3)); assertFalse(r.next());
        }
    }
    @Test void lateAcknowledgementOfAppliedPhaseRemainsUnknownUntilExplicitReadReconciliation() throws Exception {
        var uncertain = new ForwardStore(store) {
            @Override public Optional<GroupRewriteRecord> transition(GroupRewriteKey key, String owner, long revision, GroupRewriteRecord.Phase expected, GroupRewriteRecord.Phase next) {
                var result = super.transition(key, owner, revision, expected, next);
                if (next == GroupRewriteRecord.Phase.APPLIED) throw new IllegalStateException("fixture APPLIED commit response lost"); return result;
            }
        };
        var sync = sync(uncertain, "owner_initial_0001", false, LIMITS);
        assertThrows(UnconfirmedWriteException.class, () -> sync.executeDelete(Collections.singletonList(row(2))));
        assertRows(1, 3); assertEquals(GroupRewriteRecord.Phase.APPLIED, onlyRecord().phase()); int before = writes.get();
        sync(store, "owner_restarted_02", true, LIMITS); assertEquals(before, writes.get()); assertRows(1, 3);
    }
    @Test void publicMigrationDifferenceUsesTrustedProviderAndReplaysWithoutLosingTimestampSiblings() throws Exception {
        sql(target, "CREATE TABLE " + database + ".desired(ts TIMESTAMP,id BIGINT COMPOSITE KEY,amount DECIMAL(38,9),u BIGINT UNSIGNED,raw VARBINARY(16),note NCHAR(100))");
        var writer = new TdengineWriterImpl(target); writer.configure(OPTIONS); writer.configureBatchLimits(LIMITS); writer.prepareWrite(database, "desired", FIELDS);
        writer.write(FIELDS, List.of(row(1), row(3), row(4)));
        var request = new MigrationRequest(); var input = new SourceDataSourceProperties();
        input.setSourceProductType(ProductTypeEnum.TDENGINE); input.setDriverClassName("com.taosdata.jdbc.ws.WebSocketDriver");
        input.setUrl(System.getProperty("datahub.test.tdengine.ws.url")); input.setUsername("root"); input.setPassword(System.getenv("DBSWITCH_TEST_TDENGINE_PASSWORD"));
        input.setSourceSchema(database); input.setSourceIncludes("desired"); input.setPoolSize(2); input.setFetchSize(2);
        input.setRegexTableMapper(List.of(new PatternMapper("^desired$", "rows"))); request.getSource().add(input);
        var output = request.getTarget(); output.setTargetProductType(ProductTypeEnum.TDENGINE); output.setDriverClassName("com.taosdata.jdbc.ws.WebSocketDriver");
        output.setUrl(input.getUrl()); output.setUsername("root"); output.setPassword(input.getPassword()); output.setTargetSchema(database); output.setTargetTable("rows");
        output.setPoolSize(3); output.setWriterEngineInsert(true); output.setChangeDataSync(true); output.setBatchLimits(LIMITS);
        output.setGroupRewriteBindingProvider((schema, table, logical, discovered, structure) -> {
            assertEquals(database, schema); assertEquals("rows", table); assertEquals(physical, discovered);
            return binding(store, "owner_migration_01", false, LIMITS);
        });
        try (var runtime = DbSwitchRuntime.standalone()) {
            var result = new MigrationService(request, ignored -> {}, null, runtime).run();
            assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString()); assertRows(1, 3, 4);
            assertEquals(GroupRewriteRecord.Phase.APPLIED, onlyRecord().phase());
            assertTrue(new MigrationService(request, ignored -> {}, null, runtime).run().getIfAllSuccess().get()); assertRows(1, 3, 4);
        }
    }
    private TdengineDatabaseSyncImpl sync(GroupRewriteStore journal, String owner, boolean resume, BatchLimits limits) {
        var sync = new TdengineDatabaseSyncImpl(target); sync.configure(OPTIONS); sync.configureBatchLimits(LIMITS);
        sync.bindGroupRewrite(binding(journal, owner, resume, limits));
        sync.prepare(database, "rows", FIELDS, KEYS); return sync;
    }
    private GroupRewriteBinding binding(GroupRewriteStore journal, String owner, boolean resume, BatchLimits limits) {
        return new GroupRewriteBinding(scope(), "a".repeat(64), physical, owner, journal, limits, true,
                resume ? GroupRewriteBinding.ResumeMode.RECONCILE_AFTER_PRIOR_EXECUTOR_EXIT : GroupRewriteBinding.ResumeMode.NEW_OPERATION_ONLY,
                () -> { if (!owned.get()) throw new IllegalStateException("fixture ownership revoked"); });
    }
    private GroupRewriteRecord onlyRecord() throws Exception {
        try (var c = control.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT group_key FROM " + controlSchema + ".journal")) {
            assertTrue(r.next()); var key = new GroupRewriteKey(scope(), physical, r.getString(1)); assertFalse(r.next()); return store.read(key).orElseThrow();
        }
    }
    private void assertRows(int... ids) throws Exception {
        try (var c = target.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + name() + " ORDER BY id")) {
            for (int id : ids) {
                assertTrue(r.next()); var expected = row(id); assertEquals(TS.toInstant(), r.getTimestamp(1).toInstant()); assertEquals(id, r.getLong(2));
                assertEquals(expected[2], r.getBigDecimal(3)); assertEquals(expected[3], new BigInteger(r.getString(4)));
                assertArrayEquals((byte[])expected[4], r.getBytes(5)); assertEquals(expected[5], r.getString(6));
            }
            assertFalse(r.next());
        }
    }
    private Object[] row(int id) { return new Object[]{TS, (long)id, new BigDecimal("12345678901234567890123456789.123456789"),
            new BigInteger("18446744073709551615"), new byte[]{0,-1,(byte)id}, "中文'\\\n😀"}; }
    private void create() throws Exception { sql(target, "CREATE TABLE " + name() + "(ts TIMESTAMP,id BIGINT COMPOSITE KEY,amount DECIMAL(38,9),u BIGINT UNSIGNED,raw VARBINARY(16),note NCHAR(100))"); }
    private String scope() { return GroupRewriteMaterial.sha256(database.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private String name() { return database + ".`rows`"; }
    private static long epoch() { return Math.addExact(Math.multiplyExact(TS.toInstant().getEpochSecond(), 1000000000L), TS.getNanos()); }
    private static void sql(DataSource ds, String sql) throws Exception { try (var c = ds.getConnection(); var s = c.createStatement()) { s.setQueryTimeout(15); s.execute(sql); } }
    private DataSource target() {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Properties p = new Properties(); p.setProperty("user", "root"); p.setProperty("password", System.getenv("DBSWITCH_TEST_TDENGINE_PASSWORD"));
                Connection actual = DriverManager.getConnection(System.getProperty("datahub.test.tdengine.ws.url"), p);
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class}, (proxy, method, args) -> {
                    Object value = invoke(actual, method, args);
                    if (value instanceof Statement statement && (method.getName().equals("createStatement") || method.getName().equals("prepareStatement"))) {
                        String preparedSql = args != null && args.length > 0 && args[0] instanceof String text ? text : null;
                        Class<?> type = value instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                        return Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, (sproxy, called, params) -> {
                            Object result = invoke(statement, called, params);
                            if (called.getName().equals("executeUpdate")) {
                                String sql = preparedSql == null ? (String)params[0] : preparedSql; writes.incrementAndGet();
                                String armed = fault.get(); if (armed != null && sql.startsWith(armed) && fault.compareAndSet(armed, null)) throw new SQLException("fixture sent response lost");
                            }
                            return result;
                        });
                    }
                    return value;
                });
            }
            @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        };
    }
    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private static class ForwardStore implements GroupRewriteStore {
        private final GroupRewriteStore delegate; ForwardStore(GroupRewriteStore delegate) { this.delegate = delegate; }
        @Override public PendingPage pendingKeys(String scope, String resource, String after, int size) { return delegate.pendingKeys(scope,resource,after,size); }
        @Override public Optional<GroupRewriteRecord> read(GroupRewriteKey key) { return delegate.read(key); }
        @Override public GroupRewriteRecord prepare(GroupRewriteKey key, String owner, GroupRewriteMaterial material, long revision) { return delegate.prepare(key,owner,material,revision); }
        @Override public Optional<GroupRewriteRecord> transition(GroupRewriteKey key, String owner, long revision, GroupRewriteRecord.Phase expected, GroupRewriteRecord.Phase next) { return delegate.transition(key,owner,revision,expected,next); }
        @Override public Optional<GroupRewriteRecord> claimRecovery(GroupRewriteKey key, long revision, String owner) { return delegate.claimRecovery(key,revision,owner); }
    }
}

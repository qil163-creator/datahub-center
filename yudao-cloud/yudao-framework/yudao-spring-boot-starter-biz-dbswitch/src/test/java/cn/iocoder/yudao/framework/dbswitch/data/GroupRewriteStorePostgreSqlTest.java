package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.data.recovery.PostgresGroupRewriteStore;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.recovery.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.math.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(40)
@EnabledIfSystemProperty(named="datahub.test.postgres.url", matches="jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+")
class GroupRewriteStorePostgreSqlTest {
    private static final String SCOPE = "a".repeat(64), PHYSICAL = "b".repeat(64), STRUCTURE = "c".repeat(64), BINDING = "d".repeat(64);
    private static final String OWNER = "fixture_owner_0001", NEXT_OWNER = "fixture_owner_0002";
    private static final BatchLimits LIMITS = new BatchLimits(20, 32768, 8192, 100, 1000);
    private static final List<String> FIELDS = List.of("ts", "key", "amount", "unsigned", "bytes", "text");
    private String schema, relation; private DataSource source; private SecretKey encryptionKey;
    @BeforeEach void setup() throws Exception {
        source = new DriverManagerDataSource(System.getProperty("datahub.test.postgres.url"), System.getProperty("datahub.test.postgres.user", "dataforge_test"), "");
        schema = "group_recovery_" + UUID.randomUUID().toString().replace("-", ""); relation = schema + ".journal";
        byte[] bytes = new byte[32]; new java.security.SecureRandom().nextBytes(bytes); encryptionKey = new SecretKeySpec(bytes, "AES");
        sql("CREATE SCHEMA " + schema); for (String ddl : PostgresGroupRewriteStore.schemaStatements(relation)) sql(ddl);
    }
    @AfterEach void cleanup() throws Exception { if (schema != null) sql("DROP SCHEMA " + schema + " CASCADE"); }
    private PostgresGroupRewriteStore store() { return store(source, SCOPE, "key_v1", Map.of("key_v1", encryptionKey), (c,k) -> {}); }
    private PostgresGroupRewriteStore store(DataSource source, String scope, String active, Map<String, ? extends SecretKey> keys, PostgresGroupRewriteStore.OwnershipGuard guard) {
        return new PostgresGroupRewriteStore(source, relation, scope, active, keys, LIMITS, 10, guard);
    }
    private static GroupRewriteKey key() { return new GroupRewriteKey(SCOPE, PHYSICAL, "e".repeat(64)); }
    private static Object[] row(int key, String text) { return new Object[]{Timestamp.from(Instant.parse("2026-10-03T00:00:00.123456789Z")), key,
            new BigDecimal("12345678901234567890123456789.123456789"), new BigInteger("18446744073709551615"), new byte[]{0,-1,2}, text}; }
    private static GroupRewriteMaterial material(String text) {
        Object[] survivor = row(2, text);
        return GroupRewriteMaterial.create(BINDING, PHYSICAL, STRUCTURE, FIELDS, List.of("ts", "key"), List.of(row(1, text), survivor), Collections.singletonList(survivor), LIMITS);
    }
    @Test void restartedStoreRecoversAuthenticatedExactBeforeAndAfterImages() throws Exception {
        var expected = material("中文'\\\n精确"); var created = store().prepare(key(), OWNER, expected, -1);
        assertEquals(0, created.revision()); assertEquals(expected.hash(), store().read(key()).orElseThrow().material().hash());
        var restored = store().read(key()).orElseThrow().material();
        assertEquals(row(1, "")[0], restored.before().getFirst()[0]); assertEquals(row(1, "")[2], restored.before().getFirst()[2]);
        assertEquals(row(1, "")[3], restored.before().getFirst()[3]); assertArrayEquals(new byte[]{0,-1,2}, (byte[])restored.before().getFirst()[4]);
        assertEquals("中文'\\\n精确", restored.before().getFirst()[5]); assertEquals(1, restored.logicalDeletes());
        try (var c = source.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT payload FROM " + relation)) {
            assertTrue(r.next()); assertFalse(Arrays.equals(expected.encoded(), r.getBytes(1)), "Database stores authenticated ciphertext, not plain rows");
        }
        sql("UPDATE " + relation + " SET created_at='2000-01-01'::timestamptz,updated_at='2000-01-01'::timestamptz");
        assertEquals(GroupRewriteRecord.Phase.PREPARED, store().read(key()).orElseThrow().phase(), "A clock/lease age never expires unresolved recovery material");
    }
    @Test void exactPrepareRetryIsIdempotentButDifferentPayloadAndLiveOwnerTakeoverAreRejected() {
        var expected = material("first"); var store = store();
        assertEquals(0, store.prepare(key(), OWNER, expected, -1).revision());
        assertEquals(0, store.prepare(key(), OWNER, expected, -1).revision());
        assertThrows(IllegalStateException.class, () -> store.prepare(key(), OWNER, material("changed"), -1));
        assertThrows(IllegalStateException.class, () -> store.prepare(key(), NEXT_OWNER, expected, -1));
        assertThrows(IllegalStateException.class, () -> store.prepare(key(), OWNER, expected, 999));
        assertEquals(expected.hash(), store.read(key()).orElseThrow().material().hash());
    }
    @Test void phaseCasHasOneWinnerAndOldOwnerCannotAdvanceAfterExplicitRecoveryClaim() throws Exception {
        var store = store(); store.prepare(key(), OWNER, material("before"), -1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1); List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) futures.add(executor.submit(() -> { start.await(); return store().transition(key(), OWNER, 0,
                    GroupRewriteRecord.Phase.PREPARED, GroupRewriteRecord.Phase.DELETE_INTENT).isPresent(); }));
            start.countDown(); int winners = 0; for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners);
        }
        var owned = store.claimRecovery(key(), 1, NEXT_OWNER).orElseThrow(); assertEquals(2, owned.revision());
        assertTrue(store.transition(key(), OWNER, 2, GroupRewriteRecord.Phase.DELETE_INTENT, GroupRewriteRecord.Phase.RESTORE_INTENT).isEmpty());
        assertTrue(store.claimRecovery(key(), 1, OWNER).isEmpty());
        var restoring = store.transition(key(), NEXT_OWNER, 2, GroupRewriteRecord.Phase.DELETE_INTENT, GroupRewriteRecord.Phase.RESTORE_INTENT).orElseThrow();
        var applied = store.transition(key(), NEXT_OWNER, restoring.revision(), GroupRewriteRecord.Phase.RESTORE_INTENT, GroupRewriteRecord.Phase.APPLIED).orElseThrow();
        assertTrue(store.claimRecovery(key(), applied.revision(), OWNER).isEmpty());
        assertEquals(applied.revision() + 1, store.prepare(key(), OWNER, material("next generation"), applied.revision()).revision());
    }
    @Test void tenantScopeCannotReadOrChangeAnotherScopeAndPendingPhysicalGroupIsGloballyExclusive() {
        var store = store(); store.prepare(key(), OWNER, material("owner one"), -1);
        var monitored = spy(source); var foreign = store(monitored, "f".repeat(64), "key_v1", Map.of("key_v1", encryptionKey), (c,k) -> {});
        assertThrows(IllegalArgumentException.class, () -> foreign.read(key())); verifyNoInteractions(monitored);
        var otherKey = new GroupRewriteKey("f".repeat(64), PHYSICAL, key().group());
        assertTrue(foreign.read(otherKey).isEmpty());
        assertThrows(IllegalStateException.class, () -> foreign.prepare(otherKey, NEXT_OWNER, material("other owner"), -1));
        assertEquals(OWNER, store.read(key()).orElseThrow().ownerToken());
    }
    @Test void ownershipGuardAndSmallCapacityFailBeforeDurableIntent() {
        var rejected = store(source, SCOPE, "key_v1", Map.of("key_v1", encryptionKey), (c,k) -> { throw new SQLException("synthetic ownership revoked"); });
        var error = assertThrows(IllegalStateException.class, () -> rejected.prepare(key(), OWNER, material("data"), -1));
        assertNull(error.getCause()); assertTrue(store().read(key()).isEmpty());
        var monitored = spy(source);
        var small = new PostgresGroupRewriteStore(monitored, relation, SCOPE, "key_v1", Map.of("key_v1", encryptionKey),
                new BatchLimits(20, 100, 100, 100, 1000), 10, (c,k) -> {});
        assertThrows(IllegalArgumentException.class, () -> small.prepare(key(), OWNER, material("data"), -1)); verifyNoInteractions(monitored);
    }
    @Test void tamperedCiphertextWrongKeyAndMissingRotationKeyRejectRecovery() throws Exception {
        store().prepare(key(), OWNER, material("protected"), -1);
        byte[] other = new byte[32]; new java.security.SecureRandom().nextBytes(other); var otherKey = new SecretKeySpec(other, "AES");
        assertThrows(IllegalStateException.class, () -> store(source, SCOPE, "key_v1", Map.of("key_v1", otherKey), (c,k) -> {}).read(key()));
        assertThrows(IllegalStateException.class, () -> store(source, SCOPE, "key_v2", Map.of("key_v2", otherKey), (c,k) -> {}).read(key()));
        assertEquals(2, store(source, SCOPE, "key_v2", Map.of("key_v1", encryptionKey, "key_v2", otherKey), (c,k) -> {}).read(key()).orElseThrow().material().beforeCount());
        sql("UPDATE " + relation + " SET payload=set_byte(payload,0,get_byte(payload,0)#1)");
        assertThrows(IllegalStateException.class, () -> store().read(key()));
    }
    @Test void commitResponseLossLeavesReadablePreparedMaterialWithoutInventingFailureAbsence() {
        var armed = new AtomicBoolean(true);
        var uncertain = store(commitFault(source, armed), SCOPE, "key_v1", Map.of("key_v1", encryptionKey), (c,k) -> {});
        assertThrows(IllegalStateException.class, () -> uncertain.prepare(key(), OWNER, material("committed"), -1));
        assertFalse(armed.get()); var recovered = store().read(key()).orElseThrow();
        assertEquals(GroupRewriteRecord.Phase.PREPARED, recovered.phase()); assertEquals(material("committed").hash(), recovered.material().hash());
        assertEquals(recovered.revision(), store().prepare(key(), OWNER, material("committed"), -1).revision());
    }
    @Test void invalidPhaseJumpsAndStaleRevisionNeverAlterDurableState() {
        var store = store(); store.prepare(key(), OWNER, material("state"), -1);
        assertThrows(IllegalArgumentException.class, () -> store.transition(key(), OWNER, 0, GroupRewriteRecord.Phase.PREPARED, GroupRewriteRecord.Phase.APPLIED));
        assertTrue(store.transition(key(), OWNER, 100, GroupRewriteRecord.Phase.PREPARED, GroupRewriteRecord.Phase.DELETE_INTENT).isEmpty());
        assertEquals(0, store.read(key()).orElseThrow().revision());
    }
    @Test void pendingKeysetScanIsBoundedAndIncludesWorkAfterAnAppliedOnlyPage() {
        var store = store(); var first = new GroupRewriteKey(SCOPE, PHYSICAL, "1".repeat(64));
        var second = new GroupRewriteKey(SCOPE, PHYSICAL, "2".repeat(64));
        store.prepare(first, OWNER, material("one"), -1); store.prepare(second, OWNER, material("two"), -1);
        store.transition(first, OWNER, 0, GroupRewriteRecord.Phase.PREPARED, GroupRewriteRecord.Phase.DELETE_INTENT);
        store.transition(first, OWNER, 1, GroupRewriteRecord.Phase.DELETE_INTENT, GroupRewriteRecord.Phase.RESTORE_INTENT);
        store.transition(first, OWNER, 2, GroupRewriteRecord.Phase.RESTORE_INTENT, GroupRewriteRecord.Phase.APPLIED);
        var page = store.pendingKeys(SCOPE, PHYSICAL, null, 1); assertTrue(page.keys().isEmpty()); assertEquals(first.group(), page.nextAfter());
        page = store.pendingKeys(SCOPE, PHYSICAL, page.nextAfter(), 1); assertEquals(List.of(second), page.keys());
        page = store.pendingKeys(SCOPE, PHYSICAL, page.nextAfter(), 1); assertTrue(page.keys().isEmpty()); assertNull(page.nextAfter());
        assertThrows(IllegalArgumentException.class, () -> store.pendingKeys("f".repeat(64), PHYSICAL, null, 1));
        assertThrows(IllegalArgumentException.class, () -> store.pendingKeys(SCOPE, PHYSICAL, null, 257));
    }
    @Test void damagedStateCannotHidePendingWorkOrBeLaunderedByAnyCas() throws Exception {
        for (String damage : List.of("phase='APPLIED'", "owner_token='fixture_forged_01'", "revision=9", "material_hash='" + "0".repeat(64) + "'")) {
            sql("DELETE FROM " + relation); store().prepare(key(), OWNER, material("protected state"), -1);
            sql("UPDATE " + relation + " SET " + damage);
            assertThrows(IllegalStateException.class, () -> store().read(key()));
            assertThrows(IllegalStateException.class, () -> store().pendingKeys(SCOPE, PHYSICAL, null, 10));
            assertThrows(IllegalStateException.class, () -> store().prepare(key(), OWNER, material("replacement"), 0));
            assertThrows(IllegalStateException.class, () -> store().transition(key(), OWNER, 0, GroupRewriteRecord.Phase.PREPARED, GroupRewriteRecord.Phase.DELETE_INTENT));
            assertThrows(IllegalStateException.class, () -> store().claimRecovery(key(), 0, NEXT_OWNER));
        }
    }
    @Test void platformSchemaHyphenIsQuotedAndSqlFragmentsRemainRejected() {
        assertTrue(PostgresGroupRewriteStore.schemaStatements("datahub-center.dh_dataforge_access_group_recovery").getFirst()
                .startsWith("CREATE TABLE \"datahub-center\".\"dh_dataforge_access_group_recovery\""));
        for (String invalid : List.of("\"datahub-center\".journal", "x.y.z", "x;DROP SCHEMA y", "x.y--\n", "x.\"y\""))
            assertThrows(IllegalArgumentException.class, () -> PostgresGroupRewriteStore.schemaStatements(invalid));
    }
    @Test void nextGenerationPrepareIsIdempotentAfterItsCommitResponseIsLost() {
        var store = store(); store.prepare(key(), OWNER, material("first"), -1);
        store.transition(key(), OWNER, 0, GroupRewriteRecord.Phase.PREPARED, GroupRewriteRecord.Phase.DELETE_INTENT);
        store.transition(key(), OWNER, 1, GroupRewriteRecord.Phase.DELETE_INTENT, GroupRewriteRecord.Phase.RESTORE_INTENT);
        store.transition(key(), OWNER, 2, GroupRewriteRecord.Phase.RESTORE_INTENT, GroupRewriteRecord.Phase.APPLIED);
        var armed = new AtomicBoolean(true); var uncertain = store(commitFault(source, armed), SCOPE, "key_v1", Map.of("key_v1", encryptionKey), (c,k) -> {});
        assertThrows(IllegalStateException.class, () -> uncertain.prepare(key(), NEXT_OWNER, material("next"), 3));
        assertEquals(4, store.prepare(key(), NEXT_OWNER, material("next"), 3).revision());
        assertThrows(IllegalStateException.class, () -> store.prepare(key(), OWNER, material("next"), 3));
        assertThrows(IllegalStateException.class, () -> store.prepare(key(), NEXT_OWNER, material("different"), 3));
    }
    private void sql(String sql) throws SQLException { try (var c = source.getConnection(); var s = c.createStatement()) { s.setQueryTimeout(10); s.execute(sql); } }
    private static DataSource commitFault(DataSource source, AtomicBoolean armed) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                var real = source.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p,m,a) -> {
                    try { Object result = m.invoke(real, a);
                        if (m.getName().equals("commit") && armed.compareAndSet(true, false)) throw new SQLException("synthetic commit acknowledgement loss");
                        return result;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
            @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        };
    }
}

package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.atomic.*;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
@Timeout(60)
class AtomicBatchPostgreSqlTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    PGSimpleDataSource source;
    IAtomicBatchWriter writer;
    String schema;
    AtomicBatchTarget target;
    final List<String> fields = List.of("batch", "ordinal", "status", "value");

    @BeforeEach void setup() throws Exception {
        source = new PGSimpleDataSource(); source.setURL(PG.getJdbcUrl());
        source.setUser(PG.getUsername()); source.setPassword(PG.getPassword());
        writer = DbSwitchProductRegistry.discover().atomicBatchWriter(source, ProductTypeEnum.POSTGRESQL);
        schema = "atomic_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
        sql("CREATE TABLE " + schema + ".rows (batch text NOT NULL, ordinal bigint NOT NULL, status text NOT NULL,"
                + "value numeric CHECK(value>=0), UNIQUE(batch,ordinal))");
        target = new AtomicBatchTarget(schema, "rows", "batch", "ordinal", "status");
        writer.prepareLedger(schema);
    }

    @Test void durableReceiptDataAndSamePayloadReplayAreOneLogicalCommit() throws Exception {
        writer.prepareLedger(schema);
        writer.preflight(target, fields);
        var request = request("tenant-a", "batch-a", 3);
        var first = writer.commit(request);
        assertEquals(first, writer.commit(request));
        assertEquals(first, writer.findReceipt(target, request.key()).orElseThrow());
        assertEquals(request.payloadHash(), first.payloadHash()); assertEquals(3, first.rowCount());
        assertEquals(3, count("rows")); assertEquals(1, count("dbswitch_batch_receipt_v1"));
        var plan = writer.planReadBatch(target, request.key(), fields, 1, 2);
        assertEquals(List.of("batch-a", 1L), plan.parameters()); assertEquals(first, plan.receipt());
        assertEquals(schema, plan.select().getSchemaName()); assertEquals("rows", plan.select().getTableName());
        assertFalse(plan.select().getNormalizedSql().contains("batch-a"));
        var page = writer.readBatch(target, request.key(), fields, 0, 2);
        assertEquals(List.of(1L, 2L), page.stream().map(row -> row.get("ordinal")).toList());
        assertEquals(List.of(3L), writer.readBatch(target, request.key(), fields, 2, 2).stream().map(row -> row.get("ordinal")).toList());
        assertEquals(new BigDecimal("0"), page.getFirst().get("value"));
        assertTrue(writer.findReceipt(target, new AtomicBatchKey("missing", "batch-a")).isEmpty());
        assertThrows(IllegalStateException.class, () -> writer.readBatch(target, new AtomicBatchKey("missing", "batch-a"), fields, 0, 10));
    }

    @Test void sameKeyDifferentContentOrTargetIsExplicitConflict() throws Exception {
        var original = request("tenant-a", "batch-a", 1); writer.commit(original);
        assertThrows(AtomicBatchConflictException.class, () -> writer.commit(request("tenant-a", "batch-a", 2)));
        sql("CREATE TABLE " + schema + ".other (LIKE " + schema + ".rows INCLUDING ALL)");
        var other = new AtomicBatchTarget(schema, "other", "batch", "ordinal", "status");
        assertThrows(AtomicBatchConflictException.class, () -> writer.findReceipt(other, original.key()));
        assertThrows(AtomicBatchConflictException.class, () -> writer.commit(AtomicBatchRequest.create(other, original.key(), fields, original.rows())));
        assertEquals(1, count("rows")); assertEquals(0, count("other"));
    }

    @Test void competingSameBatchReturnsOneReceiptAndOneCopy() throws Exception {
        var request = request("tenant-a", "concurrent", 100);
        var ready = new CountDownLatch(6); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(6)) {
            var tasks = new ArrayList<Future<AtomicBatchReceipt>>();
            for (int i = 0; i < 6; i++) tasks.add(executor.submit(() -> {
                ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); return writer.commit(request);
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            var receipts = new HashSet<AtomicBatchReceipt>();
            for (var task : tasks) receipts.add(task.get(20, TimeUnit.SECONDS));
            assertEquals(1, receipts.size());
        }
        assertEquals(100, count("rows")); assertEquals(1, count("dbswitch_batch_receipt_v1"));
    }

    @Test void constraintFailureRollsBackBothLedgerAndEveryRow() throws Exception {
        var key = new AtomicBatchKey("scope", "failure");
        var bad = AtomicBatchRequest.create(target, key, fields, List.of(
                new Object[]{"failure", 1L, "PASS", 1}, new Object[]{"failure", 2L, "PASS", -1}));
        assertThrows(UnconfirmedWriteException.class, () -> writer.commit(bad));
        assertEquals(0, count("rows")); assertEquals(0, count("dbswitch_batch_receipt_v1"));
        assertTrue(writer.findReceipt(target, key).isEmpty());
        assertEquals(2, writer.commit(request("scope", "failure", 2)).rowCount());
    }

    @Test void lostCommitResponseIsReconciledWithoutReinsertion() throws Exception {
        var request = request("scope", "lost-response", 2);
        var fault = new PostgresAtomicBatchWriter(faultSource("commit"));
        var failure = assertThrows(UnconfirmedWriteException.class, () -> fault.commit(request));
        assertNull(failure.getCause()); assertEquals(0, failure.getSuppressed().length);
        assertFalse(failure.getMessage().contains("fixture-private"));
        var receipt = writer.findReceipt(target, request.key()).orElseThrow();
        assertEquals(receipt, writer.commit(request)); assertEquals(2, count("rows"));
    }

    @Test void lostBatchResponseRollsBackAndDoesNotPublishReceipt() throws Exception {
        var request = request("scope", "lost-batch", 2);
        assertThrows(UnconfirmedWriteException.class, () -> new PostgresAtomicBatchWriter(faultSource("batch")).commit(request));
        assertTrue(writer.findReceipt(target, request.key()).isEmpty()); assertEquals(0, count("rows"));
    }

    @Test void exactTargetMetadataAndUniqueConstraintAreRequiredWithoutAutoAlter() throws Exception {
        sql("CREATE TABLE " + schema + ".bad (batch text NOT NULL,ordinal bigint NOT NULL,status text NOT NULL,value int)");
        var bad = new AtomicBatchTarget(schema, "bad", "batch", "ordinal", "status");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> writer.preflight(bad, fields)).getMessage().contains("UNIQUE"));
        sql("CREATE UNIQUE INDEX bad_partial ON " + schema + ".bad(batch,ordinal) WHERE value>0");
        assertThrows(IllegalArgumentException.class, () -> writer.preflight(bad, fields));
        sql("ALTER TABLE " + schema + ".bad ADD UNIQUE(batch,ordinal)");
        writer.preflight(bad, fields);
        assertThrows(IllegalArgumentException.class, () -> writer.preflight(target, List.of("batch", "ordinal", "status", "missing")));
        sql("ALTER TABLE " + schema + ".bad ALTER COLUMN status DROP NOT NULL");
        assertThrows(IllegalArgumentException.class, () -> writer.preflight(bad, fields));
        assertEquals(0, count("dbswitch_batch_receipt_v1"));
    }

    @Test void identifiersAndValuesAreNotSqlFragments() throws Exception {
        String weirdTable = "odd\";drop";
        sql("CREATE TABLE " + schema + ".\"odd\"\";drop\" (batch text NOT NULL,ordinal bigint NOT NULL,status text NOT NULL,value text,UNIQUE(batch,ordinal))");
        var odd = new AtomicBatchTarget(schema, weirdTable, "batch", "ordinal", "status");
        var key = new AtomicBatchKey("scope", "x' OR '1'='1");
        var request = AtomicBatchRequest.create(odd, key, fields, List.<Object[]>of(new Object[]{key.batchKey(), 1L, "PASS", "'); DROP TABLE rows;--"}));
        assertEquals(1, writer.commit(request).rowCount());
        assertEquals("'); DROP TABLE rows;--", writer.readBatch(odd, key, fields, 0, 1).getFirst().get("value"));
        assertEquals(0, count("rows"));
    }

    @Test void changedPhysicalTargetCannotReuseOldReceipt() throws Exception {
        var request = request("scope", "before-truncate", 1); writer.commit(request);
        sql("TRUNCATE " + schema + ".rows");
        assertThrows(IllegalStateException.class, () -> writer.findReceipt(target, request.key()));
        assertThrows(AtomicBatchConflictException.class, () -> writer.commit(request));
        assertEquals(0, count("rows"));
    }

    @Test void independentScopesDoNotExposeAnotherScopesRowsAndEmptyBatchHasReceipt() throws Exception {
        var a = writer.commit(request("scope-a", "a", 1));
        var b = writer.commit(request("scope-b", "b", 1));
        assertNotEquals(a.receiptId(), b.receiptId());
        assertTrue(writer.findReceipt(target, new AtomicBatchKey("scope-b", "a")).isEmpty());
        var empty = request("scope-a", "empty", 0);
        assertEquals(0, writer.commit(empty).rowCount());
        assertEquals(writer.commit(empty), writer.findReceipt(target, empty.key()).orElseThrow());
        assertTrue(writer.readBatch(target, empty.key(), fields, 0, 1).isEmpty());
        assertEquals(2, count("rows")); assertEquals(3, count("dbswitch_batch_receipt_v1"));
    }

    @Test void literalAuthorizationPlanRetainsBoundedBatchPredicateThroughPostgresAst() throws Exception {
        var request = request("scope", "a".repeat(64), 3); writer.commit(request);
        String sql = writer.planReadBatch(target, request.key(), fields, 1, 1).authorizationSql();
        try (var connection = source.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next()); assertEquals(2L, rows.getLong("ordinal")); assertFalse(rows.next());
        }
        var opaque = request("scope", "not-a-hash", 1); writer.commit(opaque);
        assertThrows(IllegalArgumentException.class, () -> writer.planReadBatch(target, opaque.key(), fields, 0, 1).authorizationSql());
    }

    @Test void replacementKeepsOriginalHistoryAndChangesOnlyTheExactBatchWithStableOperationReceipt() throws Exception {
        var original = request("scope", "repair", 1000); var first = writer.commit(original);
        writer.commit(request("other-scope", "other-batch", 7));
        var replacement = replacement(original, original.payloadHash(), "repair-1", 1000, 1000);
        var repaired = writer.replace(replacement);
        assertEquals(repaired, writer.replace(replacement));
        assertEquals(first, writer.findReceipt(target, original.key()).orElseThrow());
        assertEquals(repaired, writer.findCurrentReceipt(target, original.key()).orElseThrow());
        assertEquals(repaired, writer.findOperationReceipt(target, original.key(), "repair-1").orElseThrow());
        assertEquals(first, writer.commit(original), "repeating the original commit cannot undo a replacement");
        assertEquals(1007, count("rows")); assertEquals(1, count("dbswitch_batch_operation_v1"));
        assertEquals(new BigDecimal("1000"), writer.readBatch(target, original.key(), fields, 0, 1).getFirst().get("value"));
        var second = replacement(original, repaired.payloadHash(), "repair-2", 999, 2000);
        var latest = writer.replace(second);
        assertEquals(repaired, writer.replace(replacement), "historical operation retry returns its immutable receipt");
        assertEquals(latest, writer.findCurrentReceipt(target, original.key()).orElseThrow());
        assertEquals(1006, count("rows")); assertEquals(2, count("dbswitch_batch_operation_v1"));
    }

    @Test void staleExpectedHashOrChangedOperationContentCannotChangeRows() throws Exception {
        var original = request("scope", "repair", 2); writer.commit(original);
        var replacement = replacement(original, original.payloadHash(), "repair-1", 2, 10);
        var repaired = writer.replace(replacement);
        assertThrows(AtomicBatchStaleException.class, () -> writer.replace(replacement(original, original.payloadHash(), "stale", 2, 20)));
        assertThrows(AtomicBatchConflictException.class, () -> writer.replace(replacement(original, original.payloadHash(), "repair-1", 3, 20)));
        assertThrows(AtomicBatchConflictException.class, () -> writer.replace(new AtomicBatchReplacement(target, original.key(),
                repaired.payloadHash(), "repair-1", replacement.replacement())));
        assertEquals(repaired, writer.findCurrentReceipt(target, original.key()).orElseThrow());
        assertEquals(2, count("rows")); assertEquals(1, count("dbswitch_batch_operation_v1"));
    }

    @Test void replacementFailureAfterDeleteRollsBackOldRowsHeadAndOperationLedger() throws Exception {
        var original = request("scope", "repair", 2); var first = writer.commit(original);
        var invalid = AtomicBatchRequest.create(target, original.key(), fields, List.of(
                new Object[]{"repair", 1, "PASS", 5}, new Object[]{"repair", 2, "PASS", -1}));
        assertThrows(UnconfirmedWriteException.class, () -> writer.replace(new AtomicBatchReplacement(target, original.key(),
                original.payloadHash(), "failing-repair", invalid)));
        assertEquals(first, writer.findCurrentReceipt(target, original.key()).orElseThrow());
        assertTrue(writer.findOperationReceipt(target, original.key(), "failing-repair").isEmpty());
        assertEquals(List.of(new BigDecimal("0"), new BigDecimal("1")), writer.readBatch(target, original.key(), fields, 0, 10)
                .stream().map(row -> row.get("value")).toList());
        assertEquals(0, count("dbswitch_batch_operation_v1"));
    }

    @Test void lostReplacementCommitResponseIsQueriedByOperationWithoutDeletingAgain() throws Exception {
        var original = request("scope", "repair", 2); writer.commit(original);
        var replacement = replacement(original, original.payloadHash(), "repair-unknown", 3, 10);
        assertThrows(UnconfirmedWriteException.class, () -> new PostgresAtomicBatchWriter(faultSource("commit")).replace(replacement));
        var receipt = writer.findOperationReceipt(target, original.key(), "repair-unknown").orElseThrow();
        assertEquals(receipt, writer.replace(replacement)); assertEquals(3, count("rows"));
        assertEquals(1, count("dbswitch_batch_operation_v1"));
    }

    @Test void competingRepairsUseExpectedHashCasAndOnlyOneWins() throws Exception {
        var original = request("scope", "repair", 2); writer.commit(original);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<Future<String>>();
            for (int i = 0; i < 2; i++) {
                int number = i;
                tasks.add(executor.submit(() -> {
                    start.await();
                    try { writer.replace(replacement(original, original.payloadHash(), "repair-" + number, 2, number + 10)); return "committed"; }
                    catch (AtomicBatchStaleException stale) { return "stale"; }
                }));
            }
            start.countDown();
            var outcomes = new HashSet<String>(); for (var task : tasks) outcomes.add(task.get(20, TimeUnit.SECONDS));
            assertEquals(Set.of("committed", "stale"), outcomes);
        }
        assertEquals(2, count("rows")); assertEquals(1, count("dbswitch_batch_operation_v1"));
    }

    @Test void missingBatchOrCrossScopeReplacementDoesNotDeleteAnotherScopesData() throws Exception {
        var original = request("scope", "repair", 2); var receipt = writer.commit(original);
        var other = request("other-scope", "repair", 1);
        assertThrows(IllegalArgumentException.class, () -> writer.replace(new AtomicBatchReplacement(target, other.key(),
                original.payloadHash(), "cross-scope", other)));
        assertTrue(writer.findOperationReceipt(target, other.key(), "cross-scope").isEmpty());
        assertEquals(receipt, writer.findCurrentReceipt(target, original.key()).orElseThrow()); assertEquals(2, count("rows"));
    }

    @Test void statusAllowlistFiltersBeforeLimitAndCursorInBothBoundAndLiteralPlans() throws Exception {
        var key = new AtomicBatchKey("scope", "c".repeat(64));
        writer.commit(AtomicBatchRequest.create(target, key, fields, List.of(
                new Object[]{key.batchKey(), 1, "REJECT", 1}, new Object[]{key.batchKey(), 2, "PASS", 2},
                new Object[]{key.batchKey(), 3, "WARN", 3}, new Object[]{key.batchKey(), 4, "REJECT", 4})));
        var plan = writer.planReadBatch(target, key, fields, 0, 1, Set.of("PASS", "WARN"));
        assertEquals(List.of(key.batchKey(), 0L, "PASS", "WARN"), plan.parameters());
        try (var connection = source.getConnection(); var statement = connection.prepareStatement(plan.select().getNormalizedSql())) {
            for (int i = 0; i < plan.parameters().size(); i++) statement.setObject(i + 1, plan.parameters().get(i));
            try (var rows = statement.executeQuery()) { assertTrue(rows.next()); assertEquals(2L, rows.getLong("ordinal")); assertFalse(rows.next()); }
        }
        String next = writer.planReadBatch(target, key, fields, 2, 1, Set.of("PASS", "WARN")).authorizationSql();
        try (var connection = source.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery(next)) {
            assertTrue(rows.next()); assertEquals(3L, rows.getLong("ordinal")); assertFalse(rows.next());
        }
        assertThrows(IllegalArgumentException.class, () -> writer.planReadBatch(target, key, fields, 0, 1, Set.of("PASS') OR true--")));
    }

    @ParameterizedTest @ValueSource(strings = {"UPDATE", "DELETE", "INSERT", "UNSELECTED"})
    void unmanagedMutationCannotBeOverwrittenOrConsumedUsingOldQualityEvidence(String mutation) throws Exception {
        sql("ALTER TABLE " + schema + ".rows ADD COLUMN outside_payload text DEFAULT 'original'");
        var original = request("scope", "materialized", 2); var receipt = writer.commit(original);
        assertNotNull(receipt.materializedHash());
        switch (mutation) {
            case "UPDATE" -> sql("UPDATE " + schema + ".rows SET value=99 WHERE ordinal=1");
            case "DELETE" -> sql("DELETE FROM " + schema + ".rows WHERE ordinal=1");
            case "INSERT" -> sql("INSERT INTO " + schema + ".rows(batch,ordinal,status,value) VALUES('materialized',3,'PASS',3)");
            default -> sql("UPDATE " + schema + ".rows SET outside_payload='external'");
        }
        long externalCount = count("rows");
        assertThrows(RuntimeException.class, () -> writer.replace(replacement(original, original.payloadHash(), "repair", 2, 10)));
        assertThrows(RuntimeException.class, () -> writer.readBatch(target, original.key(), fields, 0, 10));
        assertThrows(RuntimeException.class, () -> writer.planReadBatch(target, original.key(), fields, 0, 10));
        assertEquals(receipt, writer.findReceipt(target, original.key()).orElseThrow(), "historical commit evidence remains queryable");
        assertEquals(receipt, writer.commit(original), "historical replay does not blindly rewrite an externally changed target");
        assertEquals(externalCount, count("rows")); assertEquals(0, count("dbswitch_batch_operation_v1"));
    }

    @Test void legacyReceiptWithoutMaterializationIsQueryableButCannotReplaceOrConsume() throws Exception {
        var original = request("scope", "legacy", 2); writer.commit(original);
        sql("UPDATE " + schema + ".dbswitch_batch_receipt_v1 SET materialized_hash=NULL"); writer.prepareLedger(schema);
        assertNull(writer.findReceipt(target, original.key()).orElseThrow().materializedHash());
        assertNull(writer.commit(original).materializedHash());
        assertThrows(RuntimeException.class, () -> writer.replace(replacement(original, original.payloadHash(), "repair", 1, 0)));
        assertThrows(RuntimeException.class, () -> writer.readBatch(target, original.key(), fields, 0, 10));
        assertEquals(2, count("rows"));
    }

    @Test void materializationUsesStoredTimeAndNumericSemanticsWithoutChangingLogicalInputHash() throws Exception {
        sql("ALTER TABLE " + schema + ".rows ADD COLUMN at timestamptz, ADD COLUMN rounded numeric(12,2)");
        var columns = List.of("batch", "ordinal", "status", "value", "at", "rounded");
        var request = AtomicBatchRequest.create(target, new AtomicBatchKey("scope", "typed"), columns,
                Collections.singletonList(new Object[]{"typed", 1L, "PASS", 1, java.time.OffsetDateTime.parse("2026-10-03T12:34:56+08:00"), new BigDecimal("123.456")}));
        // PG metadata exposes timestamptz as JDBC TIMESTAMP; its driver requires the explicit zoned bind type.
        try (var connection = source.getConnection(); var statement = connection.prepareStatement("SELECT ?")) {
            assertThrows(SQLException.class, () -> statement.setObject(1, request.rows().getFirst()[4], Types.TIMESTAMP));
            assertDoesNotThrow(() -> statement.setObject(1, request.rows().getFirst()[4], Types.TIMESTAMP_WITH_TIMEZONE));
        }
        var first = writer.commit(request);
        assertNotEquals(first.payloadHash(), first.materializedHash());
        assertEquals(first, writer.findCurrentReceipt(target, request.key()).orElseThrow());
        assertEquals(1, writer.readBatch(target, request.key(), columns, 0, 10).size());
        var repaired = writer.replace(new AtomicBatchReplacement(target, request.key(), first.payloadHash(), "repair", request));
        assertEquals(first.materializedHash(), repaired.materializedHash());
    }

    @Test void authorizedReadForcesBatchStatusCursorOrderAndLimitEvenWhenRewriteDropsThem() throws Exception {
        var key = new AtomicBatchKey("scope", "a".repeat(64));
        var request = AtomicBatchRequest.create(target, key, fields, List.of(new Object[]{key.batchKey(), 1, "REJECT", 1},
                new Object[]{key.batchKey(), 2, "PASS", 2}, new Object[]{key.batchKey(), 3, "PASS", 3}, new Object[]{key.batchKey(), 4, "WARN", 4}));
        writer.commit(request); writer.commit(request("scope", "other", 2));
        var plan = writer.planReadBatch(target, key, fields, 1, 2, Set.of("PASS"));
        String permissive = "SELECT batch,ordinal,status,value FROM " + schema + ".rows WHERE value>=0 ORDER BY ordinal DESC LIMIT 100 OFFSET 1";
        var rows = writer.readAuthorizedBatch(plan, permissive, request.payloadHash());
        assertEquals(List.of(2L, 3L), rows.stream().map(r -> r.get("ordinal")).toList());
        assertTrue(rows.stream().allMatch(r -> key.batchKey().equals(r.get("batch"))));
        assertThrows(RuntimeException.class, () -> writer.readAuthorizedBatch(plan, "SELECT * FROM " + schema + ".dbswitch_batch_receipt_v1", request.payloadHash()));
        assertThrows(AtomicBatchStaleException.class, () -> writer.readAuthorizedBatch(plan, permissive, "f".repeat(64)));
        sql("UPDATE " + schema + ".rows SET value=88 WHERE batch='" + key.batchKey() + "' AND ordinal=2");
        assertThrows(RuntimeException.class, () -> writer.readAuthorizedBatch(plan, permissive, request.payloadHash()));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void attestationAndConsumptionOrReplacementBlockExternalInsertUpdateDeleteUntilTransactionEnds(boolean replace) throws Exception {
        var original = request("scope", "locked", 2); writer.commit(original);
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var used = new java.util.concurrent.atomic.AtomicBoolean();
        DataSource wrapped = mock(DataSource.class);
        when(wrapped.getConnection()).thenAnswer(ignored -> {
            Connection physical = source.getConnection();
            return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, method, args) -> {
                Object value = invoke(method, physical, args);
                if (method.getName().equals("prepareStatement") && ((String) args[0]).startsWith("SELECT pg_catalog.sha256(pg_catalog.record_send(t))")) {
                    var statement = (PreparedStatement) value;
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (q, m, a) -> {
                        if (m.getName().equals("executeQuery") && used.compareAndSet(false, true)) { locked.countDown(); assertTrue(release.await(15, TimeUnit.SECONDS)); }
                        return invoke(m, statement, a);
                    });
                }
                return value;
            });
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var guarded = new PostgresAtomicBatchWriter(wrapped);
            var work = executor.submit(() -> replace ? guarded.replace(replacement(original, original.payloadHash(), "repair", 2, 10))
                    : guarded.readBatch(target, original.key(), fields, 0, 10));
            try {
                assertTrue(locked.await(10, TimeUnit.SECONDS));
                try (var external = source.getConnection(); var statement = external.createStatement()) {
                    statement.execute("SET lock_timeout='150ms'");
                    for (String sql : List.of("UPDATE " + schema + ".rows SET value=77 WHERE ordinal=1",
                            "DELETE FROM " + schema + ".rows WHERE ordinal=1",
                            "INSERT INTO " + schema + ".rows(batch,ordinal,status,value) VALUES('locked',3,'PASS',3)")) {
                        SQLException failure = assertThrows(SQLException.class, () -> statement.executeUpdate(sql));
                        assertEquals("55P03", failure.getSQLState(), "external writes must be blocked by the materialization transaction lock");
                    }
                }
            } finally { release.countDown(); }
            assertNotNull(work.get(20, TimeUnit.SECONDS));
        }
        assertEquals(2, count("rows"));
    }

    private AtomicBatchReplacement replacement(AtomicBatchRequest original, String expected, String operation, int count, int offset) {
        var rows = new ArrayList<Object[]>();
        for (int i = 0; i < count; i++) rows.add(new Object[]{original.key().batchKey(), i + 1, "PASS", i + offset});
        return new AtomicBatchReplacement(target, original.key(), expected, operation,
                AtomicBatchRequest.create(target, original.key(), fields, rows));
    }

    private AtomicBatchRequest request(String scope, String batch, int count) {
        var rows = new ArrayList<Object[]>();
        for (int i = 0; i < count; i++) rows.add(new Object[]{batch, (long) i + 1, "PASS", i});
        return AtomicBatchRequest.create(target, new AtomicBatchKey(scope, batch), fields, rows);
    }
    private DataSource faultSource(String fault) throws Exception {
        DataSource wrapped = mock(DataSource.class);
        when(wrapped.getConnection()).thenAnswer(ignored -> {
            Connection physical = source.getConnection();
            return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                Object value = invoke(method, physical, args);
                if (method.getName().equals("commit") && fault.equals("commit")) throw new SQLException("fixture-private-commit-response");
                if (method.getName().equals("prepareStatement") && fault.equals("batch")
                        && ((String) args[0]).startsWith("INSERT INTO \"" + schema + "\".\"rows\"")) {
                    var statement = (PreparedStatement) value;
                    return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                        Object result = invoke(m, statement, a);
                        if (m.getName().equals("executeBatch")) throw new SQLException("fixture-private-batch-response");
                        return result;
                    });
                }
                return value;
            });
        });
        return wrapped;
    }
    private static Object invoke(java.lang.reflect.Method method, Object instance, Object[] args) throws Throwable {
        try { return method.invoke(instance, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private void sql(String sql) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private long count(String table) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM " + schema + "." + table)) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }
}

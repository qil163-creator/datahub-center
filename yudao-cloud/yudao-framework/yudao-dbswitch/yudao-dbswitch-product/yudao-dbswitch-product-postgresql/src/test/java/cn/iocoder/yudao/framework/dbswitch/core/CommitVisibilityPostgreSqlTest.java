package cn.iocoder.yudao.framework.dbswitch.core;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ReadSnapshot;
import cn.iocoder.yudao.framework.dbswitch.core.util.PostgresTransactionRoute;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.TransactionCommitReceipt;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitRangeReceipt;
import cn.iocoder.yudao.framework.dbswitch.data.domain.BatchCommitSummary;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
@EnabledIfSystemProperty(named="datahub.test.postgres.url", matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
class CommitVisibilityPostgreSqlTest {
    HikariDataSource pool;
    String schema;
    @BeforeEach void setup() throws Exception {
        pool = new HikariDataSource(); pool.setJdbcUrl(System.getProperty("datahub.test.postgres.url") + "?socketTimeout=5");
        pool.setUsername("dataforge_test"); pool.setMaximumPoolSize(3); pool.setMinimumIdle(0);
        schema = "visibility_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = pool.getConnection(); var sql = c.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema); sql.execute("CREATE TABLE " + schema + ".sample(id int)");
        }
    }
    @AfterEach void cleanup() throws Exception {
        Thread.interrupted();
        if (pool != null) try (var ownedPool = pool; var c = ownedPool.getConnection(); var sql = c.createStatement()) {
            assertTrue(schema.matches("visibility_[a-f0-9]{32}")); sql.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
    private ReadSnapshot snapshot(Connection c) throws SQLException {
        return cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL).openReadSnapshot(c, 5);
    }
    private TransactionCommitReceipt write() {
        var writer = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().writer(pool, ProductTypeEnum.POSTGRESQL, cn.iocoder.yudao.framework.dbswitch.spi.WriteMode.INSERT);
        writer.prepareWrite(schema, "sample", List.of("id"));
        return writer.writeWithReceipt(List.of("id"), List.<Object[]>of(new Object[]{1})).receipt();
    }
    private static CommitRangeReceipt range(TransactionCommitReceipt... receipts) {
        var collector = new BatchCommitSummary.Collector();
        for (var receipt : receipts) collector.add(1, receipt);
        return collector.snapshot().coverage();
    }
    @Test void rangeAfterSnapshotRemainsUnprovenUntilNextSnapshot() throws Exception {
        try (var c = pool.getConnection()) {
            CommitRangeReceipt range;
            try (var snapshot = snapshot(c)) {
                range = range(write(), write());
                assertEquals("RANGE_UNPROVEN", snapshot.inspectCommitRanges(List.of(range)).observations().getFirst().status());
            }
            try (var snapshot = snapshot(c)) {
                var result = snapshot.inspectCommitRanges(List.of(range));
                assertEquals(snapshot.evidence(), result.snapshot());
                assertEquals("RANGE_VISIBLE", result.observations().getFirst().status());
            }
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void visibleEndpointsDoNotHideInProgressMiddleTransaction(boolean abort) throws Exception {
        var first = write();
        try (var middle = pool.getConnection(); var c = pool.getConnection()) {
            middle.setAutoCommit(false);
            try (var sql = middle.createStatement(); var rows = sql.executeQuery("SELECT pg_current_xact_id()")) { assertTrue(rows.next()); }
            var last = write(); var range = range(first, last);
            try (var snapshot = snapshot(c)) {
                assertTrue(snapshot.inspectCommits(List.of(first,last)).observations().stream().allMatch(o -> o.status().equals("VISIBLE")));
                assertEquals("RANGE_UNPROVEN",snapshot.inspectCommitRanges(List.of(range)).observations().getFirst().status());
                if (abort) middle.rollback(); else middle.commit();
                // 同一次读取不能借新状态改写旧快照的活动事务集合。
                assertEquals("RANGE_UNPROVEN",snapshot.inspectCommitRanges(List.of(range)).observations().getFirst().status());
            }
            try (var snapshot = snapshot(c)) {
                assertEquals("RANGE_VISIBLE",snapshot.inspectCommitRanges(List.of(range)).observations().getFirst().status());
            }
            middle.rollback();
        }
    }
    @Test void earlierUnrelatedActiveTransactionDoesNotBlockNonoverlappingRange() throws Exception {
        try (var old = pool.getConnection(); var c = pool.getConnection()) {
            old.setAutoCommit(false);
            try (var sql = old.createStatement(); var rows = sql.executeQuery("SELECT pg_current_xact_id()")) { assertTrue(rows.next()); }
            var range = range(write(), write());
            try (var snapshot = snapshot(c)) {
                assertEquals("RANGE_VISIBLE", snapshot.inspectCommitRanges(List.of(range)).observations().getFirst().status());
            }
            old.rollback();
        }
    }
    @Test void malformedAndUnavailableRangeChecksPreserveSnapshotButNotFalseEvidence() throws Exception {
        var range = range(write());
        var wrong = new CommitRangeReceipt(1,range.protocol(),1,1,range.firstTransactionId(),range.lastTransactionId(),"f".repeat(64),range.digest());
        var future = new CommitRangeReceipt(1,range.protocol(),1,1,"18446744073709551615","18446744073709551615",range.routeFingerprint(),range.digest());
        try (var c = pool.getConnection(); var snapshot = snapshot(c)) {
            assertThrows(IllegalArgumentException.class, () -> snapshot.inspectCommitRanges(List.of()));
            assertThrows(IllegalArgumentException.class, () -> snapshot.inspectCommitRanges(java.util.Collections.nCopies(21,range)));
            assertThrows(IllegalArgumentException.class, () -> snapshot.inspectCommitRanges(List.of(range,range)));
            var error = assertThrows(SQLException.class, () -> snapshot.inspectCommitRanges(List.of(future)));
            assertNull(error.getCause()); assertFalse(error.getMessage().contains(future.firstTransactionId()));
            var result = snapshot.inspectCommitRanges(List.of(wrong,range));
            assertEquals(List.of("ROUTE_MISMATCH","RANGE_VISIBLE"),result.observations().stream().map(ReadSnapshot.RangeObservation::status).toList());
            assertEquals(List.of(wrong,range),result.observations().stream().map(ReadSnapshot.RangeObservation::receipt).toList());
        }
    }
    @Test void committedAfterSnapshotIsNotVisibleUntilNextSnapshot() throws Exception {
        try (var c = pool.getConnection()) {
            TransactionCommitReceipt receipt;
            try (var snapshot = snapshot(c)) {
                receipt = write();
                var observed = snapshot.inspectCommits(List.of(receipt));
                assertEquals(snapshot.evidence(), observed.snapshot());
                assertEquals(receipt, observed.observations().getFirst().receipt());
                assertEquals("NOT_VISIBLE", observed.observations().getFirst().status());
                try (var sql = c.createStatement(); var rows = sql.executeQuery("SELECT count(*) FROM " + schema + ".sample")) {
                    assertTrue(rows.next()); assertEquals(0, rows.getLong(1));
                }
            }
            try (var next = snapshot(c)) {
                assertEquals("VISIBLE", next.inspectCommits(List.of(receipt)).observations().getFirst().status());
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={"commit", "abort", "active"})
    void completedTransactionTestAloneCannotConfuseAbortWithCommit(String outcome) throws Exception {
        try (var writer = pool.getConnection(); var c = pool.getConnection()) {
            writer.setAutoCommit(false);
            TransactionCommitReceipt receipt;
            try (var sql = writer.createStatement(); var rows = sql.executeQuery("SELECT pg_current_xact_id()::text, "
                    + PostgresTransactionRoute.COLUMNS + PostgresTransactionRoute.FROM)) {
                assertTrue(rows.next());
                receipt = new TransactionCommitReceipt(1, "POSTGRESQL_XID8_V1", rows.getString(1),
                        PostgresTransactionRoute.fingerprint(rows, 2));
            }
            if (outcome.equals("commit")) writer.commit();
            if (outcome.equals("abort")) writer.rollback();
            try (var snapshot = snapshot(c)) {
                assertEquals(outcome.equals("commit") ? "VISIBLE" : "NOT_COMMITTED",
                        snapshot.inspectCommits(List.of(receipt)).observations().getFirst().status());
                assertEquals(outcome.equals("commit") ? "RANGE_VISIBLE" : "NOT_COMMITTED",
                        snapshot.inspectCommitRanges(List.of(range(receipt))).observations().getFirst().status());
            }
            writer.rollback();
        }
    }
    @Test void differentRouteCannotBorrowVisibleXidAndKeepsInputOrder() throws Exception {
        var a = write(); var b = write();
        var wrong = new TransactionCommitReceipt(1, a.protocol(), a.transactionId(), "f".repeat(64));
        try (var c = pool.getConnection(); var snapshot = snapshot(c)) {
            var result = snapshot.inspectCommits(List.of(wrong, b));
            assertEquals(List.of("ROUTE_MISMATCH", "VISIBLE"), result.observations().stream().map(ReadSnapshot.CommitObservation::status).toList());
            assertEquals(List.of(wrong, b), result.observations().stream().map(ReadSnapshot.CommitObservation::receipt).toList());
        }
    }
    @Test void futureXidSqlFailureIsSanitizedAndCheckpointKeepsMainSnapshotUsable() throws Exception {
        var receipt = write();
        var future = new TransactionCommitReceipt(1, receipt.protocol(), "18446744073709551615", receipt.routeFingerprint());
        try (var c = pool.getConnection(); var snapshot = snapshot(c)) {
            var failure = assertThrows(SQLException.class, () -> snapshot.inspectCommits(List.of(future)));
            assertNull(failure.getCause()); assertFalse(failure.getMessage().contains(future.transactionId()));
            assertEquals("VISIBLE", snapshot.inspectCommits(List.of(receipt)).observations().getFirst().status());
        }
    }
    @Test void changedAndClosedSnapshotCannotProduceVisibility() throws Exception {
        var receipt = write();
        try (var c = pool.getConnection()) {
            var snapshot = snapshot(c); c.commit();
            assertThrows(SQLException.class, () -> snapshot.inspectCommits(List.of(receipt)));
            assertThrows(SQLException.class, () -> snapshot.inspectCommitRanges(List.of(range(receipt))));
            assertThrows(SQLException.class, snapshot::close);
        }
        try (var c = pool.getConnection()) {
            var snapshot = snapshot(c); snapshot.close();
            assertThrows(SQLException.class, () -> snapshot.inspectCommits(List.of(receipt)));
            assertThrows(SQLException.class, () -> snapshot.inspectCommitRanges(List.of(range(receipt))));
        }
    }
    @Test void emptyOversizedAndDuplicateInputsAreRejectedWithoutEndingSnapshot() throws Exception {
        var receipt = write();
        try (var c = pool.getConnection(); var snapshot = snapshot(c)) {
            assertThrows(IllegalArgumentException.class, () -> snapshot.inspectCommits(List.of()));
            assertThrows(IllegalArgumentException.class, () -> snapshot.inspectCommits(java.util.Collections.nCopies(17, receipt)));
            assertThrows(IllegalArgumentException.class, () -> snapshot.inspectCommits(List.of(receipt, receipt)));
            assertEquals("VISIBLE", snapshot.inspectCommits(List.of(receipt)).observations().getFirst().status());
        }
    }

    @Test void unverifiedVersionPreservesOrdinarySnapshotWithoutBorrowingPg16Proof() throws Exception {
        var receipt = write();
        try (var c = pool.getConnection()) {
            var metadata = org.mockito.Mockito.spy(c.getMetaData());
            org.mockito.Mockito.doReturn(15).when(metadata).getDatabaseMajorVersion();
            var proxy = (Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class[]{Connection.class},(p,m,a)->{
                        if (m.getName().equals("getMetaData")) return metadata;
                        try { return m.invoke(c,a); } catch (java.lang.reflect.InvocationTargetException ex) { throw ex.getCause(); }
                    });
            try (var snapshot = snapshot(proxy)) {
                assertThrows(java.sql.SQLFeatureNotSupportedException.class, () -> snapshot.inspectCommits(List.of(receipt)));
                assertThrows(java.sql.SQLFeatureNotSupportedException.class, () -> snapshot.inspectCommitRanges(List.of(range(receipt))));
                assertNotNull(snapshot.evidence());
            }
            assertTrue(c.getAutoCommit()); assertFalse(c.isReadOnly());
        }
    }
}

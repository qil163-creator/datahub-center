package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Pure JDBC control-flow/contract tests. Native metadata, locking and atomicity need MariaDB tests. */
class MariaDbSourceGenerationTest {

    private static final String SCHEMA = "source_guard";
    private static final String INSTANCE = "10000000-0000-0000-0000-000000000001";
    private static final String G1 = "20000000-0000-0000-0000-000000000001";
    private static final String G2 = "20000000-0000-0000-0000-000000000002";
    private static final String G3 = "20000000-0000-0000-0000-000000000003";
    private static final SourceGenerationPin PIN = new SourceGenerationPin(SCHEMA, INSTANCE, G1);
    private static final String MARKER_SELECT = "SELECT singleton_id, schema_version";
    private static final String HISTORY_SELECT = "SELECT generation, prev_generation";

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "a-b", "a.b", "a`", "a;DROP", " a", "a ", "a\n", "中文", "1a",
            "mysql", "MYSQL", "information_schema", "performance_schema", "sys",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void invalidAndReservedSchemasFailBeforeJdbc(String schema) {
        Connection connection = mock(Connection.class);
        assertThrows(IllegalArgumentException.class, () -> new SourceGenerationPin(schema, INSTANCE, G1));
        assertThrows(IllegalArgumentException.class, () -> MariaDbSourceGeneration.initialize(connection, schema));
        verifyNoInteractions(connection);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "0", "1-1-1-1-1", "00000000-0000-0000-0000-000000000000",
            "20000000-0000-0000-0000-00000000000A", " 20000000-0000-0000-0000-000000000001",
            "20000000-0000-0000-0000-000000000001\n", "20000000000000000000000000000001"})
    void uuidMustBeNonzeroLowercaseCanonical(String value) {
        assertThrows(IllegalArgumentException.class, () -> new SourceGenerationPin(SCHEMA, value, G1));
        assertThrows(IllegalArgumentException.class, () -> new SourceGenerationPin(SCHEMA, INSTANCE, value));
        Connection connection = mock(Connection.class);
        assertThrows(IllegalArgumentException.class, () -> MariaDbSourceGeneration.rotate(connection, PIN, value));
        verifyNoInteractions(connection);
    }

    @Test
    void fingerprintIsVersionedDelimitedAndBindsEveryField() throws Exception {
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                ("DBSWITCH_SOURCE_GENERATION_V1\0" + SCHEMA + "\0" + INSTANCE + "\0" + G1)
                        .getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, PIN.fingerprint());
        assertTrue(PIN.fingerprint().matches("[0-9a-f]{64}"));
        assertEquals(PIN, new SourceGenerationPin(SCHEMA, INSTANCE, G1));
        assertNotEquals(PIN.fingerprint(), new SourceGenerationPin("other_schema", INSTANCE, G1).fingerprint());
        assertNotEquals(PIN.fingerprint(), new SourceGenerationPin(SCHEMA, G2, G1).fingerprint());
        assertNotEquals(PIN.fingerprint(), new SourceGenerationPin(SCHEMA, INSTANCE, G2).fingerprint());
        assertEquals("SourceGenerationPin[redacted]", PIN.toString());
        assertDoesNotThrow(() -> new SourceGenerationPin("_" + "a".repeat(63), INSTANCE, G1));
    }

    @Test
    void runtimeReadOnlyBindsParametersAndClosesBoundedStatements() throws Exception {
        Jdbc fixture = new Jdbc();
        MariaDbSourceGeneration.readVerified(fixture.connection, PIN);
        fixture.assertConnectionUntouched();
        assertTrue(fixture.writes.isEmpty());
        for (Query query : fixture.queries) {
            assertTrue(query.sql().startsWith("SELECT ") || query.sql().startsWith("SHOW CREATE TABLE "));
            assertFalse(query.sql().contains(INSTANCE));
            assertFalse(query.sql().contains(G1));
            verify(query.statement()).setQueryTimeout(5);
            verify(query.statement()).setMaxRows(intThat(value -> value > 0 && value <= 9));
            verify(query.statement()).close();
        }
        assertTrue(fixture.queries.stream().anyMatch(query -> G1.equals(query.values().get(1))));
        assertEquals(2, fixture.queries.stream().filter(query -> query.sql().startsWith(MARKER_SELECT)).count());
    }

    @Test
    void selectOnlyCatalogVisibilityStillRequiresExactChecksAndIndexes() throws Exception {
        Jdbc fixture = new Jdbc();
        // Native MariaDB 11.4 exposes CHECK_CONSTRAINTS and STATISTICS with SELECT while hiding
        // TABLE_CONSTRAINTS. The reader must not need DML grants or accept absent CHECK evidence.
        fixture.overrides.put("SELECT CONSTRAINT_NAME, CONSTRAINT_TYPE", rows());
        MariaDbSourceGeneration.readVerified(fixture.connection, PIN);
        assertTrue(fixture.queries.stream().noneMatch(query -> query.sql().contains("TABLE_CONSTRAINTS")));
        fixture.overrides.put("SELECT CONSTRAINT_NAME, CHECK_CLAUSE", rows());
        assertOrdinarySqlFailure(() -> MariaDbSourceGeneration.readVerified(fixture.connection, PIN));
        fixture.assertConnectionUntouched();
    }

    @Test
    void showCreateRejectsExtraForeignKeyEvenIfOtherCatalogsAreHidden() throws Exception {
        Jdbc fixture = new Jdbc();
        fixture.overrides.put("SHOW CREATE TABLE", rows(row("dbswitch_source_generation",
                "CREATE TABLE dbswitch_source_generation (singleton_id tinyint,"
                        + " CONSTRAINT unexpected FOREIGN KEY (singleton_id) REFERENCES other(id))")));
        assertOrdinarySqlFailure(() -> MariaDbSourceGeneration.readVerified(fixture.connection, PIN));
        fixture.assertConnectionUntouched();
    }

    @Test
    void allEntrypointsRejectJdbcTransactionWithoutTouchingIt() throws Exception {
        Jdbc fixture = new Jdbc();
        when(fixture.connection.getAutoCommit()).thenReturn(false);
        assertEquals("25000", assertThrows(SQLException.class,
                () -> MariaDbSourceGeneration.readVerified(fixture.connection, PIN)).getSQLState());
        assertThrows(SQLException.class, () -> MariaDbSourceGeneration.initialize(fixture.connection, SCHEMA));
        assertThrows(SQLException.class, () -> MariaDbSourceGeneration.fence(fixture.connection, PIN));
        assertThrows(SQLException.class, () -> MariaDbSourceGeneration.rotate(fixture.connection, PIN, G2));
        fixture.assertConnectionUntouched();
        verify(fixture.connection, never()).prepareStatement(anyString());
    }

    @Test
    void sqlStartTransactionWithJdbcAutocommitTrueIsRejectedWithoutCleanup() throws Exception {
        Jdbc fixture = new Jdbc();
        fixture.sessionTransaction = "1";
        SQLException failure = assertThrows(SQLException.class,
                () -> MariaDbSourceGeneration.readVerified(fixture.connection, PIN));
        assertEquals("25000", failure.getSQLState());
        fixture.assertConnectionUntouched();
        assertEquals(1, fixture.queries.size());
        assertThrows(SQLException.class, () -> MariaDbSourceGeneration.initialize(fixture.connection, SCHEMA));
        assertThrows(SQLException.class, () -> MariaDbSourceGeneration.fence(fixture.connection, PIN));
        assertThrows(SQLException.class, () -> MariaDbSourceGeneration.rotate(fixture.connection, PIN, G2));
        fixture.assertConnectionUntouched();
    }

    @ParameterizedTest
    @ValueSource(strings = {"11.3.2-MariaDB", "11.5.1-MariaDB", "8.0.40", "5.7.25-TiDB-v8.5.6"})
    void compatibleOrOtherVersionServersAreUnsupported(String version) throws Exception {
        Jdbc fixture = new Jdbc();
        fixture.version = version;
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> MariaDbSourceGeneration.readVerified(fixture.connection, PIN));
        fixture.assertConnectionUntouched();
    }

    @Test
    void driverProductAndMinorVersionMustAlsoMatch() throws Exception {
        Jdbc fixture = new Jdbc();
        when(fixture.metadata.getDatabaseProductName()).thenReturn("MySQL");
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> MariaDbSourceGeneration.readVerified(fixture.connection, PIN));
        when(fixture.metadata.getDatabaseProductName()).thenReturn("MariaDB");
        when(fixture.metadata.getDatabaseMinorVersion()).thenReturn(5);
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> MariaDbSourceGeneration.readVerified(fixture.connection, PIN));
        assertTrue(fixture.queries.isEmpty());
    }

    @Test
    void absentMarkerIsUnsupportedButPermissionsAndNetworkFailuresKeepTheirCause() throws Exception {
        Jdbc fixture = new Jdbc();
        fixture.markerExists = false;
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> MariaDbSourceGeneration.readVerified(fixture.connection, PIN));
        for (SQLException failure : List.of(new SQLException("test denied", "42000", 1142),
                new SQLException("test disconnected", "08006"))) {
            fixture.queryFailure = failure;
            fixture.failurePrefix = "SHOW CREATE TABLE";
            assertSame(failure, assertThrows(SQLException.class,
                    () -> MariaDbSourceGeneration.readVerified(fixture.connection, PIN)));
        }
        fixture.assertConnectionUntouched();
    }

    @Test
    void staleAndFencedMarkersNeverBecomeUnsupported() throws Exception {
        Jdbc fixture = new Jdbc();
        fixture.state = "FENCED";
        assertOrdinarySqlFailure(() -> MariaDbSourceGeneration.readVerified(fixture.connection, PIN));
        fixture.state = "ACTIVE";
        assertOrdinarySqlFailure(() -> MariaDbSourceGeneration.readVerified(fixture.connection,
                new SourceGenerationPin(SCHEMA, INSTANCE, G2)));
        assertOrdinarySqlFailure(() -> MariaDbSourceGeneration.readVerified(fixture.connection,
                new SourceGenerationPin(SCHEMA, G2, G1)));
        fixture.assertConnectionUntouched();
    }

    @ParameterizedTest
    @ValueSource(strings = {"dbswitch_source_generation", "dbswitch_source_generation_history"})
    void temporaryShadowOfEitherTableIsRejected(String table) throws Exception {
        Jdbc fixture = new Jdbc();
        fixture.temporary = table;
        assertOrdinarySqlFailure(() -> MariaDbSourceGeneration.readVerified(fixture.connection, PIN));
        assertOrdinarySqlFailure(() -> MariaDbSourceGeneration.initialize(fixture.connection, SCHEMA));
        assertTrue(fixture.writes.isEmpty());
    }

    @Test
    void foreignStorageObjectsAndMalformedSchemaAreRejectedWithoutDdl() throws Exception {
        Map<String, List<String[]>> corruptions = new LinkedHashMap<>();
        corruptions.put("SELECT TABLE_TYPE", rows(row("VIEW", "InnoDB", "foreign", "")));
        corruptions.put("SELECT COLUMN_NAME", rows(row("foreign", "int(11)", "NO", null, null, "", "NEVER")));
        corruptions.put("SELECT CONSTRAINT_NAME, CHECK_CLAUSE", rows(row("sg_singleton", "singleton_id > 0")));
        corruptions.put("SELECT INDEX_NAME", rows(row("PRIMARY", "singleton_id", "1", "1", null, "BTREE", "NO")));
        corruptions.put("SELECT TRIGGER_NAME", rows(row("unexpected_trigger")));
        for (var corruption : corruptions.entrySet()) {
            Jdbc fixture = new Jdbc();
            fixture.overrides.put(corruption.getKey(), corruption.getValue());
            assertOrdinarySqlFailure(() -> MariaDbSourceGeneration.initialize(fixture.connection, SCHEMA));
            assertTrue(fixture.writes.isEmpty(), corruption.getKey());
            fixture.assertConnectionUntouched();
        }
    }

    @Test
    void singletonVersionAndStoredUuidAreValidated() throws Exception {
        for (String[] bad : rows(row("2", "1", INSTANCE, G1, "ACTIVE"),
                row("1", "2", INSTANCE, G1, "ACTIVE"), row("1", "1", "bad", G1, "ACTIVE"),
                row("1", "1", INSTANCE, G1, "UNKNOWN"))) {
            Jdbc fixture = new Jdbc();
            fixture.overrides.put(MARKER_SELECT, rows(bad));
            assertOrdinarySqlFailure(() -> MariaDbSourceGeneration.readVerified(fixture.connection, PIN));
        }
        Jdbc fixture = new Jdbc();
        fixture.overrides.put(MARKER_SELECT, rows(row("1", "1", INSTANCE, G1, "ACTIVE"),
                row("1", "1", INSTANCE, G1, "ACTIVE")));
        assertOrdinarySqlFailure(() -> MariaDbSourceGeneration.readVerified(fixture.connection, PIN));
    }

    @Test
    void runtimeRequiresHistoryReceiptAndDetectsFenceDuringRead() throws Exception {
        Jdbc fixture = new Jdbc();
        fixture.history.clear();
        assertOrdinarySqlFailure(() -> MariaDbSourceGeneration.readVerified(fixture.connection, PIN));
        fixture.history.put(G1, row(G1, null, INSTANCE));
        fixture.fenceOnReceipt = true;
        assertOrdinarySqlFailure(() -> MariaDbSourceGeneration.readVerified(fixture.connection, PIN));
        fixture.assertConnectionUntouched();
    }

    @Test
    void initializeRequiresPrecreatedSchemaAndNeverCreatesDatabase() throws Exception {
        Jdbc fixture = new Jdbc();
        fixture.overrides.put("SELECT SCHEMA_NAME", List.of());
        assertOrdinarySqlFailure(() -> MariaDbSourceGeneration.initialize(fixture.connection, SCHEMA));
        assertTrue(fixture.writes.isEmpty());
        fixture.assertConnectionUntouched();
    }

    @Test
    void initializeExistingActiveOrFencedPinIsIdempotentAndDoesNotRewriteData() throws Exception {
        for (String state : List.of("ACTIVE", "FENCED")) {
            Jdbc fixture = new Jdbc();
            fixture.state = state;
            assertEquals(PIN, MariaDbSourceGeneration.initialize(fixture.connection, SCHEMA));
            assertTrue(fixture.writes.isEmpty());
            assertEquals(state, fixture.state);
            fixture.assertSuccessfulOwnedTransaction();
        }
    }

    @Test
    void initializeResumesEmptyPartialDdlAndUsesGeneratedBoundParameters() throws Exception {
        for (boolean markerExists : List.of(false, true)) {
            Jdbc fixture = new Jdbc();
            fixture.markerExists = markerExists;
            fixture.historyExists = false;
            fixture.generation = null;
            fixture.history.clear();
            SourceGenerationPin pin = MariaDbSourceGeneration.initialize(fixture.connection, SCHEMA);
            assertEquals(SCHEMA, pin.schema());
            assertEquals(pin.instanceId(), fixture.instance);
            assertEquals(pin.generation(), fixture.generation);
            assertEquals("ACTIVE", fixture.state);
            assertEquals(1, fixture.history.size());
            assertNull(fixture.history.get(pin.generation())[1]);
            assertEquals(markerExists ? 1 : 2,
                    fixture.writes.stream().filter(query -> query.sql().startsWith("CREATE TABLE")).count());
            assertTrue(fixture.writes.stream().noneMatch(query -> query.sql().contains("CREATE DATABASE")
                    || query.sql().contains("REPLACE") || query.sql().contains("ALTER") || query.sql().contains("DROP")));
            assertTrue(fixture.writes.stream().noneMatch(query -> query.sql().contains(pin.generation())));
            fixture.assertSuccessfulOwnedTransaction();
        }
    }

    @Test
    void initializeRejectsPopulatedHalfInstallationsWithoutCreatingOrRepairingAnything() throws Exception {
        Jdbc fixture = new Jdbc();
        fixture.historyExists = false;
        assertOrdinarySqlFailure(() -> MariaDbSourceGeneration.initialize(fixture.connection, SCHEMA));
        assertTrue(fixture.writes.isEmpty());
        Jdbc missingMarker = new Jdbc();
        missingMarker.markerExists = false;
        assertOrdinarySqlFailure(() -> MariaDbSourceGeneration.initialize(missingMarker.connection, SCHEMA));
        assertTrue(missingMarker.writes.isEmpty());
    }

    @Test
    void initializeRejectsMissingOrphanedCyclicAndWrongInstanceHistory() throws Exception {
        for (List<String[]> history : List.of(rows(), rows(row(G1, G2, INSTANCE)),
                rows(row(G1, null, INSTANCE), row(G2, null, INSTANCE)),
                rows(row(G1, G2, INSTANCE), row(G2, G1, INSTANCE)), rows(row(G1, null, G2)))) {
            Jdbc fixture = new Jdbc();
            fixture.history.clear();
            for (String[] entry : history) fixture.history.put(entry[0], entry);
            assertOrdinarySqlFailure(() -> MariaDbSourceGeneration.initialize(fixture.connection, SCHEMA));
            assertTrue(fixture.writes.isEmpty());
            verify(fixture.connection).rollback();
            verify(fixture.connection).setAutoCommit(true);
        }
    }

    @Test
    void fenceIsCasAndIdempotentButRejectsStalePin() throws Exception {
        Jdbc fixture = new Jdbc();
        MariaDbSourceGeneration.fence(fixture.connection, PIN);
        assertEquals("FENCED", fixture.state);
        assertEquals(1, fixture.writes.size());
        Query cas = fixture.writes.getFirst();
        assertTrue(cas.sql().contains("AND instance_id = ? AND generation = ? AND state = ?"));
        assertEquals(Map.of(1, "FENCED", 2, INSTANCE, 3, G1, 4, "ACTIVE"), cas.values());
        MariaDbSourceGeneration.fence(fixture.connection, PIN);
        assertEquals(1, fixture.writes.size());
        assertOrdinarySqlFailure(() -> MariaDbSourceGeneration.fence(fixture.connection,
                new SourceGenerationPin(SCHEMA, INSTANCE, G2)));
        assertEquals(1, fixture.writes.size());
    }

    @Test
    void rotateRequiresFenceAndFreshUuid() throws Exception {
        Jdbc fixture = new Jdbc();
        assertOrdinarySqlFailure(() -> MariaDbSourceGeneration.rotate(fixture.connection, PIN, G2));
        assertTrue(fixture.writes.isEmpty());
        assertOrdinarySqlFailure(() -> MariaDbSourceGeneration.rotate(fixture.connection, PIN, G1));
        assertTrue(fixture.writes.isEmpty());
    }

    @Test
    void rotateRecordsPredecessorAndInstanceThenCasAndReplaysOnlyCurrentActiveReceipt() throws Exception {
        Jdbc fixture = new Jdbc();
        fixture.state = "FENCED";
        SourceGenerationPin next = MariaDbSourceGeneration.rotate(fixture.connection, PIN, G2);
        assertEquals(new SourceGenerationPin(SCHEMA, INSTANCE, G2), next);
        assertEquals(INSTANCE, fixture.instance);
        assertEquals("ACTIVE", fixture.state);
        assertArrayEquals(row(G2, G1, INSTANCE), fixture.history.get(G2));
        assertEquals(2, fixture.writes.size());
        assertTrue(fixture.writes.get(0).sql().startsWith("INSERT INTO"));
        assertTrue(fixture.writes.get(1).sql().contains("SET generation = ?, state = ? WHERE"));
        assertFalse(fixture.writes.get(1).sql().contains("SET instance_id"));
        assertEquals(next, MariaDbSourceGeneration.rotate(fixture.connection, PIN, G2));
        assertEquals(2, fixture.writes.size());
        fixture.state = "FENCED";
        assertOrdinarySqlFailure(() -> MariaDbSourceGeneration.rotate(fixture.connection, PIN, G2));
        assertEquals(2, fixture.writes.size());
    }

    @Test
    void historicalReceiptAfterFurtherRotationCannotReplayAndAbaIsRejected() throws Exception {
        Jdbc fixture = new Jdbc();
        fixture.state = "FENCED";
        SourceGenerationPin second = MariaDbSourceGeneration.rotate(fixture.connection, PIN, G2);
        MariaDbSourceGeneration.fence(fixture.connection, second);
        SourceGenerationPin third = MariaDbSourceGeneration.rotate(fixture.connection, second, G3);
        int writes = fixture.writes.size();
        assertOrdinarySqlFailure(() -> MariaDbSourceGeneration.rotate(fixture.connection, PIN, G2));
        assertEquals(writes, fixture.writes.size());
        MariaDbSourceGeneration.fence(fixture.connection, third);
        writes = fixture.writes.size();
        assertOrdinarySqlFailure(() -> MariaDbSourceGeneration.rotate(fixture.connection, third, G1));
        assertOrdinarySqlFailure(() -> MariaDbSourceGeneration.rotate(fixture.connection, third, G2));
        assertEquals(writes, fixture.writes.size());
    }

    @Test
    void retryWithWrongPredecessorOrInstanceIsRejected() throws Exception {
        Jdbc fixture = new Jdbc();
        fixture.generation = G2;
        fixture.history.put(G2, row(G2, G1, INSTANCE));
        assertOrdinarySqlFailure(() -> MariaDbSourceGeneration.rotate(fixture.connection,
                new SourceGenerationPin(SCHEMA, INSTANCE, G3), G2));
        assertOrdinarySqlFailure(() -> MariaDbSourceGeneration.rotate(fixture.connection,
                new SourceGenerationPin(SCHEMA, G3, G1), G2));
        assertTrue(fixture.writes.isEmpty());
    }

    @Test
    void uniqueConstraintAndCasFailuresRollbackAndPreserveOriginalCause() throws Exception {
        Jdbc fixture = new Jdbc();
        fixture.state = "FENCED";
        SQLException duplicate = new SQLException("test unique conflict", "23000", 1062);
        fixture.writeFailure = duplicate;
        assertSame(duplicate, assertThrows(SQLException.class,
                () -> MariaDbSourceGeneration.rotate(fixture.connection, PIN, G2)));
        verify(fixture.connection).rollback();
        verify(fixture.connection, never()).commit();
        verify(fixture.connection).setAutoCommit(true);
        Jdbc failedCas = new Jdbc();
        failedCas.state = "FENCED";
        failedCas.casCount = 0;
        assertOrdinarySqlFailure(() -> MariaDbSourceGeneration.rotate(failedCas.connection, PIN, G2));
        verify(failedCas.connection).rollback();
        verify(failedCas.connection, never()).commit();
        verify(failedCas.connection).setAutoCommit(true);
    }

    @Test
    void rollbackFailureNeverRestoresAutocommitOrClosesConnection() throws Exception {
        Jdbc fixture = new Jdbc();
        fixture.state = "FENCED";
        SQLException statement = new SQLException("test failed write", "40001");
        SQLException rollback = new SQLException("test rollback failed", "08006");
        fixture.writeFailure = statement;
        doThrow(rollback).when(fixture.connection).rollback();
        assertSame(statement, assertThrows(SQLException.class,
                () -> MariaDbSourceGeneration.rotate(fixture.connection, PIN, G2)));
        assertArrayEquals(new Throwable[]{rollback}, statement.getSuppressed());
        verify(fixture.connection, never()).setAutoCommit(true);
        verify(fixture.connection, never()).commit();
        verify(fixture.connection, never()).close();
    }

    @Test
    void disablingAutocommitFailureDoesNotRollbackOrRestoreCallerConnection() throws Exception {
        Jdbc fixture = new Jdbc();
        fixture.state = "FENCED";
        SQLException start = new SQLException("test transaction setup failed", "08006");
        doThrow(start).when(fixture.connection).setAutoCommit(false);
        assertSame(start, assertThrows(SQLException.class,
                () -> MariaDbSourceGeneration.rotate(fixture.connection, PIN, G2)));
        verify(fixture.connection, never()).rollback();
        verify(fixture.connection, never()).commit();
        verify(fixture.connection, never()).setAutoCommit(true);
        verify(fixture.connection, never()).close();
        assertTrue(fixture.writes.isEmpty());
    }

    @Test
    void successfulRollbackWithRestoreFailurePreservesOriginalError() throws Exception {
        Jdbc fixture = new Jdbc();
        fixture.state = "FENCED";
        SQLException statement = new SQLException("test failed write", "40001");
        SQLException restore = new SQLException("test restore failed", "08006");
        fixture.writeFailure = statement;
        doThrow(restore).when(fixture.connection).setAutoCommit(true);
        assertSame(statement, assertThrows(SQLException.class,
                () -> MariaDbSourceGeneration.rotate(fixture.connection, PIN, G2)));
        assertArrayEquals(new Throwable[]{restore}, statement.getSuppressed());
        verify(fixture.connection).rollback();
        verify(fixture.connection, never()).close();
    }

    @Test
    void lostCommitAcknowledgementCanReconcileTheSameTokenWithoutMoreWrites() throws Exception {
        Jdbc fixture = new Jdbc();
        fixture.state = "FENCED";
        SQLException lostReply = new SQLException("test commit reply lost", "08006");
        doThrow(lostReply).doNothing().when(fixture.connection).commit();
        assertSame(lostReply, assertThrows(SQLException.class,
                () -> MariaDbSourceGeneration.rotate(fixture.connection, PIN, G2)));
        int writes = fixture.writes.size();
        assertEquals(new SourceGenerationPin(SCHEMA, INSTANCE, G2),
                MariaDbSourceGeneration.rotate(fixture.connection, PIN, G2));
        assertEquals(writes, fixture.writes.size());
        verify(fixture.connection).rollback();
        verify(fixture.connection, never()).close();
    }

    @Test
    void unknownCommitAndFailedRollbackCannotImplicitlyCommitByRestoringAutocommit() throws Exception {
        Jdbc fixture = new Jdbc();
        fixture.state = "FENCED";
        SQLException commit = new SQLException("test commit reply lost", "08006");
        SQLException rollback = new SQLException("test rollback failed", "08006");
        doThrow(commit).when(fixture.connection).commit();
        doThrow(rollback).when(fixture.connection).rollback();
        assertSame(commit, assertThrows(SQLException.class,
                () -> MariaDbSourceGeneration.rotate(fixture.connection, PIN, G2)));
        assertArrayEquals(new Throwable[]{rollback}, commit.getSuppressed());
        verify(fixture.connection, never()).setAutoCommit(true);
        verify(fixture.connection, never()).close();
    }

    @Test
    void restoreFailureAfterSuccessfulCommitIsReportedAndDoesNotRollbackCommittedWork() throws Exception {
        Jdbc fixture = new Jdbc();
        fixture.state = "FENCED";
        SQLException restore = new SQLException("test restore failed", "08006");
        doThrow(restore).when(fixture.connection).setAutoCommit(true);
        assertSame(restore, assertThrows(SQLException.class,
                () -> MariaDbSourceGeneration.rotate(fixture.connection, PIN, G2)));
        verify(fixture.connection).commit();
        verify(fixture.connection, never()).rollback();
        verify(fixture.connection, never()).close();
    }

    private static void assertOrdinarySqlFailure(SqlAction action) {
        SQLException failure = assertThrows(SQLException.class, action::run);
        assertFalse(failure instanceof SQLFeatureNotSupportedException);
        assertEquals("55000", failure.getSQLState());
    }

    @FunctionalInterface
    private interface SqlAction { void run() throws SQLException; }
    private record Query(String sql, PreparedStatement statement, Map<Integer, String> values) { }

    private static String[] row(String... values) { return values; }
    private static List<String[]> rows(String[]... rows) { return List.of(rows); }

    /** Scripted metadata and protocol responses, not a SQL engine or a database atomicity test. */
    private static final class Jdbc {
        final Connection connection = mock(Connection.class);
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        final List<Query> queries = new ArrayList<>();
        final List<Query> writes = new ArrayList<>();
        final Map<String, String[]> history = new LinkedHashMap<>();
        final Map<String, List<String[]>> overrides = new LinkedHashMap<>();
        String version = "11.4.5-MariaDB";
        String sessionTransaction = "0";
        String instance = INSTANCE;
        String generation = G1;
        String state = "ACTIVE";
        String temporary;
        String failurePrefix;
        SQLException queryFailure;
        SQLException writeFailure;
        boolean markerExists = true;
        boolean historyExists = true;
        boolean fenceOnReceipt;
        int casCount = 1;

        Jdbc() throws SQLException {
            history.put(G1, row(G1, null, INSTANCE));
            when(connection.getAutoCommit()).thenReturn(true);
            when(connection.getMetaData()).thenReturn(metadata);
            when(metadata.getDatabaseProductName()).thenReturn("MariaDB");
            when(metadata.getDatabaseMajorVersion()).thenReturn(11);
            when(metadata.getDatabaseMinorVersion()).thenReturn(4);
            when(connection.prepareStatement(anyString())).thenAnswer(call -> {
                String sql = call.getArgument(0);
                PreparedStatement statement = mock(PreparedStatement.class);
                Map<Integer, String> values = new HashMap<>();
                Query query = new Query(sql, statement, values);
                queries.add(query);
                doAnswer(bind -> { values.put(bind.getArgument(0), bind.getArgument(1)); return null; })
                        .when(statement).setString(anyInt(), nullable(String.class));
                when(statement.executeQuery()).thenAnswer(ignored -> resultSet(select(query)));
                when(statement.executeUpdate()).thenAnswer(ignored -> write(query));
                return statement;
            });
        }

        private List<String[]> select(Query query) throws SQLException {
            String sql = query.sql();
            if (failurePrefix != null && sql.startsWith(failurePrefix)) throw queryFailure;
            for (var override : overrides.entrySet()) {
                if (sql.startsWith(override.getKey())) return override.getValue();
            }
            if (sql.startsWith("SELECT VERSION()")) {
                return rows(row(version, "MariaDB Server", "1", sessionTransaction, "1"));
            }
            if (sql.startsWith("SELECT SCHEMA_NAME")) return rows(row(SCHEMA));
            boolean marker = !sql.contains("dbswitch_source_generation_history")
                    && !MariaDbSourceGeneration.HISTORY_TABLE.equals(query.values().get(2));
            String table = marker ? MariaDbSourceGeneration.MARKER_TABLE : MariaDbSourceGeneration.HISTORY_TABLE;
            if (sql.startsWith("SHOW CREATE TABLE")) {
                if (!(marker ? markerExists : historyExists)) throw new SQLException("test missing", "42S02", 1146);
                return rows(row(table, (table.equals(temporary) ? "CREATE TEMPORARY TABLE " : "CREATE TABLE ") + table));
            }
            if (sql.startsWith("SELECT TABLE_TYPE")) {
                return rows(row("BASE TABLE", "InnoDB", "dbswitch.source-generation.v1." + table, ""));
            }
            if (sql.startsWith("SELECT COLUMN_NAME")) {
                return marker ? rows(column("singleton_id", "tinyint(3) unsigned", false, null),
                        column("schema_version", "smallint(5) unsigned", false, null),
                        column("instance_id", "char(36)", false, "ascii_bin"),
                        column("generation", "char(36)", false, "ascii_bin"),
                        column("state", "varchar(6)", false, "ascii_bin"))
                        : rows(column("generation", "char(36)", false, "ascii_bin"),
                        column("prev_generation", "char(36)", true, "ascii_bin"),
                        column("instance_id", "char(36)", false, "ascii_bin"));
            }
            if (sql.startsWith("SELECT CONSTRAINT_NAME, CONSTRAINT_TYPE")) {
                return marker ? rows(row("PRIMARY", "PRIMARY KEY"), row("sg_singleton", "CHECK"),
                        row("sg_version", "CHECK"), row("sg_state", "CHECK"))
                        : rows(row("PRIMARY", "PRIMARY KEY"), row("sg_previous", "UNIQUE"));
            }
            if (sql.startsWith("SELECT CONSTRAINT_NAME, CHECK_CLAUSE")) {
                return marker ? rows(row("sg_singleton", "`singleton_id` = 1"), row("sg_version", "`schema_version` = 1"),
                        row("sg_state", "`state` in ('ACTIVE','FENCED')")) : rows();
            }
            if (sql.startsWith("SELECT INDEX_NAME")) {
                return marker ? rows(row("PRIMARY", "singleton_id", "0", "1", null, "BTREE", "NO"))
                        : rows(row("PRIMARY", "generation", "0", "1", null, "BTREE", "NO"),
                        row("sg_previous", "prev_generation", "0", "1", null, "BTREE", "NO"));
            }
            if (sql.startsWith("SELECT TRIGGER_NAME")) return rows();
            if (sql.startsWith(MARKER_SELECT)) {
                return generation == null ? rows() : rows(row("1", "1", instance, generation, state));
            }
            if (sql.startsWith(HISTORY_SELECT)) {
                if (sql.contains("WHERE generation = ?")) {
                    if (fenceOnReceipt) state = "FENCED";
                    String[] entry = history.get(query.values().get(1));
                    return entry == null ? rows() : rows(entry);
                }
                return new ArrayList<>(history.values());
            }
            throw new AssertionError("Unexpected SQL in pure test: " + sql);
        }

        private int write(Query query) throws SQLException {
            writes.add(query);
            if (writeFailure != null) throw writeFailure;
            String sql = query.sql();
            Map<Integer, String> values = query.values();
            if (sql.startsWith("CREATE TABLE")) {
                if (sql.contains("dbswitch_source_generation_history")) historyExists = true;
                else markerExists = true;
                return 0;
            }
            if (sql.startsWith("INSERT INTO") && sql.contains("dbswitch_source_generation_history")) {
                if (history.containsKey(values.get(1))) throw new SQLException("test duplicate", "23000", 1062);
                history.put(values.get(1), row(values.get(1), values.get(2), values.get(3)));
                return 1;
            }
            if (sql.startsWith("INSERT INTO")) {
                instance = values.get(1); generation = values.get(2); state = values.get(3);
                return 1;
            }
            if (sql.startsWith("UPDATE")) {
                if (casCount != 1) return casCount;
                if (sql.contains("SET generation")) { generation = values.get(1); state = values.get(2); }
                else state = values.get(1);
                return 1;
            }
            throw new AssertionError("Unexpected SQL mutation in pure test: " + sql);
        }

        void assertConnectionUntouched() throws SQLException {
            verify(connection, never()).setAutoCommit(anyBoolean());
            verify(connection, never()).commit();
            verify(connection, never()).rollback();
            verify(connection, never()).setReadOnly(anyBoolean());
            verify(connection, never()).setTransactionIsolation(anyInt());
            verify(connection, never()).setCatalog(anyString());
            verify(connection, never()).setSchema(anyString());
            verify(connection, never()).close();
        }

        void assertSuccessfulOwnedTransaction() throws SQLException {
            var order = inOrder(connection);
            order.verify(connection).setAutoCommit(false);
            order.verify(connection).commit();
            order.verify(connection).setAutoCommit(true);
            verify(connection, never()).rollback();
            verify(connection, never()).close();
        }

        private static String[] column(String name, String type, boolean nullable, String collation) {
            return row(name, type, nullable ? "YES" : "NO", collation, nullable ? "NULL" : null, "", "NEVER");
        }
    }

    private static ResultSet resultSet(List<String[]> data) throws SQLException {
        ResultSet result = mock(ResultSet.class);
        AtomicInteger cursor = new AtomicInteger(-1);
        when(result.next()).thenAnswer(ignored -> cursor.incrementAndGet() < data.size());
        when(result.getString(anyInt())).thenAnswer(call -> data.get(cursor.get())[call.getArgument(0, Integer.class) - 1]);
        when(result.getInt(anyInt())).thenAnswer(call -> {
            String value = data.get(cursor.get())[call.getArgument(0, Integer.class) - 1];
            return value == null ? 0 : Integer.parseInt(value);
        });
        return result;
    }
}

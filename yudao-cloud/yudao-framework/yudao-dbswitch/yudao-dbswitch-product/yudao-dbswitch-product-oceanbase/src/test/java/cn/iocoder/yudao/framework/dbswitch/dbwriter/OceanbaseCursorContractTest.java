package cn.iocoder.yudao.framework.dbswitch.dbwriter;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.OceanbaseDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.oceanbase.OceanbaseUpsertWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * JDBC mock routing for the refusal order only. OceanBase CE has no Oracle-mode tenant; these tests prove that the
 * mode, version, driver and SQL mode reported by the actual connection stop the cursor/UPSERT before any session
 * change or data statement. They certify no Oracle-mode behaviour and no real server.
 */
class OceanbaseCursorContractTest {
    private static final String VERSION = "5.7.25-OceanBase_CE-v4.3.5.6", STRICT = "STRICT_TRANS_TABLES,STRICT_ALL_TABLES";
    private static final SourceGenerationPin PIN = new SourceGenerationPin("app", "11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222");
    private static final List<String> FIELDS = List.of("wm", "id");

    /** The operator's constructor probe: SHOW GLOBAL VARIABLES reports the tenant mode. */
    private static Connection modeProbe(String mode) throws SQLException {
        Connection connection = mock(Connection.class); PreparedStatement statement = mock(PreparedStatement.class); ResultSet rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement); when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false); when(rows.getString(2)).thenReturn(mode);
        return connection;
    }
    /** A cursor/UPSERT connection: only the session admission query is answerable; everything else is recorded and fails. */
    private static Connection session(String mode, String version, String driver, String consistency, String sqlMode, String charset, List<String> statements) throws SQLException {
        Connection connection = mock(Connection.class); DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        Statement statement = mock(Statement.class); ResultSet rows = mock(ResultSet.class);
        when(connection.getAutoCommit()).thenReturn(true); when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDriverName()).thenReturn("OceanBase Connector/J"); when(metadata.getDriverVersion()).thenReturn(driver);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenAnswer(call -> {
            statements.add(call.getArgument(0));
            if (statements.size() > 1) throw new SQLException("No statement may follow a refused session");
            return rows;
        });
        when(connection.prepareStatement(anyString())).thenAnswer(call -> { statements.add(call.getArgument(0)); throw new SQLException("No statement may follow a refused session"); });
        when(rows.next()).thenReturn(true, false);
        when(rows.getString(1)).thenReturn(version); when(rows.getString(2)).thenReturn(mode); when(rows.getString(3)).thenReturn(consistency);
        when(rows.getString(4)).thenReturn("1"); when(rows.getString(5)).thenReturn(sqlMode);
        when(rows.getString(6)).thenReturn(charset); when(rows.getString(7)).thenReturn(charset); when(rows.getString(8)).thenReturn(charset);
        return connection;
    }
    private static CursorReadRequest request(SourceGenerationPin pin) {
        return new CursorReadRequest("app", "rows", FIELDS, "wm", List.of("id"), null, null, null, BatchLimits.defaults(), false, pin);
    }
    private static void assertUntouched(Connection connection) throws SQLException {
        verify(connection, never()).setAutoCommit(false); verify(connection, never()).setReadOnly(anyBoolean());
        verify(connection, never()).setTransactionIsolation(anyInt()); verify(connection, never()).commit(); verify(connection, never()).rollback();
        verify(connection).close();
    }

    @Test void oracleModeRequiresItsOwnActualServerProfileBeforeChangingTheSnapshotConnection() throws Exception {
        DataSource source = mock(DataSource.class); Connection probe = modeProbe("ORACLE");
        Connection snapshot = mock(Connection.class); DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(snapshot.getAutoCommit()).thenReturn(true); when(snapshot.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("OceanBase"); when(metadata.getDatabaseProductVersion()).thenReturn("4.3.5.6");
        when(source.getConnection()).thenReturn(probe, snapshot);
        var operator = new OceanbaseDatabaseOperator(source);
        assertThrows(IllegalArgumentException.class, () -> operator.openCursor(request(PIN)));
        verify(source, times(2)).getConnection(); verify(probe).close(); assertUntouched(snapshot);
        verify(snapshot, never()).createStatement();
    }

    @ParameterizedTest @CsvSource(delimiter = ';', value = {
            "ORACLE;5.7.25-OceanBase_CE-v4.3.5.6;2.4.12;STRONG;STRICT_TRANS_TABLES;utf8mb4",
            "MYSQL;5.7.25-OceanBase_CE-v4.2.5.0;2.4.12;STRONG;STRICT_TRANS_TABLES;utf8mb4",
            "MYSQL;8.0.36;2.4.12;STRONG;STRICT_TRANS_TABLES;utf8mb4",
            "MYSQL;5.7.25-OceanBase_CE-v4.3.5.6;2.4.3;STRONG;STRICT_TRANS_TABLES;utf8mb4",
            "MYSQL;5.7.25-OceanBase_CE-v4.3.5.6;2.4.12;WEAK;STRICT_TRANS_TABLES;utf8mb4",
            "MYSQL;5.7.25-OceanBase_CE-v4.3.5.6;2.4.12;STRONG;STRICT_TRANS_TABLES,NO_BACKSLASH_ESCAPES;utf8mb4",
            "MYSQL;5.7.25-OceanBase_CE-v4.3.5.6;2.4.12;STRONG;PAD_CHAR_TO_FULL_LENGTH;utf8mb4",
            "MYSQL;5.7.25-OceanBase_CE-v4.3.5.6;2.4.12;STRONG;STRICT_TRANS_TABLES;gbk"})
    void cursorConnectionThatIsNotTheVerifiedMysqlTenantIsRefusedBeforeAnySessionChange(String mode, String version, String driver, String consistency, String sqlMode, String charset) throws Exception {
        DataSource source = mock(DataSource.class); Connection probe = modeProbe("MYSQL"); var statements = new ArrayList<String>();
        Connection cursor = session(mode, version, driver, consistency, sqlMode, charset, statements);
        // The factory-time probe saw a MySQL tenant; the snapshot connection itself decides.
        when(source.getConnection()).thenReturn(probe, cursor);
        var operator = new OceanbaseDatabaseOperator(source);
        assertThrows(IllegalArgumentException.class, () -> operator.openCursor(request(PIN)));
        verify(source, times(2)).getConnection(); assertUntouched(cursor);
        assertTrue(statements.size() <= 1 && statements.stream().allMatch(sql -> sql.startsWith("SELECT VERSION(),@@ob_compatibility_mode")), statements.toString());
    }

    @Test void verifiedProfilePassesAdmissionSoEachRefusalIsCausedByItsSingleDeviation() throws Exception {
        DataSource source = mock(DataSource.class); Connection probe = modeProbe("MYSQL"); var statements = new ArrayList<String>();
        Connection cursor = session("MYSQL", VERSION, "2.4.12", "STRONG", STRICT, "utf8mb4", statements);
        when(source.getConnection()).thenReturn(probe, cursor);
        // This fixture answers only the admission query, so the next statement proves the admission itself passed.
        assertThrows(IllegalArgumentException.class, () -> new OceanbaseDatabaseOperator(source).openCursor(request(PIN)));
        assertEquals(2, statements.size(), statements.toString()); assertTrue(statements.get(1).contains("GV$OB_SESSION"), statements.toString());
        DataSource targets = mock(DataSource.class); var writes = new ArrayList<String>();
        Connection target = session("MYSQL", VERSION, "2.4.12", "STRONG", STRICT, "utf8mb4", writes);
        when(targets.getConnection()).thenReturn(target);
        assertThrows(IllegalArgumentException.class, () -> new OceanbaseUpsertWriter(targets).prepareUpsert("app", "rows", List.of("id", "v"), List.of("id")));
        assertEquals(2, writes.size(), writes.toString()); assertTrue(writes.get(1).contains("information_schema.TABLES"), writes.toString());
        assertTrue(writes.stream().noneMatch(sql -> sql.startsWith("INSERT")));
    }

    @Test void absentDeploymentPinIsRefusedBeforeAnyQuery() throws Exception {
        DataSource source = mock(DataSource.class); Connection probe = modeProbe("MYSQL"); var statements = new ArrayList<String>();
        Connection cursor = session("MYSQL", VERSION, "2.4.12", "STRONG", STRICT, "utf8mb4", statements);
        when(source.getConnection()).thenReturn(probe, cursor);
        assertThrows(IllegalArgumentException.class, () -> new OceanbaseDatabaseOperator(source).openCursor(request(null)));
        assertUntouched(cursor); assertTrue(statements.isEmpty(), statements.toString()); verify(cursor, never()).createStatement();
    }

    @ParameterizedTest @CsvSource(delimiter = ';', value = {
            "ORACLE;5.7.25-OceanBase_CE-v4.3.5.6;2.4.12;STRONG;STRICT_TRANS_TABLES;utf8mb4",
            "MYSQL;5.7.25-OceanBase_CE-v4.3.5.5;2.4.12;STRONG;STRICT_TRANS_TABLES;utf8mb4",
            "MYSQL;5.7.25-OceanBase_CE-v4.3.5.6;2.4.3;STRONG;STRICT_TRANS_TABLES;utf8mb4",
            "MYSQL;5.7.25-OceanBase_CE-v4.3.5.6;2.4.12;STRONG;NO_ENGINE_SUBSTITUTION;utf8mb4",
            "MYSQL;5.7.25-OceanBase_CE-v4.3.5.6;2.4.12;STRONG;STRICT_ALL_TABLES,NO_BACKSLASH_ESCAPES;utf8mb4",
            "MYSQL;5.7.25-OceanBase_CE-v4.3.5.6;2.4.12;STRONG;STRICT_ALL_TABLES;gbk"})
    void upsertOnAnUnverifiedOrNonStrictSessionIsRefusedBeforeAnyCatalogOrDataStatement(String mode, String version, String driver, String consistency, String sqlMode, String charset) throws Exception {
        DataSource source = mock(DataSource.class); var statements = new ArrayList<String>();
        Connection target = session(mode, version, driver, consistency, sqlMode, charset, statements);
        var writer = new OceanbaseUpsertWriter(source);
        verifyNoInteractions(source);
        when(source.getConnection()).thenReturn(target);
        assertThrows(IllegalArgumentException.class, () -> writer.prepareUpsert("app", "rows", List.of("id", "v"), List.of("id")));
        assertUntouched(target);
        assertTrue(statements.size() <= 1 && statements.stream().allMatch(sql -> sql.startsWith("SELECT VERSION(),@@ob_compatibility_mode")), statements.toString());
        // A refused preparation leaves no usable writer: nothing is borrowed or sent afterwards.
        assertThrows(RuntimeException.class, () -> writer.writeWithReceipt(List.of("id", "v"), Collections.singletonList(new Object[]{1L, "x"})));
        assertThrows(IllegalStateException.class, writer::targetSchemaFingerprint);
        verify(source, times(1)).getConnection();
    }
}

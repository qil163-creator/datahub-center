package cn.iocoder.yudao.framework.dbswitch.core.schema.catalog;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseHiveImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.HiveNativeCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Column reads on {@link DatabaseHiveImpl} use {@link HiveNativeCatalog}, not a bare DESCRIBE. */
@Timeout(60)
class HiveDialectColumnReadTest {
    private static final String TEXT_INPUT = "org.apache.hadoop.mapred.TextInputFormat";
    private static final String LAZY_SERDE = "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe";
    private static final String ORC_INPUT = "org.apache.hadoop.hive.ql.io.orc.OrcInputFormat";
    private static final String ORC_SERDE = "org.apache.hadoop.hive.ql.io.orc.OrcSerde";

    @Test void physicalMetadataUsesDescribeAndMetastoreWithoutScanningTheRelation() throws Exception {
        Probe probe = probe(HiveNativeCatalog.VERSION, "EXTERNAL_TABLE", TEXT_INPUT, LAZY_SERDE, false, false);
        var database = new DatabaseHiveImpl();
        List<ColumnDescription> columns = database.queryTableColumnMeta(probe.connection, "s", "plain");
        assertEquals(List.of(
                "SELECT version()",
                "DESCRIBE `s`.`plain`"), probe.executed);
        assertTrue(probe.executed.stream().noneMatch(sql -> sql.toLowerCase().contains("information_schema")));
        assertEquals(List.of("c_int"), columns.stream().map(ColumnDescription::getFieldName).toList());
        assertEquals(Types.INTEGER, columns.get(0).getFieldType());
        assertEquals("note", columns.get(0).getRemarks());
        assertEquals("int", columns.get(0).getMetadataEvidence().nativeType().value());
        assertEquals("HIVE_CATALOG_V1", columns.get(0).getMetadataEvidence().provenance().method());
        assertEquals(List.of("c_int"), database.queryTableColumnMetaOnly(probe.connection, "s", "plain").stream()
                .map(ColumnDescription::getFieldName).toList());
        assertEquals(probe.executed.subList(0, 2), probe.executed.subList(2, 4));
        verify(probe.statement, times(4)).setQueryTimeout(30);
        verify(probe.statement, never()).setMaxRows(anyInt());
        verify(probe.statement, never()).execute(anyString());
        verify(probe.statement, times(4)).close();
        verify(probe.connection, never()).close();
        verify(probe.connection, never()).commit();
        verify(probe.connection, never()).rollback();
    }

    @Test void strictReconstructionStillRejectsAcidPartitionsViewsAndManagedTables() throws Exception {
        reject("VIRTUAL_VIEW", TEXT_INPUT, LAZY_SERDE, false, false, "views");
        reject("EXTERNAL_TABLE", TEXT_INPUT, LAZY_SERDE, true, false, "partitioned");
        reject("EXTERNAL_TABLE", TEXT_INPUT, LAZY_SERDE, false, true, "ACID");
        reject("MANAGED_TABLE", TEXT_INPUT, LAZY_SERDE, false, false, "TEXTFILE");
        reject("EXTERNAL_TABLE", ORC_INPUT, LAZY_SERDE, false, false, "TEXTFILE");
        reject("EXTERNAL_TABLE", TEXT_INPUT, ORC_SERDE, false, false, "TEXTFILE");
    }

    @Test void describeJsonIsNotAcceptedWithoutTheVersionAndStorageChecks() throws Exception {
        Probe probe = probe("{\"columns\":[{\"name\":\"id\",\"type\":\"int\"}]}",
                "EXTERNAL_TABLE", TEXT_INPUT, LAZY_SERDE, false, false);
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> new DatabaseHiveImpl().queryTableColumnMeta(probe.connection, "schema", "sample"));
        assertInstanceOf(SQLFeatureNotSupportedException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("unverified"));
        assertEquals(List.of("SELECT version()"), probe.executed);
        verify(probe.statement, never()).setMaxRows(anyInt());
        verify(probe.connection, never()).close();
    }

    @Test void blankIdentifierIsRejectedBeforeAStatement() throws Exception {
        Connection connection = mock(Connection.class);
        assertThrows(IllegalArgumentException.class,
                () -> new DatabaseHiveImpl().queryTableColumnMeta(connection, " ", "plain"));
        verify(connection, never()).createStatement();
    }

    private static void reject(String tableType, String input, String serde, boolean partitioned, boolean acid,
            String messagePart) throws Exception {
        Probe probe = probe(HiveNativeCatalog.VERSION, tableType, input, serde, partitioned, acid);
        var failure = assertThrows(SQLFeatureNotSupportedException.class,
                () -> HiveNativeCatalog.read(probe.connection, "s", "plain", 30));
        assertTrue(failure.getMessage().contains(messagePart), failure.getMessage());
        assertEquals("SELECT version()", probe.executed.get(0));
        assertTrue(probe.executed.stream().noneMatch(sql -> sql.startsWith("SELECT * FROM ")));
        verify(probe.connection, never()).close();
        verify(probe.connection, never()).commit();
        verify(probe.connection, never()).rollback();
    }

    private static Probe probe(String version, String tableType, String input, String serde,
            boolean partitioned, boolean acid) throws SQLException {
        var executed = new ArrayList<String>();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSetMetaData jdbc = jdbcMetadata();
        when(connection.createStatement()).thenReturn(statement);
        var metadata = mock(java.sql.DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getColumns(null, "s", "plain", null)).thenAnswer(invocation -> {
            var result = mock(ResultSet.class); when(result.next()).thenReturn(true, false);
            when(result.getString("TABLE_SCHEM")).thenReturn("s"); when(result.getString("TABLE_NAME")).thenReturn("plain");
            when(result.getString("COLUMN_NAME")).thenReturn("c_int"); when(result.getString("TYPE_NAME")).thenReturn("INT");
            when(result.getString("REMARKS")).thenReturn("note");
            when(result.getInt("DATA_TYPE")).thenReturn(Types.INTEGER); when(result.getInt("ORDINAL_POSITION")).thenReturn(1);
            when(result.getInt("COLUMN_SIZE")).thenReturn(10); when(result.getInt("NULLABLE")).thenReturn(ResultSetMetaData.columnNullable);
            return result;
        });
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            executed.add(sql);
            if (sql.equals("SELECT version()")) return grid(1, row(version));
            if (sql.startsWith("DESCRIBE FORMATTED ")) return grid(3, formatted(tableType, input, serde, partitioned));
            if (sql.startsWith("SHOW TBLPROPERTIES ")) return acid ? grid(2, row("transactional", "true")) : grid(2);
            if (sql.startsWith("SELECT * FROM ") && sql.endsWith(" WHERE 1=0")) return jdbcRows(jdbc);
            if (sql.startsWith("DESCRIBE ")) return grid(3, row("c_int", "int", "note"));
            throw new AssertionError(sql);
        });
        return new Probe(connection, statement, executed);
    }

    private static String[][] formatted(String tableType, String input, String serde, boolean partitioned) {
        var rows = new ArrayList<String[]>();
        rows.add(row("c_int", "int", null));
        if (partitioned) {
            rows.add(row("# Partition Information", "", ""));
            rows.add(row("dt", "string", null));
        }
        rows.add(row("# Detailed Table Information", "", ""));
        rows.add(row("Table Type:", tableType + "   ", null));
        rows.add(row("SerDe Library:", serde + " ", null));
        rows.add(row("InputFormat:", "  " + input, null));
        return rows.toArray(String[][]::new);
    }

    private static ResultSet jdbcRows(ResultSetMetaData metadata) throws SQLException {
        ResultSet result = mock(ResultSet.class);
        when(result.getMetaData()).thenReturn(metadata);
        when(result.next()).thenReturn(false);
        return result;
    }

    private static ResultSetMetaData jdbcMetadata() throws SQLException {
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnName(1)).thenReturn("plain.c_int");
        when(metadata.getColumnType(1)).thenReturn(Types.INTEGER);
        when(metadata.getColumnTypeName(1)).thenReturn("int");
        when(metadata.getColumnClassName(1)).thenReturn("java.lang.Integer");
        when(metadata.getColumnDisplaySize(1)).thenReturn(10);
        when(metadata.getPrecision(1)).thenReturn(10);
        when(metadata.getScale(1)).thenReturn(0);
        when(metadata.isSigned(1)).thenReturn(true);
        when(metadata.isNullable(1)).thenReturn(ResultSetMetaData.columnNullable);
        when(metadata.isAutoIncrement(1)).thenReturn(false);
        return metadata;
    }

    private static ResultSet grid(int columns, String[]... rows) throws SQLException {
        ResultSet result = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnCount()).thenReturn(columns);
        when(result.getMetaData()).thenReturn(metadata);
        int[] cursor = {-1};
        when(result.next()).thenAnswer(invocation -> ++cursor[0] < rows.length);
        when(result.getString(anyInt())).thenAnswer(invocation -> {
            int column = invocation.getArgument(0);
            String[] row = rows[cursor[0]];
            return column - 1 < row.length ? row[column - 1] : null;
        });
        return result;
    }

    private static String[] row(String... cells) { return cells; }

    private record Probe(Connection connection, Statement statement, List<String> executed) { }
}

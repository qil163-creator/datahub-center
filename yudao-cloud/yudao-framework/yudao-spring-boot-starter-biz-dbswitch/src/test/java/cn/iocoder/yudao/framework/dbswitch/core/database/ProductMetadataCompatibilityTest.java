package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseClickhouseImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDmImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** SQL/API contract tests only; these do not certify the actual ClickHouse or DM server versions. */
class ProductMetadataCompatibilityTest {
    @Test void clickhouseListAndSingleTableUseTheSameConnectionAndPreserveAvailableComments() throws Exception {
        verifyClickhouse(true);
        verifyClickhouse(false);
    }

    private void verifyClickhouse(boolean supportsComment) throws Exception {
        var database = mock(DatabaseClickhouseImpl.class, CALLS_REAL_METHODS);
        Connection connection = mock(Connection.class);
        Statement probe = mock(Statement.class);
        ResultSet probeRows = mock(ResultSet.class);
        ResultSetMetaData probeMetadata = mock(ResultSetMetaData.class);
        PreparedStatement query = mock(PreparedStatement.class);
        ResultSet listRows = row(supportsComment), singleRows = row(supportsComment);
        when(connection.createStatement()).thenReturn(probe);
        when(probe.executeQuery("SELECT * FROM `system`.`tables` WHERE 1=0")).thenReturn(probeRows);
        when(probeRows.getMetaData()).thenReturn(probeMetadata);
        when(probeMetadata.getColumnCount()).thenReturn(2);
        when(probeMetadata.getColumnName(1)).thenReturn("name");
        when(probeMetadata.getColumnName(2)).thenReturn(supportsComment ? "comment" : "engine");
        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            assertEquals(!supportsComment, sql.contains("NULL AS comment"));
            return query;
        });
        when(query.executeQuery()).thenReturn(listRows, singleRows);

        assertEquals(supportsComment ? "native comment" : null, database.queryTableList(connection, "sample").getFirst().getRemarks());
        assertEquals(supportsComment ? "native comment" : null, database.queryTableMeta(connection, "sample", "example").getRemarks());
        verify(connection, times(2)).createStatement();
        verify(query, times(2)).setString(1, "sample");
        verify(query).setString(2, "example");
        verify(connection, never()).close();
        verify(connection, never()).commit();
    }

    @Test void clickhouseProbeAuthorizationFailureCannotBecomeAnEmptyCommentSuccess() throws Exception {
        var database = mock(DatabaseClickhouseImpl.class, CALLS_REAL_METHODS);
        Connection connection = mock(Connection.class);
        Statement probe = mock(Statement.class);
        when(connection.createStatement()).thenReturn(probe);
        when(probe.executeQuery(anyString())).thenThrow(new SQLException("denied", "42501"));
        assertThrows(RuntimeException.class, () -> database.queryTableList(connection, "sample"));
        assertThrows(RuntimeException.class, () -> database.queryTableMeta(connection, "sample", "example"));
        verify(connection, never()).prepareStatement(anyString());
    }

    @Test void dmRejectsUnknownStringLengthsAndUnverifiedLargeKeysWithoutShorteningThem() {
        var database = mock(DatabaseDmImpl.class, CALLS_REAL_METHODS);
        for (int length : new int[]{-1, 0, 2048, Integer.MAX_VALUE}) {
            ColumnMetaData column = column(ColumnMetaData.TYPE_STRING, length, 0);
            assertThrows(IllegalArgumentException.class, () -> database.getFieldDefinition(column, List.of("key"), false, false, false));
            assertFalse(database.canCreateIndex(column));
            assertEquals(length, column.getLength());
        }
        assertTrue(database.getFieldDefinition(column(ColumnMetaData.TYPE_STRING, 128, 0), List.of("key"), false, false, false).contains("VARCHAR(128)"));
    }

    @Test void dmDecimalPrimaryKeysRemainDecimalAndPrecisionDoesNotSilentlyShrink() {
        var database = mock(DatabaseDmImpl.class, CALLS_REAL_METHODS);
        String sql = database.getFieldDefinition(column(ColumnMetaData.TYPE_BIGNUMBER, 20, 7), List.of("key"), false, false, false);
        assertTrue(sql.contains("NUMERIC(20, 7)"));
        assertFalse(sql.contains("BIGINT"));
        for (int[] shape : new int[][]{{39, 7}, {0, 0}, {20, -1}, {5, 6}}) {
            ColumnMetaData column = column(ColumnMetaData.TYPE_BIGNUMBER, shape[0], shape[1]);
            assertThrows(IllegalArgumentException.class, () -> database.getFieldDefinition(column, List.of(), false, false, false));
        }
    }

    private static ResultSet row(boolean comment) throws SQLException {
        ResultSet row = mock(ResultSet.class);
        when(row.next()).thenReturn(true, false);
        when(row.getString(1)).thenReturn("sample"); when(row.getString(2)).thenReturn("example");
        when(row.getString(3)).thenReturn(comment ? "native comment" : null); when(row.getString(4)).thenReturn("MergeTree");
        return row;
    }

    private static ColumnMetaData column(int type, int length, int scale) {
        ColumnMetaData column = mock(ColumnMetaData.class);
        when(column.getName()).thenReturn("key"); when(column.getType()).thenReturn(type);
        when(column.getLength()).thenReturn(length); when(column.getPrecision()).thenReturn(scale);
        return column;
    }
}

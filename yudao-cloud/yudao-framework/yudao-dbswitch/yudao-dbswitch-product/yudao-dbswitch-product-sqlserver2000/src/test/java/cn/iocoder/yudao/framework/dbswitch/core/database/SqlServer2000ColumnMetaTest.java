package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqlserver2000Impl;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLFeatureNotSupportedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

/** Unproven server versions must be refused before executing legacy SQL. Native acceptance is tested separately. */
class SqlServer2000ColumnMetaTest {
    @Test
    void columnMetaRefusesModernServerBeforeAnyStatement() throws Exception {
        var database = new DatabaseSqlserver2000Impl();
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("Microsoft SQL Server");
        when(metadata.getDatabaseProductVersion()).thenReturn("16.0.1000");
        var failure = assertThrows(SQLFeatureNotSupportedException.class,
                () -> database.queryTableColumnMeta(connection, "dbo", "sample"));
        assertEquals("0A000", failure.getSQLState());
        assertTrue(failure.getMessage().contains("8.00.760"), failure.getMessage());
        var only = assertThrows(SQLFeatureNotSupportedException.class,
                () -> database.queryTableColumnMetaOnly(connection, "dbo", "sample"));
        assertEquals("0A000", only.getSQLState());
        verify(connection, never()).createStatement();
        verify(connection, never()).prepareStatement(anyString());
    }
}

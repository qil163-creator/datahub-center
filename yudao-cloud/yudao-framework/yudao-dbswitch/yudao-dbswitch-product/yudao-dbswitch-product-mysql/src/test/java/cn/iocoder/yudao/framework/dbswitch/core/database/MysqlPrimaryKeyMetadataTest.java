package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMysqlImpl;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MysqlPrimaryKeyMetadataTest {
    @Test void usesBoundExactNamesAndKeepsKeySequenceRatherThanHashOrColumnOrder() throws Exception {
        var connection = mock(Connection.class); var statement = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getString("COLUMN_NAME")).thenReturn("z", "a");
        assertEquals(List.of("z", "a"), new DatabaseMysqlImpl().queryTablePrimaryKeys(connection, "schema'_%", "table'_%"));
        verify(connection).prepareStatement(argThat(sql -> sql.contains("KEY_COLUMN_USAGE")
                && sql.contains("ORDER BY ORDINAL_POSITION") && !sql.contains("schema'_%") && !sql.contains("table'_%")));
        verify(statement).setString(1, "schema'_%"); verify(statement).setString(2, "table'_%");
        verify(rows).close(); verify(statement).close(); verify(connection, never()).close();
    }

    @Test void tableWithoutPrimaryKeyReturnsAnEmptyList() throws Exception {
        var connection = mock(Connection.class); var statement = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        assertEquals(List.of(), new DatabaseMysqlImpl().queryTablePrimaryKeys(connection, "schema", "keyless"));
        verify(rows).close(); verify(statement).close();
    }
}

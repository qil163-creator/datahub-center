package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseHiveImpl;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HiveMetadataTest {
    @Test void catalogFailureDoesNotCloseTheCallerConnection() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenThrow(new SQLException("version unread"));
        var failure = assertThrows(RuntimeException.class,
                () -> new DatabaseHiveImpl().queryTableColumnMeta(connection, "schema", "sample"));
        assertInstanceOf(SQLException.class, failure.getCause());
        verify(connection, never()).close();
        verify(connection, never()).commit();
        verify(connection, never()).rollback();
    }
}

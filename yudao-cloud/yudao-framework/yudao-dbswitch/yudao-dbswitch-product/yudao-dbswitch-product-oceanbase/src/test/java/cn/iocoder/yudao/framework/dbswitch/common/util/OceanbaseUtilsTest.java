package cn.iocoder.yudao.framework.dbswitch.common.util;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOceanbaseImpl;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.OceanbaseDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.oceanbase.OceanbaseDatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.oceanbase.OceanbaseWriterImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OceanbaseUtilsTest {
    @ParameterizedTest @CsvSource({"MYSQL,true", "mysql,true", "ORACLE,false", "oracle,false"})
    void onlyExactKnownModesAreAccepted(String mode, boolean mysql) throws Exception {
        var connection = mock(Connection.class); var statement = mock(PreparedStatement.class); var rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows); when(rows.next()).thenReturn(true, false);
        when(rows.getString(2)).thenReturn(mode);
        assertEquals(mysql, OceanbaseUtils.isOceanBaseUseMysqlMode(connection));
        verify(statement).setQueryTimeout(5); verify(statement).setMaxRows(2);
        verify(rows).close(); verify(statement).close();
        verify(connection, never()).close(); verify(connection, never()).setAutoCommit(anyBoolean());
        verify(connection, never()).commit(); verify(connection, never()).rollback();
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"MYSQL_UNKNOWN", "NOT_MYSQL", "OBORACLE", " ", " MYSQL "})
    void missingAndUnknownModeNeverSelectsAnotherDialect(String mode) throws Exception {
        var connection = mock(Connection.class); var statement = mock(PreparedStatement.class); var rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows); when(rows.next()).thenReturn(true, false);
        when(rows.getString(2)).thenReturn(mode);
        assertThrows(IllegalStateException.class, () -> OceanbaseUtils.isOceanBaseUseMysqlMode(connection));
        verify(rows).close(); verify(statement).close(); verify(connection, never()).close();
    }

    @Test void absentAndAmbiguousRowsAreNotAProofOfMode() throws Exception {
        for (boolean first : new boolean[]{false, true}) {
            var connection = mock(Connection.class); var statement = mock(PreparedStatement.class); var rows = mock(ResultSet.class);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeQuery()).thenReturn(rows); when(rows.next()).thenReturn(first, true, false);
            when(rows.getString(2)).thenReturn("MYSQL");
            assertThrows(IllegalStateException.class, () -> OceanbaseUtils.isOceanBaseUseMysqlMode(connection));
        }
    }

    @Test void connectionFailureDoesNotChooseMysql() throws Exception {
        var source = mock(DataSource.class);
        var failure = new SQLTimeoutException("synthetic timeout");
        when(source.getConnection()).thenThrow(failure);
        var thrown = assertThrows(IllegalStateException.class, () -> OceanbaseUtils.isOceanBaseUseMysqlMode(source));
        assertSame(failure, thrown.getCause());
    }

    @Test void aBorrowedConnectionIsClosedWhenDetectionFails() throws Exception {
        var source = mock(DataSource.class); var connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        var failure = new SQLException("synthetic denied", "42000");
        when(connection.prepareStatement(anyString())).thenThrow(failure);
        var thrown = assertThrows(IllegalStateException.class, () -> OceanbaseUtils.isOceanBaseUseMysqlMode(source));
        assertSame(failure, thrown.getCause()); verify(connection).close();
    }

    @Test void metadataAndWriteConsumersCannotProceedWithAnUnverifiedMode() throws Exception {
        var source = mock(DataSource.class); var connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenThrow(new SQLTimeoutException("synthetic probe timeout"));
        assertThrows(IllegalStateException.class, () -> new DatabaseOceanbaseImpl().queryTableList(connection, "example"));
        assertThrows(IllegalStateException.class, () -> new OceanbaseWriterImpl(source));
        assertThrows(IllegalStateException.class, () -> new OceanbaseDatabaseSyncImpl(source));
        assertThrows(IllegalStateException.class, () -> new OceanbaseDatabaseOperator(source));
        verify(connection, never()).createStatement();
        verify(connection, never()).setAutoCommit(anyBoolean());
        verify(connection, never()).commit(); verify(connection, never()).rollback();
    }
}


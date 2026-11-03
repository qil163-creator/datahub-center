package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.IDatabaseInterface;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOceanbaseImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedIntegerKeyProjection;
import cn.iocoder.yudao.framework.dbswitch.core.model.BoundedKeyComponentProjection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OceanbaseRelationIdentityTest {
    @ParameterizedTest @ValueSource(strings = {"sample;SELECT 1", "sample -- x", "schema.sample extra", "schema.sample.*",
            "schema.sample.more", "\"schema\".sample", "中文表", "sample/*x*/"})
    void namesOutsideVerifiedSubsetFailBeforeJdbc(String reference) {
        var connection = mock(Connection.class);
        assertThrows(SQLFeatureNotSupportedException.class, () -> OceanbaseRelationIdentity.fingerprint(connection, reference));
        verifyNoInteractions(connection);
    }

    @Test void inheritingClassAndConnectionlessCallsCannotImplyModeSupport() {
        var database = mock(DatabaseOceanbaseImpl.class, CALLS_REAL_METHODS);
        when(database.getDatabaseType()).thenReturn(ProductTypeEnum.UNKNOWN);
        var connection = mock(Connection.class);
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.queryRelationFingerprint(connection, "sample"));
        assertThrows(UnsupportedOperationException.class, () -> database.integerKeyProjection(connection, "k", "id"));
        assertThrows(UnsupportedOperationException.class, () -> database.keyComponentProjection(connection, "k", "id", 256));
        when(database.getDatabaseType()).thenReturn(ProductTypeEnum.OCEANBASE);
        assertThrows(UnsupportedOperationException.class, () -> database.integerKeyProjection("k", "id"));
        assertThrows(UnsupportedOperationException.class, () -> database.keyComponentProjection("k", "id", 256));
        verifyNoInteractions(connection);
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void incompleteOrUnverifiedSessionStopsBeforeReadingSystemTables(int invalidColumn) throws Exception {
        var connection = mock(Connection.class); var statement = mock(Statement.class); var rows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement); when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        String[] fields = {"5.7.25-OceanBase_CE-v4.3.5.6", "example", "root@%", "NONE", "MYSQL", "1", "STRONG", "1"};
        fields[invalidColumn - 1] = switch (invalidColumn) {
            case 1 -> "5.7.25-OceanBase_CE-v4.3.5.5";
            case 2 -> "complex.schema";
            case 3 -> "";
            case 4 -> "role\0ambiguous";
            case 5 -> "ORACLE";
            case 6, 8 -> "0";
            default -> "WEAK";
        };
        when(rows.getString(anyInt())).thenAnswer(call -> fields[call.getArgument(0, Integer.class) - 1]);
        assertThrows(SQLFeatureNotSupportedException.class, () -> OceanbaseRelationIdentity.fingerprint(connection, "sample"));
        verify(statement, times(1)).executeQuery(anyString());
        verify(statement).setMaxRows(2); verify(statement).setQueryTimeout(5); verify(statement).close(); verify(rows).close();
        verify(connection, never()).prepareStatement(anyString()); verifyUnchanged(connection);
    }

    @ParameterizedTest @ValueSource(strings = {"ORACLE", "", "mysql", "UNKNOWN"})
    void unverifiedProjectionModeCannotBeReusedOrCached(String mode) throws Exception {
        var connection = mock(Connection.class); var statement = mock(Statement.class); var rows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement); when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString(1)).thenReturn("5.7.25-OceanBase_CE-v4.3.5.6"); when(rows.getString(2)).thenReturn(mode);
        assertThrows(SQLFeatureNotSupportedException.class, () -> OceanbaseRelationIdentity.requireVerifiedMysqlMode(connection));
        verify(statement).setMaxRows(2); verify(statement).setQueryTimeout(5); verify(statement).close(); verify(rows).close();
        verifyUnchanged(connection);
    }

    @Test void metadataFailureDoesNotGuessMysqlOrChangeConnection() throws Exception {
        var connection = mock(Connection.class); var statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        SQLException denied = new SQLException("synthetic permission denial", "42501");
        when(statement.executeQuery(anyString())).thenThrow(denied);
        assertSame(denied, assertThrows(SQLException.class, () -> OceanbaseRelationIdentity.requireVerifiedMysqlMode(connection)));
        assertSame(denied, assertThrows(SQLException.class, () -> OceanbaseRelationIdentity.fingerprint(connection, "sample")));
        verify(statement, times(2)).close(); verifyUnchanged(connection);
    }

    @Test void connectionAwareSpiPreservesExistingImplementations() throws Exception {
        var database = mock(IDatabaseInterface.class, CALLS_REAL_METHODS); var connection = mock(Connection.class);
        var integer = mock(BoundedIntegerKeyProjection.class); var component = mock(BoundedKeyComponentProjection.class);
        doReturn(integer).when(database).integerKeyProjection("k", "id");
        doReturn(component).when(database).keyComponentProjection("k", "id", 256);
        assertSame(integer, database.integerKeyProjection(connection, "k", "id"));
        assertSame(component, database.keyComponentProjection(connection, "k", "id", 256)); verifyNoInteractions(connection);
    }

    private static void verifyUnchanged(Connection connection) throws SQLException {
        verify(connection, never()).setAutoCommit(anyBoolean()); verify(connection, never()).setReadOnly(anyBoolean());
        verify(connection, never()).setCatalog(anyString()); verify(connection, never()).setSchema(anyString());
        verify(connection, never()).commit(); verify(connection, never()).rollback(); verify(connection, never()).close();
    }
}

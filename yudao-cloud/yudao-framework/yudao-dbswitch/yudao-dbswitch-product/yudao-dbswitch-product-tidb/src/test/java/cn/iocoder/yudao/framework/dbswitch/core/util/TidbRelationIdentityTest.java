package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseTidbImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TidbRelationIdentityTest {
    @ParameterizedTest @ValueSource(strings = {"sample;SELECT 1", "sample -- x", "schema.sample extra", "schema.sample.*",
            "schema.sample.more", "\"schema\".sample", "中文表", "sample/*x*/"})
    void namesOutsideVerifiedSubsetFailBeforeJdbc(String reference) {
        var connection = mock(Connection.class);
        assertThrows(SQLFeatureNotSupportedException.class, () -> TidbRelationIdentity.fingerprint(connection, reference));
        verifyNoInteractions(connection);
    }

    @Test void inheritingClassCannotImplySupportForOtherProducts() {
        var database = mock(DatabaseTidbImpl.class, CALLS_REAL_METHODS);
        when(database.getDatabaseType()).thenReturn(ProductTypeEnum.UNKNOWN);
        var connection = mock(Connection.class);
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.queryRelationFingerprint(connection, "sample"));
        assertThrows(UnsupportedOperationException.class, () -> database.integerKeyProjection("k", "id"));
        assertThrows(UnsupportedOperationException.class, () -> database.keyComponentProjection("k", "id", 256));
        verifyNoInteractions(connection);
    }

    @ParameterizedTest @ValueSource(ints = {1, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
    void incompleteOrUnverifiedSessionStopsBeforeReadingSystemTables(int invalidColumn) throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(java.sql.Statement.class);
        var rows = mock(java.sql.ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        String[] fields = {"Release Version: v8.5.6\nEdition: Community\nStore: tikv", "example", "root@%", "NONE",
                "2", "", "0", "leader", "0", "", "0", "1"};
        fields[invalidColumn - 1] = switch (invalidColumn) {
            case 1 -> "Release Version: v8.5.5\nEdition: Community\nStore: tikv";
            case 3 -> "";
            case 4 -> "role\0ambiguous";
            case 5, 12 -> "0";
            default -> "unverified";
        };
        when(rows.getString(anyInt())).thenAnswer(call -> fields[call.getArgument(0, Integer.class) - 1]);
        when(rows.getInt(5)).thenReturn(Integer.parseInt(fields[4]));
        assertThrows(SQLFeatureNotSupportedException.class, () -> TidbRelationIdentity.fingerprint(connection, "sample"));
        verify(statement, times(1)).executeQuery(anyString());
        verify(statement).setMaxRows(2); verify(statement).setQueryTimeout(5); verify(statement).close();
        verify(connection, never()).prepareStatement(anyString());
        verify(connection, never()).setAutoCommit(anyBoolean());
        verify(connection, never()).commit(); verify(connection, never()).rollback(); verify(connection, never()).close();
    }
}

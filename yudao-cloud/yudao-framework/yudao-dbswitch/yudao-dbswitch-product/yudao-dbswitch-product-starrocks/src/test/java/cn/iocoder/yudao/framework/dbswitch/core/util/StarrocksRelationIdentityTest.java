package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseStarrocksImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StarrocksRelationIdentityTest {
    @ParameterizedTest @ValueSource(strings = {"sample;SELECT 1", "sample -- x", "schema.sample extra", "schema.sample.*",
            "default_catalog.schema.sample", "`schema`.sample", "中文表", "sample/*x*/"})
    void unverifiedReferencesFailBeforeJdbc(String reference) {
        var connection = mock(Connection.class);
        assertThrows(SQLFeatureNotSupportedException.class, () -> StarrocksRelationIdentity.fingerprint(connection, reference));
        verifyNoInteractions(connection);
    }
    @Test void inheritedMethodsCannotQualifyOtherProducts() {
        assertTrue(new DatabaseStarrocksImpl().integerKeyProjection(null, "id").requiresExplicitOuterLimit());
        var database = mock(DatabaseStarrocksImpl.class, CALLS_REAL_METHODS);
        when(database.getDatabaseType()).thenReturn(ProductTypeEnum.UNKNOWN);
        var connection = mock(Connection.class);
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.queryRelationFingerprint(connection, "sample"));
        assertThrows(UnsupportedOperationException.class, () -> database.integerKeyProjection(null, "id"));
        assertThrows(UnsupportedOperationException.class, () -> database.keyComponentProjection(null, "id", 256));
        verifyNoInteractions(connection);
    }
    @Test void rangerOrMissingAccessControlCannotProduceNativeProof() throws Exception {
        var connection = mock(Connection.class); var statement = mock(Statement.class);
        var cluster = mock(ResultSet.class); var access = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SHOW FRONTENDS")).thenReturn(cluster);
        when(cluster.next()).thenReturn(true, false);
        when(cluster.getString("ClusterId")).thenReturn("123"); when(cluster.getString("Version")).thenReturn("3.5.21-test");
        when(cluster.getString("Role")).thenReturn("LEADER"); when(cluster.getBoolean("Alive")).thenReturn(true);
        when(cluster.getBoolean("Join")).thenReturn(true);
        when(statement.execute("ADMIN SHOW FRONTEND CONFIG LIKE 'access_control'")).thenReturn(true);
        when(statement.getResultSet()).thenReturn(access);
        when(access.next()).thenReturn(true); when(access.getString("Key")).thenReturn("access_control");
        when(access.getString("Value")).thenReturn("ranger");
        assertThrows(SQLFeatureNotSupportedException.class, () -> StarrocksRelationIdentity.fingerprint(connection, "db.sample"));
        verify(statement, never()).executeQuery(startsWith("SELECT CATALOG"));
        verify(connection, never()).prepareStatement(anyString());
    }
}

package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMysqlImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class RelationIdentityCapabilityTest {
    @ParameterizedTest
    @EnumSource(ProductTypeEnum.class)
    void legacySpiDefaultsToExplicitUnsupportedWithoutTouchingConnection(ProductTypeEnum product) {
        var database = mock(IDatabaseInterface.class, CALLS_REAL_METHODS);
        when(database.getDatabaseType()).thenReturn(product);
        var connection = mock(Connection.class);
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.queryRelationFingerprint(connection, "table1"));
        assertThrows(UnsupportedOperationException.class, () -> database.textColumnExpression("id"));
        assertThrows(UnsupportedOperationException.class, () -> database.integerKeyProjection("k", "id"));
        assertThrows(UnsupportedOperationException.class, () -> database.textKeyProjection("k", "id", 256));
        assertThrows(UnsupportedOperationException.class, () -> database.keyComponentProjection("k", "id", 256));
        assertThrows(UnsupportedOperationException.class, () -> database.textAndIntegerKeyComponentProjection(connection, "k", "id", 256));
        verifyNoInteractions(connection);
    }

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = "MYSQL", mode = EnumSource.Mode.EXCLUDE)
    void mysqlInheritanceDoesNotClaimCompatibility(ProductTypeEnum product) {
        var database = mock(DatabaseMysqlImpl.class, CALLS_REAL_METHODS);
        when(database.getDatabaseType()).thenReturn(product);
        var connection = mock(Connection.class);
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.queryRelationFingerprint(connection, "table1"));
        assertThrows(UnsupportedOperationException.class, () -> database.integerKeyProjection("k", "id"));
        assertThrows(UnsupportedOperationException.class, () -> database.textKeyProjection("k", "id", 256));
        assertThrows(UnsupportedOperationException.class, () -> database.keyComponentProjection("k", "id", 256));
        verifyNoInteractions(connection);
    }

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = "POSTGRESQL", mode = EnumSource.Mode.EXCLUDE)
    void postgresInheritanceDoesNotClaimCompatibility(ProductTypeEnum product) {
        var database = mock(DatabasePostgresImpl.class, CALLS_REAL_METHODS);
        when(database.getDatabaseType()).thenReturn(product);
        var connection = mock(Connection.class);
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.queryRelationFingerprint(connection, "table1"));
        verifyNoInteractions(connection);
    }
}

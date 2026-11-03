package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DorisRelationIdentityTest {
    @ParameterizedTest
    @ValueSource(strings = {"sample alias", "a.b.c", "sample;SELECT 1", "sample/*x*/", "sample#x", "\"sample\"", "'sample'", "[sample]"})
    void rejectsSqlInsteadOfSingleRelation(String reference) {
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.parseRelationReference(
                ProductTypeEnum.DORIS, reference, "schema"));
    }

    @Test
    void quotesExactNamesWithoutEnablingAuthorizationSql() {
        var plan = RestrictedSelectGuard.parseRelationReference(ProductTypeEnum.DORIS, "`Db.X`.`T``.x`", "unused");
        assertEquals("Db.X", plan.getSchemaName());
        assertEquals("T`.x", plan.getTableName());
        assertEquals("SELECT * FROM `Db.X`.`T``.x`", plan.getNormalizedSql());
        assertThrows(UnsupportedOperationException.class, () -> RestrictedSelectGuard.parseTableReference(ProductTypeEnum.DORIS, "t", "s"));
        assertThrows(UnsupportedOperationException.class, () -> RestrictedSelectGuard.parseBound(ProductTypeEnum.DORIS, "SELECT * FROM t", "s"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.7.99", "MySQL", "doris-2.1.7", "doris-4.1.0", ""})
    void protocolOrUnverifiedVersionDoesNotCountAsProductEvidence(String version) throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var rows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SHOW FRONTENDS")).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString("ClusterId")).thenReturn("123");
        when(rows.getString("Version")).thenReturn(version);
        assertThrows(SQLFeatureNotSupportedException.class, () -> DorisRelationIdentity.fingerprint(connection, "sample"));
        verify(statement).setQueryTimeout(5);
        verify(statement).setMaxRows(65);
        verify(statement).close();
        verify(rows).close();
        verify(connection, never()).close();
        verify(connection, never()).commit();
        verify(connection, never()).rollback();
    }

    @Test
    void infrastructureFailurePropagatesAndDoesNotBecomeUnsupported() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        SQLException failure = new SQLException("test timeout", "HYT00");
        when(statement.executeQuery("SHOW FRONTENDS")).thenThrow(failure);
        assertSame(failure, assertThrows(SQLException.class, () -> DorisRelationIdentity.fingerprint(connection, "t")));
        verify(statement).close();
        verify(connection, never()).close();
    }
}

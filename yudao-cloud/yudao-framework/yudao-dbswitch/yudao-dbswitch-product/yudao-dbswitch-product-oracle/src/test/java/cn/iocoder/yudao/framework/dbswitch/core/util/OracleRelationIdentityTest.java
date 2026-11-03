package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOracleImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OracleRelationIdentityTest {
    @ParameterizedTest @ValueSource(strings = {"SAMPLE", "schema.sample extra", "schema.sample.more", "\"SCHEMA\".SAMPLE",
            "中文.表", "schema.sample;SELECT 1", "schema.sample -- x", "schema.sample/*x*/"})
    void namesOutsideVerifiedSubsetFailBeforeJdbc(String reference) {
        var connection = mock(Connection.class);
        assertThrows(SQLFeatureNotSupportedException.class, () -> OracleRelationIdentity.fingerprint(connection, reference));
        verifyNoInteractions(connection);
    }

    @Test void dictionaryPermissionErrorsPropagate() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenThrow(new SQLException("catalog hidden", "42000", 942));
        var denied = assertThrows(SQLException.class, () -> OracleRelationIdentity.fingerprint(connection, "Q.SAMPLE"));
        assertFalse(denied instanceof SQLFeatureNotSupportedException);
        assertEquals("42000", denied.getSQLState());
        assertEquals(942, denied.getErrorCode());
    }

    @Test void inheritingClassCannotImplySupportForOtherProducts() {
        var database = mock(DatabaseOracleImpl.class, CALLS_REAL_METHODS);
        when(database.getDatabaseType()).thenReturn(ProductTypeEnum.UNKNOWN);
        var connection = mock(Connection.class);
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.queryRelationFingerprint(connection, "Q.SAMPLE"));
        verifyNoInteractions(connection);
    }

    @Test void verifiedProductUsesFetchFirstWithoutChangingBindingOrSnapshots() throws Exception {
        var oracle = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.ORACLE);
        assertEquals("SELECT x FROM t FETCH FIRST 8 ROWS ONLY", oracle.limitGeneratedSelect("SELECT x FROM t", 8));
        assertFalse(oracle.limitGeneratedSelect("SELECT x FROM t", 8).contains(" LIMIT "));
        assertEquals("\"ID\"", RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.ORACLE, "ID"));
        assertEquals("\"MiX\"", RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.ORACLE, "MiX"));
        assertThrows(UnsupportedOperationException.class, () ->
                RestrictedSelectGuard.parseBound(ProductTypeEnum.ORACLE, "SELECT id FROM t", "SCHEMA"));
        assertThrows(UnsupportedOperationException.class, () ->
                RestrictedSelectGuard.parseTableReference(ProductTypeEnum.ORACLE, "SCHEMA.SAMPLE", "SCHEMA"));
        assertThrows(UnsupportedOperationException.class, () ->
                RestrictedSelectGuard.parseRelationReference(ProductTypeEnum.ORACLE, "SCHEMA.SAMPLE", "SCHEMA"));
        var connection = mock(Connection.class);
        assertThrows(SQLFeatureNotSupportedException.class, () -> oracle.openReadSnapshot(connection, 5));
        assertThrows(SQLFeatureNotSupportedException.class, () ->
                oracle.queryRelationFingerprint(connection, "Q.SAMPLE", new SourceGenerationPin(
                        "q11pin", "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222")));
        verifyNoInteractions(connection);
    }
}

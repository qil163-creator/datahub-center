package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOscarImpl;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OscarRelationIdentityTest {
    @ParameterizedTest @ValueSource(strings = {"sample;SELECT 1", "sample -- x", "schema.sample extra", "schema.sample.*",
            "schema.sample.more", "\"schema\".sample", "中文表", "sample/*x*/"})
    void namesOutsideVerifiedSubsetFailBeforeJdbc(String reference) {
        var connection = mock(Connection.class);
        assertThrows(SQLFeatureNotSupportedException.class, () -> OscarRelationIdentity.fingerprint(connection, reference));
        verifyNoInteractions(connection);
    }

    @Test void inheritingClassCannotImplySupportForOtherProducts() {
        var database = mock(DatabaseOscarImpl.class, CALLS_REAL_METHODS);
        when(database.getDatabaseType()).thenReturn(ProductTypeEnum.UNKNOWN);
        var connection = mock(Connection.class);
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.queryRelationFingerprint(connection, "sample"));
        assertThrows(UnsupportedOperationException.class, () -> database.integerKeyProjection("k", "id"));
        assertThrows(UnsupportedOperationException.class, () -> database.keyComponentProjection("k", "id", 256));
        verifyNoInteractions(connection);
    }

    @Test void verifiedProductUsesLimitAndUppercaseQuotesWithoutChangingOtherDialects() {
        var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.OSCAR);
        assertEquals("SELECT x FROM t LIMIT 8", database.limitGeneratedSelect("SELECT x FROM t", 8));
        assertThrows(UnsupportedOperationException.class, () ->
                cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.DB2).limitGeneratedSelect("SELECT x FROM t", 8));
        assertEquals("\"ID\"", RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.OSCAR, "id"));
        assertEquals("\"MIX\"", RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.OSCAR, "MiX"));
        assertThrows(IllegalArgumentException.class, () ->
                RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.OSCAR, "bad-name"));
        assertThrows(UnsupportedOperationException.class, () ->
                RestrictedSelectGuard.parseBound(ProductTypeEnum.OSCAR, "SELECT id FROM t", "schema"));
        assertThrows(UnsupportedOperationException.class, () ->
                RestrictedSelectGuard.parseTableReference(ProductTypeEnum.OSCAR, "schema.sample", "schema"));
        assertThrows(UnsupportedOperationException.class, () ->
                RestrictedSelectGuard.parseRelationReference(ProductTypeEnum.OSCAR, "schema.sample", "schema"));
        var projection = database.integerKeyProjection("k", "id");
        assertEquals("CASE WHEN LENGTHB(CAST(\"K\".\"ID\" AS VARCHAR(40))) <= 21 "
                + "THEN CAST(\"K\".\"ID\" AS VARCHAR(40)) ELSE NULL END", projection.groupSql());
        assertEquals("NULLIF(\"K\".\"ID\", \"K\".\"ID\")", projection.typeSql());
        assertTrue(database.keyComponentProjection(null, "id", 8).groupSql().contains("<= 8"));
        assertThrows(IllegalArgumentException.class, () -> database.keyComponentProjection("k", "id", 0));
        var connection = mock(Connection.class);
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.openReadSnapshot(connection, 5));
        verifyNoInteractions(connection);
    }
}

package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseKingbaseImpl;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KingbaseRelationIdentityTest {
    @ParameterizedTest @ValueSource(strings = {"sample;SELECT 1", "sample -- x", "schema.sample extra", "schema.sample.*",
            "schema.sample.more", "\"schema\".sample", "中文表", "sample/*x*/"})
    void namesOutsideVerifiedSubsetFailBeforeJdbc(String reference) {
        var connection = mock(Connection.class);
        assertThrows(SQLFeatureNotSupportedException.class, () -> KingbaseRelationIdentity.fingerprint(connection, reference));
        verifyNoInteractions(connection);
    }

    @Test void inheritingClassCannotImplySupportForOtherProducts() {
        var database = mock(DatabaseKingbaseImpl.class, CALLS_REAL_METHODS);
        when(database.getDatabaseType()).thenReturn(ProductTypeEnum.UNKNOWN);
        var connection = mock(Connection.class);
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.queryRelationFingerprint(connection, "sample"));
        assertThrows(UnsupportedOperationException.class, () -> database.integerKeyProjection("k", "id"));
        assertThrows(UnsupportedOperationException.class, () -> database.keyComponentProjection("k", "id", 256));
        verifyNoInteractions(connection);
    }

    @Test void verifiedProductUsesLimitAndOrdinaryQuotesWithoutChangingOtherDialects() {
        var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.KINGBASE);
        assertEquals("SELECT x FROM t LIMIT 8", database.limitGeneratedSelect("SELECT x FROM t", 8));
        assertThrows(UnsupportedOperationException.class, () ->
                cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.DB2).limitGeneratedSelect("SELECT x FROM t", 8));
        assertEquals("\"id\"", RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.KINGBASE, "id"));
        assertEquals("\"MiX\"", RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.KINGBASE, "MiX"));
        assertThrows(IllegalArgumentException.class, () ->
                RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.KINGBASE, "bad-name"));
        assertThrows(UnsupportedOperationException.class, () ->
                RestrictedSelectGuard.parseBound(ProductTypeEnum.KINGBASE, "SELECT id FROM t", "schema"));
        assertThrows(UnsupportedOperationException.class, () ->
                RestrictedSelectGuard.parseTableReference(ProductTypeEnum.KINGBASE, "schema.sample", "schema"));
        assertThrows(UnsupportedOperationException.class, () ->
                RestrictedSelectGuard.parseRelationReference(ProductTypeEnum.KINGBASE, "schema.sample", "schema"));
        var projection = database.integerKeyProjection("k", "id");
        assertEquals("CASE WHEN pg_catalog.octet_length(CAST(\"k\".\"id\" AS pg_catalog.text)) <= 21 "
                + "THEN CAST(\"k\".\"id\" AS pg_catalog.text) ELSE NULL END", projection.groupSql());
        assertEquals("NULLIF(\"k\".\"id\", \"k\".\"id\")", projection.typeSql());
        assertTrue(database.keyComponentProjection(null, "id", 8).groupSql().contains("<= 8"));
        assertThrows(IllegalArgumentException.class, () -> database.keyComponentProjection("k", "id", 0));
        var connection = mock(Connection.class);
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.openReadSnapshot(connection, 5));
        verifyNoInteractions(connection);
    }
}

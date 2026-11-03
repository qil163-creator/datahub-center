package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseHighgoImpl;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.SQLFeatureNotSupportedException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HighgoRelationIdentityTest {
    @ParameterizedTest @ValueSource(strings = {"sample;SELECT 1", "sample -- x", "schema.sample extra", "schema.sample.*",
            "schema.sample.more", "\"schema\".sample", "中文表", "sample/*x*/"})
    void namesOutsideVerifiedSubsetFailBeforeJdbc(String reference) {
        var connection = mock(Connection.class);
        assertThrows(SQLFeatureNotSupportedException.class, () -> HighgoRelationIdentity.fingerprint(connection, reference));
        verifyNoInteractions(connection);
    }

    @Test void inheritingClassCannotImplySupportForOtherProducts() {
        var database = mock(DatabaseHighgoImpl.class, CALLS_REAL_METHODS);
        when(database.getDatabaseType()).thenReturn(ProductTypeEnum.UNKNOWN);
        var connection = mock(Connection.class);
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.queryRelationFingerprint(connection, "sample"));
        assertThrows(UnsupportedOperationException.class, () -> database.integerKeyProjection("k", "id"));
        assertThrows(UnsupportedOperationException.class, () -> database.keyComponentProjection("k", "id", 256));
        verifyNoInteractions(connection);
    }

    @Test void verifiedProductUsesLimitWithoutChangingOtherDialects() throws Exception {
        assertEquals("SELECT x FROM t LIMIT 8",
                cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.HIGHGO).limitGeneratedSelect("SELECT x FROM t", 8));
        assertThrows(UnsupportedOperationException.class, () ->
                cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.DB2).limitGeneratedSelect("SELECT x FROM t", 8));
        assertEquals("\"id\"", RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.HIGHGO, "id"));
        assertThrows(IllegalArgumentException.class, () ->
                RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.HIGHGO, "bad-name"));
        assertThrows(UnsupportedOperationException.class, () ->
                RestrictedSelectGuard.parseBound(ProductTypeEnum.HIGHGO, "SELECT id FROM t", "schema"));
        assertThrows(UnsupportedOperationException.class, () ->
                RestrictedSelectGuard.parseTableReference(ProductTypeEnum.HIGHGO, "schema.sample", "schema"));
        assertThrows(UnsupportedOperationException.class, () ->
                RestrictedSelectGuard.parseRelationReference(ProductTypeEnum.HIGHGO, "schema.sample", "schema"));
        var connection = mock(Connection.class);
        assertThrows(SQLFeatureNotSupportedException.class, () ->
                cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.HIGHGO).openReadSnapshot(connection, 5));
        verifyNoInteractions(connection);
    }
}

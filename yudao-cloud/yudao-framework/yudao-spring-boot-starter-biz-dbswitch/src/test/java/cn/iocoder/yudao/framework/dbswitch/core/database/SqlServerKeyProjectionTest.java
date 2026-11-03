package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqlServerKeyProjectionTest {
    private final IDatabaseInterface database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.SQLSERVER);

    @ParameterizedTest @CsvSource({"smallint,5,5", "SMALLINT,5,5", "int,4,4", "bigint,-5,-5",
            "bigint,4,0", "decimal,3,0", "varchar,12,0", "tinyint,-6,0", "float,8,0", "bit,-7,0"})
    void onlyNativeSignedIntegersAreEligible(String name, int jdbc, int expected) throws Exception {
        var metadata = mock(ResultSetMetaData.class);
        when(metadata.getColumnTypeName(2)).thenReturn(name); when(metadata.getColumnType(2)).thenReturn(jdbc);
        when(metadata.isSigned(2)).thenReturn(true);
        assertEquals(expected, database.integerKeyProjection("k", "id").jdbcType(metadata, 2));
        when(metadata.isSigned(2)).thenReturn(false);
        assertEquals(0, database.integerKeyProjection("k", "id").jdbcType(metadata, 2));
    }

    @Test void renderedSqlUsesNativeLimitsAndUncastWitness() {
        assertEquals("SELECT TOP (11) x FROM t ORDER BY x", database.limitGeneratedSelect("SELECT x FROM t ORDER BY x", 11));
        assertEquals("NULLIF([k].[id], [k].[id])", database.integerKeyProjection("k", "id").typeSql());
        assertTrue(database.integerKeyProjection(null, "id").requiresExplicitOuterLimit());
        assertFalse(database.integerKeyProjection(null, "id").valueSql().contains("null."));
        assertTrue(database.keyComponentProjection(null, "id", 3).valueSql().contains("<= 3"));
        assertEquals("[a]]b].[t'able]", database.getQuotedSchemaTableCombination("a]b", "t'able"));
        assertThrows(IllegalArgumentException.class, () -> database.integerKeyProjection("k; DROP", "id"));
        assertThrows(IllegalArgumentException.class, () -> database.keyComponentProjection(null, "id]", 21));
        assertThrows(IllegalArgumentException.class, () -> database.keyComponentProjection(null, "id", 0));
        assertThrows(IllegalArgumentException.class, () -> database.keyComponentProjection(null, "id", 257));
        assertThrows(IllegalArgumentException.class, () -> database.limitGeneratedSelect("SELECT x", 0));
        assertThrows(IllegalArgumentException.class, () -> database.limitGeneratedSelect("DELETE t", 1));
        assertThrows(IllegalArgumentException.class, () -> database.limitGeneratedSelect("SELECT x", 1_000_002));
    }

    @Test void legacyProductAndUnverifiedVersionsDoNotInheritNewSupport() throws Exception {
        var old = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.SQLSERVER2000);
        assertThrows(UnsupportedOperationException.class, () -> old.integerKeyProjection("k", "id"));
        assertThrows(UnsupportedOperationException.class, () -> old.keyComponentProjection("k", "id", 21));
        assertThrows(UnsupportedOperationException.class, () -> old.textColumnExpression("id"));
        assertThrows(UnsupportedOperationException.class, () -> old.limitGeneratedSelect("SELECT id FROM t", 3));
        var connection = mock(Connection.class); var metadata = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metadata); when(metadata.getDatabaseProductName()).thenReturn("Microsoft SQL Server");
        when(metadata.getDatabaseMajorVersion()).thenReturn(15);
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.integerKeyProjection(connection, "k", "id"));
        when(metadata.getDatabaseMajorVersion()).thenReturn(16);
        assertNotNull(database.integerKeyProjection(connection, "k", "id"));
        when(metadata.getDatabaseProductName()).thenReturn("compatible");
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.keyComponentProjection(connection, "k", "id", 21));
        verify(connection, never()).close(); verify(connection, never()).setAutoCommit(anyBoolean());
    }

    @Test void existingLimitProductsKeepByteIdenticalSql() {
        for (var product : new ProductTypeEnum[]{ProductTypeEnum.MYSQL,ProductTypeEnum.MARIADB,ProductTypeEnum.POSTGRESQL,
                ProductTypeEnum.DORIS,ProductTypeEnum.HIVE,ProductTypeEnum.TIDB,ProductTypeEnum.STARROCKS}) {
            assertEquals("SELECT x FROM t LIMIT 8", cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(product).limitGeneratedSelect("SELECT x FROM t", 8));
        }
        assertThrows(UnsupportedOperationException.class, () -> cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.DB2)
                .limitGeneratedSelect("SELECT x FROM t", 8));
    }
}

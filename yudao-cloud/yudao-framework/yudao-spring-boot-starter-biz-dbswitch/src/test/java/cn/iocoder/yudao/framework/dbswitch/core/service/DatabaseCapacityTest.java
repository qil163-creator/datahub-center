package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMysqlImpl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.driver.JdbcConnectionProvider;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLException;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseCapacityTest {
    @Test void facadeUsesMetadataBudgetAndClosesConnectionOnFailure() throws Exception {
        var products = mock(DbSwitchProductRegistry.class);
        var dialect = mock(AbstractDatabase.class);
        var metadata = mock(JdbcConnectionProvider.class);
        var workload = mock(JdbcConnectionProvider.class);
        var connection = mock(Connection.class);
        when(products.metadata(ProductTypeEnum.POSTGRESQL)).thenReturn(dialect);
        when(metadata.open("jdbc:fixture:capacity", null, null)).thenReturn(connection);
        when(dialect.queryDatabaseUsedBytes(connection, 2)).thenThrow(new SQLException("unavailable"));
        var facade = new MetaDataByJdbcServiceImpl(ProductTypeEnum.POSTGRESQL, products, metadata, workload);
        assertThrows(IllegalStateException.class, () -> facade.queryDatabaseUsedBytes("jdbc:fixture:capacity", null, null, 1500));
        verify(connection).close();
        verify(connection).setNetworkTimeout(any(), eq(1500));
        verifyNoInteractions(workload);
    }

    @Test void rejectsUnboundedTimeoutBeforeOpeningConnection() {
        var products = mock(DbSwitchProductRegistry.class);
        var provider = mock(JdbcConnectionProvider.class);
        when(products.metadata(ProductTypeEnum.POSTGRESQL)).thenReturn(mock(AbstractDatabase.class));
        var facade = new MetaDataByJdbcServiceImpl(ProductTypeEnum.POSTGRESQL, products, provider);
        assertThrows(IllegalArgumentException.class, () -> facade.queryDatabaseUsedBytes("ignored", null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> facade.queryDatabaseUsedBytes("ignored", null, null, 60001));
        verifyNoInteractions(provider);
    }

    @Test void mysqlBindsExactCatalogWithoutTreatingItAsSqlOrPattern() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(connection.getCatalog()).thenReturn("odd_'%schema");
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getBigDecimal(1)).thenReturn(new BigDecimal("9007199254740993"));
        assertEquals(9007199254740993L, new DatabaseMysqlImpl().queryDatabaseUsedBytes(connection, 3));
        verify(statement).setString(1, "odd_'%schema");
        verify(statement).setQueryTimeout(3);
        verify(connection).prepareStatement("SELECT COALESCE(SUM(DATA_LENGTH + INDEX_LENGTH), 0) FROM information_schema.TABLES WHERE TABLE_SCHEMA = ?");
        verify(rows).close();
        verify(statement).close();
        verify(connection, never()).close();
    }

    @Test void compatibleProductsDoNotInheritUnprovenCapacitySupport() throws Exception {
        var connection = mock(Connection.class);
        var compatibleMysql = new DatabaseMysqlImpl() {
            @Override public ProductTypeEnum getDatabaseType() { return ProductTypeEnum.TIDB; }
        };
        var compatiblePostgres = new DatabasePostgresImpl() {
            @Override public ProductTypeEnum getDatabaseType() { return ProductTypeEnum.GREENPLUM; }
        };
        assertThrows(SQLFeatureNotSupportedException.class, () -> compatibleMysql.queryDatabaseUsedBytes(connection, 1));
        assertThrows(SQLFeatureNotSupportedException.class, () -> compatiblePostgres.queryDatabaseUsedBytes(connection, 1));
        verifyNoInteractions(connection);
    }
}

package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.driver.JdbcConnectionProvider;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqlServer2000FacadeAdmissionTest {
    @Test void admittedNativeReadFailureNeverFallsBackToLegacyUnknownMetadata() throws Exception {
        var connection = mock(Connection.class);
        var jdbc = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(jdbc);
        when(jdbc.getDatabaseProductName()).thenReturn("Microsoft SQL Server");
        when(jdbc.getDatabaseProductVersion()).thenReturn("8.00.760");
        var statement = mock(Statement.class);
        var rows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        // JDBC is admitted, but the authoritative native version disagrees.
        when(rows.getString(1)).thenReturn("16.0.1000");
        var database = mock(AbstractDatabase.class);
        var registry = mock(DbSwitchProductRegistry.class);
        when(registry.metadata(ProductTypeEnum.SQLSERVER2000)).thenReturn(database);
        var metadata = mock(JdbcConnectionProvider.class);
        var workload = mock(JdbcConnectionProvider.class);
        when(metadata.open("jdbc:fixture:sql2000", null, null)).thenReturn(connection);
        var facade = new MetaDataByJdbcServiceImpl(ProductTypeEnum.SQLSERVER2000, registry, metadata, workload);

        var failure = assertThrows(IllegalStateException.class,
                () -> facade.queryTableSchema("jdbc:fixture:sql2000", null, null, "dbo", "ordinary", 5000));

        assertInstanceOf(SQLFeatureNotSupportedException.class, failure.getCause());
        assertEquals("0A000", ((SQLFeatureNotSupportedException) failure.getCause()).getSQLState());
        verifyNoInteractions(database, workload);
        verify(connection, never()).prepareStatement(anyString());
        verify(connection).close();
        verify(connection, never()).commit(); verify(connection, never()).rollback();
    }
}

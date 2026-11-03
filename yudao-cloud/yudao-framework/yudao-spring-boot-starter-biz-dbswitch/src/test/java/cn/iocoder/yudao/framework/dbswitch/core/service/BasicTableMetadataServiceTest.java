package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.driver.JdbcConnectionProvider;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Provider/ownership contract tests; the Hive SPI substitute is not native Hive certification. */
class BasicTableMetadataServiceTest {
    private static final String URL = "jdbc:fixture:basic-table-metadata";

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"POSTGRESQL", "SQLITE3", "HIVE"})
    void sameOwnedMetadataConnectionNeedsNoNetworkDeadlineOrFullSchemaEnrichment(ProductTypeEnum product) throws Exception {
        var registry = mock(DbSwitchProductRegistry.class); var database = mock(AbstractDatabase.class);
        when(registry.metadata(product)).thenReturn(database);
        var metadataProvider = mock(JdbcConnectionProvider.class); var workloadProvider = mock(JdbcConnectionProvider.class);
        var connection = mock(Connection.class);
        when(metadataProvider.open(URL, null, null)).thenReturn(connection);
        doThrow(new SQLFeatureNotSupportedException("network deadlines unavailable")).when(connection).setNetworkTimeout(any(), anyInt());
        when(connection.getNetworkTimeout()).thenThrow(new SQLFeatureNotSupportedException("network deadlines unavailable"));
        var column = new ColumnDescription(); column.setFieldName("id");
        when(database.queryTableColumnMeta(connection, "s", "t")).thenReturn(List.of(column));
        when(database.queryTablePrimaryKeys(connection, "s", "t")).thenReturn(List.of("id"));
        try (var fullSchema = mockStatic(NativeSchemaReader.class)) {
            var facade = new MetaDataByJdbcServiceImpl(product, registry, metadataProvider, workloadProvider);
            var result = facade.queryTableColumnsAndKeys(URL, null, null, "s", "t");
            assertEquals(List.of("id"), result.primaryKeys());
            assertEquals("id", result.columns().getFirst().getFieldName()); assertFalse(column.isPk());
            fullSchema.verifyNoInteractions();
        }
        var order = inOrder(metadataProvider, database, connection);
        order.verify(metadataProvider).open(URL, null, null);
        order.verify(database).queryTableColumnMeta(connection, "s", "t");
        order.verify(database).queryTablePrimaryKeys(connection, "s", "t");
        order.verify(connection).close();
        verifyNoMoreInteractions(database, connection, metadataProvider); verifyNoInteractions(workloadProvider);
    }

    @Test void primaryKeyFailureRemainsAFailureAndClosesTheOwnedConnection() throws Exception {
        var product = ProductTypeEnum.POSTGRESQL; var registry = mock(DbSwitchProductRegistry.class);
        var database = mock(AbstractDatabase.class); when(registry.metadata(product)).thenReturn(database);
        var connection = mock(Connection.class); var metadataProvider = mock(JdbcConnectionProvider.class);
        when(metadataProvider.open(URL, null, null)).thenReturn(connection);
        when(database.queryTableColumnMeta(connection, "s", "t")).thenReturn(List.of(new ColumnDescription()));
        var failure = new IllegalStateException("primary key observation failed");
        when(database.queryTablePrimaryKeys(connection, "s", "t")).thenThrow(failure);
        var facade = new MetaDataByJdbcServiceImpl(product, registry, metadataProvider);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> facade.queryTableColumnsAndKeys(URL, null, null, "s", "t")));
        verify(connection).close(); verify(metadataProvider).open(URL, null, null);
        verifyNoMoreInteractions(connection, metadataProvider);
    }

    @Test void columnFailureStopsBeforeKeyReadAndClosesTheOwnedConnection() throws Exception {
        var registry = mock(DbSwitchProductRegistry.class); var database = mock(AbstractDatabase.class);
        when(registry.metadata(ProductTypeEnum.POSTGRESQL)).thenReturn(database);
        var connection = mock(Connection.class); var metadataProvider = mock(JdbcConnectionProvider.class);
        when(metadataProvider.open(URL, null, null)).thenReturn(connection);
        when(database.queryTableColumnMeta(connection, "s", "t")).thenThrow(new UnsupportedOperationException("columns unavailable"));
        var facade = new MetaDataByJdbcServiceImpl(ProductTypeEnum.POSTGRESQL, registry, metadataProvider);
        assertThrows(UnsupportedOperationException.class, () -> facade.queryTableColumnsAndKeys(URL, null, null, "s", "t"));
        verify(database, never()).queryTablePrimaryKeys(any(), anyString(), anyString()); verify(connection).close();
    }

    @Test void connectionCloseFailureCannotPublishASuccessfulResult() throws Exception {
        var registry = mock(DbSwitchProductRegistry.class); var database = mock(AbstractDatabase.class);
        when(registry.metadata(ProductTypeEnum.POSTGRESQL)).thenReturn(database);
        var connection = mock(Connection.class); var metadataProvider = mock(JdbcConnectionProvider.class);
        when(metadataProvider.open(URL, null, null)).thenReturn(connection);
        when(database.queryTableColumnMeta(connection, "s", "t")).thenReturn(List.of());
        when(database.queryTablePrimaryKeys(connection, "s", "t")).thenReturn(List.of());
        var closeFailure = new SQLException("owned metadata close failed"); doThrow(closeFailure).when(connection).close();
        var facade = new MetaDataByJdbcServiceImpl(ProductTypeEnum.POSTGRESQL, registry, metadataProvider);
        var failure = assertThrows(IllegalStateException.class, () -> facade.queryTableColumnsAndKeys(URL, null, null, "s", "t"));
        assertSame(closeFailure, failure.getCause()); verify(connection).close();
    }

    @Test void oldInterfaceImplementationsDeclineRatherThanComposeMultipleLegacyConnections() {
        var legacy = mock(IMetaDataByJdbcService.class, CALLS_REAL_METHODS);
        assertThrows(UnsupportedOperationException.class, () -> legacy.queryTableColumnsAndKeys(URL, null, null, "s", "t"));
        verify(legacy, never()).queryTableColumnMeta(any(), any(), any(), any(), any());
        verify(legacy, never()).queryTablePrimaryKeys(any(), any(), any(), any(), any());
    }
}

package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.model.CatalogRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.driver.JdbcConnectionProvider;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogRelationMetadataServiceTest {
    @Test void dorisMetadataParametersUseTheProductTransportBeforeBorrowing() throws Exception {
        var fixture = new Fixture(ProductTypeEnum.DORIS);
        String safeUrl = "jdbc:fixture:catalog?useServerPrepStmts=true&emulateUnsupportedPstmts=false";
        when(fixture.database.preparePageQueryJdbcUrl("jdbc:fixture:catalog")).thenReturn(safeUrl);
        when(fixture.metadata.open(safeUrl, null, null)).thenReturn(fixture.connection);
        var observed = new CatalogRelationIdentity(ProductTypeEnum.DORIS, "warehouse", "orders", "a".repeat(64));
        when(fixture.database.resolveCatalogRelation(fixture.connection, "warehouse", "orders", 5))
                .thenReturn(observed);
        assertEquals(observed, fixture.facade.resolveCatalogRelation(
                "jdbc:fixture:catalog", null, null, "warehouse", "orders", 5000));
        verify(fixture.metadata).open(safeUrl, null, null);
        verify(fixture.metadata, never()).open("jdbc:fixture:catalog", null, null);
        verify(fixture.connection).close();
    }
    @Test void dorisNativePickerAndExpectedGenerationUseTheSameBoundedMetadataConnection() throws Exception {
        var fixture = new Fixture(ProductTypeEnum.DORIS);
        var table = new cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription();
        table.setSchemaName("warehouse"); table.setTableName("orders");
        table.setCatalogObjectId("a".repeat(64)); table.setTableType("TABLE");
        when(fixture.database.queryCatalogTableList(fixture.connection, "warehouse", 10, 5)).thenReturn(List.of(table));
        assertEquals(List.of(table), fixture.facade.queryExactTableList(
                "jdbc:fixture:catalog", null, null, "warehouse", 10, 5000));
        verify(fixture.connection, never()).getMetaData();
        var expected = new CatalogRelationIdentity(ProductTypeEnum.DORIS, "warehouse", "orders", "a".repeat(64));
        when(fixture.database.resolveCatalogRelation(fixture.connection, "warehouse", "orders", 5))
                .thenReturn(new CatalogRelationIdentity(ProductTypeEnum.DORIS, "warehouse", "orders", "b".repeat(64)));
        assertThrows(IllegalStateException.class, () -> fixture.facade.queryExactTableColumns(
                "jdbc:fixture:catalog", null, null, expected, null, 5000));
        verify(fixture.connection, never()).prepareStatement(anyString());
        verify(fixture.connection, times(2)).close();
        verifyNoInteractions(fixture.workload);
    }
    @Test void facadeUsesOneOwnedMetadataConnectionAndKeepsNativeSpelling() throws Exception {
        var fixture = new Fixture();
        var observed = new CatalogRelationIdentity(ProductTypeEnum.TIDB, "Tenant", "Orders", "41");
        when(fixture.database.resolveCatalogRelation(fixture.connection, "TENANT", "orders", 5)).thenReturn(observed);
        assertEquals(observed, fixture.facade.resolveCatalogRelation("jdbc:fixture:catalog", null, null, "TENANT", "orders", 4500));
        var order = inOrder(fixture.metadata, fixture.connection, fixture.database);
        order.verify(fixture.metadata).open("jdbc:fixture:catalog", null, null);
        order.verify(fixture.connection).setNetworkTimeout(any(), eq(4500));
        order.verify(fixture.database).resolveCatalogRelation(fixture.connection, "TENANT", "orders", 5);
        order.verify(fixture.connection).close();
        verifyNoInteractions(fixture.workload);
        verify(fixture.connection, never()).commit();
        verify(fixture.connection, never()).rollback();
        verify(fixture.connection, never()).setAutoCommit(anyBoolean());
    }

    @Test void mismatchedOrMissingNativeResultCannotBePublished() throws Exception {
        for (var result : new CatalogRelationIdentity[]{null,
                new CatalogRelationIdentity(ProductTypeEnum.MYSQL, "Tenant", "Orders", "41")}) {
            var fixture = new Fixture();
            when(fixture.database.resolveCatalogRelation(fixture.connection, "tenant", "orders", 5)).thenReturn(result);
            assertThrows(IllegalStateException.class, () -> fixture.facade.resolveCatalogRelation(
                    "jdbc:fixture:catalog", null, null, "tenant", "orders", 5000));
            verify(fixture.connection).close();
        }
    }

    @Test void readAndCloseFailuresCannotReturnAFalseSuccess() throws Exception {
        var fixture = new Fixture();
        var unsupported = new SQLFeatureNotSupportedException("Native contract unavailable");
        when(fixture.database.resolveCatalogRelation(fixture.connection, "tenant", "orders", 5)).thenThrow(unsupported);
        var failure = assertThrows(IllegalStateException.class, () -> fixture.facade.resolveCatalogRelation(
                "jdbc:fixture:catalog", null, null, "tenant", "orders", 5000));
        assertSame(unsupported, failure.getCause());
        verify(fixture.connection).close();

        var closeFixture = new Fixture();
        when(closeFixture.database.resolveCatalogRelation(closeFixture.connection, "tenant", "orders", 5))
                .thenReturn(new CatalogRelationIdentity(ProductTypeEnum.TIDB, "Tenant", "Orders", "41"));
        var closeFailure = new SQLException("Owned close unavailable");
        doThrow(closeFailure).when(closeFixture.connection).close();
        assertSame(closeFailure, assertThrows(IllegalStateException.class, () -> closeFixture.facade.resolveCatalogRelation(
                "jdbc:fixture:catalog", null, null, "tenant", "orders", 5000)).getCause());
    }

    @Test void missingNetworkDeadlineSupportDoesNotFallBackToUnboundedReads() throws Exception {
        var fixture = new Fixture();
        doThrow(new SQLFeatureNotSupportedException("No network timeout"))
                .when(fixture.connection).setNetworkTimeout(any(), anyInt());
        assertThrows(IllegalStateException.class, () -> fixture.facade.resolveCatalogRelation(
                "jdbc:fixture:catalog", null, null, "tenant", "orders", 5000));
        verifyNoInteractions(fixture.database);
        verify(fixture.connection).close();
    }

    @Test void invalidInputStopsBeforeBorrowingAndLegacyFacadesDecline() throws Exception {
        var fixture = new Fixture();
        for (int timeout : new int[]{0, -1, 60_001}) {
            assertThrows(IllegalArgumentException.class, () -> fixture.facade.resolveCatalogRelation(
                    "jdbc:fixture:catalog", null, null, "tenant", "orders", timeout));
        }
        assertThrows(IllegalArgumentException.class, () -> fixture.facade.resolveCatalogRelation(
                "jdbc:fixture:catalog", null, null, null, "orders", 5000));
        assertThrows(IllegalArgumentException.class, () -> fixture.facade.resolveCatalogRelation(
                "jdbc:fixture:catalog", null, null, "tenant", " ", 5000));
        verifyNoInteractions(fixture.metadata, fixture.connection, fixture.database, fixture.workload);
        var legacy = mock(IMetaDataByJdbcService.class, CALLS_REAL_METHODS);
        assertThrows(UnsupportedOperationException.class, () -> legacy.resolveCatalogRelation(
                "jdbc:fixture:catalog", null, null, "tenant", "orders", 5000));
    }

    @Test void fourExistingBindingProductsDoNotInheritTidbRelationBinding() {
        for (var product : List.of(ProductTypeEnum.POSTGRESQL, ProductTypeEnum.MYSQL,
                ProductTypeEnum.MARIADB, ProductTypeEnum.OPENGAUSS)) {
            var connection = mock(Connection.class);
            assertThrows(SQLFeatureNotSupportedException.class, () -> DbSwitchProductRegistry.discover()
                    .metadata(product).resolveCatalogRelation(connection, "Tenant", "Orders", 5));
            // 表列表有产品自己的实现，不能把列表能力和 TiDB 对象身份绑定混为一谈。
            verifyNoInteractions(connection);
        }
    }

    @Test void pickerDelegatesOnOneOwnedMetadataConnectionAndNeverUsesJdbcPatterns() throws Exception {
        var fixture = new Fixture();
        var table = new cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription();
        table.setSchemaName("Tenant"); table.setTableName("Orders"); table.setTableType("TABLE");
        when(fixture.database.queryCatalogTableList(fixture.connection, "TENANT", 10, 5)).thenReturn(List.of(table));
        assertEquals(List.of(table), fixture.facade.queryExactTableList("jdbc:fixture:catalog", null, null, "TENANT", 10, 5000));
        verify(fixture.connection, never()).getMetaData();
        verify(fixture.connection).close();
        verifyNoInteractions(fixture.workload);
        assertThrows(IllegalArgumentException.class,
                () -> fixture.facade.queryExactTableList("jdbc:fixture:catalog", null, null, "TENANT", 0, 5000));
    }

    @Test void exactColumnsUseCatalogNamesAndRejectNativeIdChangeBeforeReturning() throws Exception {
        var fixture = new Fixture();
        var first = new CatalogRelationIdentity(ProductTypeEnum.TIDB, "Tenant", "Orders", "41");
        var after = new CatalogRelationIdentity(ProductTypeEnum.TIDB, "Tenant", "Orders", "42");
        when(fixture.database.resolveCatalogRelation(fixture.connection, "TENANT", "orders", 5)).thenReturn(first);
        when(fixture.database.resolveCatalogRelation(fixture.connection, "Tenant", "Orders", 5)).thenReturn(after);
        var statement = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        var columns = mock(ResultSetMetaData.class);
        when(fixture.connection.prepareStatement("SELECT * FROM `Tenant`.`Orders` WHERE 1 = 0")).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(columns);
        when(columns.getColumnCount()).thenReturn(1);
        when(columns.getColumnLabel(1)).thenReturn("Id");
        when(columns.getColumnName(1)).thenReturn("Id");
        when(columns.getColumnTypeName(1)).thenReturn("INTEGER");
        assertThrows(IllegalStateException.class, () -> fixture.facade.queryExactTableColumns(
                "jdbc:fixture:catalog", null, null, "TENANT", "orders", null, 5000));
        verify(rows).close(); verify(statement).close(); verify(fixture.connection).close();
        verify(fixture.connection, never()).setReadOnly(anyBoolean());
        verify(fixture.connection, never()).commit(); verify(fixture.connection, never()).rollback();
        verifyNoInteractions(fixture.workload);
    }

    @Test void expectedGenerationMismatchFailsBeforeReadingColumnsAndLegacyOverloadDeclines() throws Exception {
        var fixture = new Fixture();
        var expected = new CatalogRelationIdentity(ProductTypeEnum.TIDB, "Tenant", "Orders", "41");
        when(fixture.database.resolveCatalogRelation(fixture.connection, "Tenant", "Orders", 5))
                .thenReturn(new CatalogRelationIdentity(ProductTypeEnum.TIDB, "Tenant", "Orders", "42"));
        assertThrows(IllegalStateException.class, () -> fixture.facade.queryExactTableColumns(
                "jdbc:fixture:catalog", null, null, expected, null, 5000));
        verify(fixture.connection, never()).prepareStatement(anyString());
        verify(fixture.connection).close();
        var legacy = mock(IMetaDataByJdbcService.class, CALLS_REAL_METHODS);
        assertThrows(UnsupportedOperationException.class, () -> legacy.queryExactTableColumns(
                "jdbc:fixture:catalog", null, null, expected, null, 5000));
    }

    private static final class Fixture {
        final AbstractDatabase database = mock(AbstractDatabase.class);
        final Connection connection = mock(Connection.class);
        final JdbcConnectionProvider metadata = mock(JdbcConnectionProvider.class), workload = mock(JdbcConnectionProvider.class);
        final MetaDataByJdbcServiceImpl facade;
        Fixture() throws SQLException {
            this(ProductTypeEnum.TIDB);
        }
        Fixture(ProductTypeEnum product) throws SQLException {
            var registry = mock(DbSwitchProductRegistry.class);
            when(registry.metadata(product)).thenReturn(database);
            when(metadata.open("jdbc:fixture:catalog", null, null)).thenReturn(connection);
            when(database.preparePageQueryJdbcUrl("jdbc:fixture:catalog")).thenReturn("jdbc:fixture:catalog");
            facade = new MetaDataByJdbcServiceImpl(product, registry, metadata, workload);
        }
    }
}

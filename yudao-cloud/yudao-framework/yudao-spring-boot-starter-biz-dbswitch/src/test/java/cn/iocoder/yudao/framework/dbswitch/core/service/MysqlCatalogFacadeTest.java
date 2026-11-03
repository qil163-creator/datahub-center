package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.driver.JdbcConnectionProvider;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MysqlCatalogFacadeTest {
    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"MYSQL", "MARIADB", "TIDB"})
    void delegatesCatalogSemanticsWithOwnedConnectionAndDeadline(ProductTypeEnum product) throws Exception {
        var dialect = mock(AbstractDatabase.class);
        var connection = mock(Connection.class);
        var provider = mock(JdbcConnectionProvider.class);
        var workload = mock(JdbcConnectionProvider.class);
        var registry = mock(DbSwitchProductRegistry.class);
        when(registry.metadata(product)).thenReturn(dialect);
        when(provider.open("jdbc:fixture:catalog", null, null)).thenReturn(connection);
        var table = new TableDescription();
        table.setSchemaName("app_db"); table.setTableName("orders"); table.setTableType("TABLE");
        when(dialect.queryCatalogTableList(connection, "app_db", 3, 5)).thenReturn(List.of(table));
        var facade = new MetaDataByJdbcServiceImpl(product, registry, provider, workload);
        assertEquals(List.of(table), facade.queryExactTableList("jdbc:fixture:catalog", null, null,
                "app_db", 3, 4500));
        var order = inOrder(connection, dialect);
        order.verify(connection).setNetworkTimeout(any(), eq(4500));
        order.verify(dialect).queryCatalogTableList(connection, "app_db", 3, 5);
        order.verify(connection).close();
        verify(connection, never()).getMetaData();
        verify(connection, never()).commit();
        verify(connection, never()).setCatalog(anyString());
        verifyNoInteractions(workload);
    }

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"MYSQL", "MARIADB"})
    void catalogFailureDoesNotFallBackToJdbcPatterns(ProductTypeEnum product) throws Exception {
        var dialect = mock(AbstractDatabase.class);
        var connection = mock(Connection.class);
        var registry = mock(DbSwitchProductRegistry.class);
        when(registry.metadata(product)).thenReturn(dialect);
        var failure = new SQLException("catalog read unavailable");
        when(dialect.queryCatalogTableList(connection, "app_db", 3, 5)).thenThrow(failure);
        var facade = new MetaDataByJdbcServiceImpl(product, registry, (url, user, password) -> connection);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> facade.queryExactTableList(
                "jdbc:fixture:catalog", null, null, "app_db", 3, 5000)).getCause());
        verify(connection, never()).getMetaData();
        verify(connection).close();
    }
}

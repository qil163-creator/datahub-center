package cn.iocoder.yudao.module.dataforge.api.database;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.model.CatalogRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseQueryReqDTO;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 原生解析身份必须传递到实际读取连接，返回前的变化必须丢弃数据。 */
class DatabaseDorisIdentityExecutionTest {
    private final DatabaseApiImpl api = new DatabaseApiImpl();
    private final DbSwitchRuntime runtime = mock(DbSwitchRuntime.class);
    private final AccessDatabaseResolver resolver = mock(AccessDatabaseResolver.class);
    private final AbstractDatabase database = mock(AbstractDatabase.class);
    private final Connection connection = mock(Connection.class);
    private final PreparedStatement statement = mock(PreparedStatement.class);
    private final CatalogRelationIdentity before = new CatalogRelationIdentity(
            ProductTypeEnum.DORIS, "warehouse", "orders", "a".repeat(64));
    private AccessDatabaseResolver.ConnectionInfo info;

    @BeforeEach void setUp() throws Exception {
        info = new AccessDatabaseResolver.ConnectionInfo();
        info.setDatabaseId(7L); info.setProductType(ProductTypeEnum.DORIS); info.setSchema("warehouse");
        info.setConfigurationRevision("revision-1"); info.setJdbcUrl("jdbc:fixture://fixture.invalid/test");
        when(resolver.resolve(eq(7L), any())).thenReturn(info);
        var products = mock(DbSwitchProductRegistry.class);
        when(runtime.products()).thenReturn(products);
        when(products.metadata(ProductTypeEnum.DORIS)).thenReturn(database);
        when(database.preparePageQueryJdbcUrl(info.getJdbcUrl()))
                .thenReturn("jdbc:fixture://fixture.invalid/test?useServerPrepStmts=true");
        when(runtime.openConnection(eq(ProductTypeEnum.DORIS), isNull(), anyString(), isNull(), isNull()))
                .thenReturn(connection);
        when(database.resolveCatalogRelation(connection, "warehouse", "orders", 5)).thenReturn(before);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        var rows = mock(ResultSet.class);
        var columns = mock(ResultSetMetaData.class);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(columns);
        when(columns.getColumnCount()).thenReturn(1);
        when(columns.getColumnLabel(1)).thenReturn("id");
        when(columns.getColumnTypeName(1)).thenReturn("BIGINT");
        when(rows.next()).thenReturn(true, false);
        when(rows.getObject(1)).thenReturn(9007199254740993L);
        ReflectionTestUtils.setField(api, "dbSwitchRuntime", runtime);
        ReflectionTestUtils.setField(api, "databaseResolver", resolver);
    }

    private DatabaseQueryReqDTO request() {
        var request = new DatabaseQueryReqDTO();
        request.setDatabaseId(7L); request.setSql("select id from orders");
        request.setExpectedObjectId(before.objectId());
        return request;
    }

    @Test void guardedReadUsesTheSameOwnedConnectionAndPreservesLargeValues() throws Exception {
        var result = api.executeQuery(request()).getCheckedData();
        assertEquals(9007199254740993L, result.getRows().getFirst().get("id"));
        verify(runtime).openConnection(ProductTypeEnum.DORIS, null,
                "jdbc:fixture://fixture.invalid/test?useServerPrepStmts=true", null, null);
        var order = inOrder(connection, database, statement);
        order.verify(connection).setNetworkTimeout(any(), eq(30_000));
        order.verify(connection).setReadOnly(true);
        order.verify(database).resolveCatalogRelation(connection, "warehouse", "orders", 5);
        order.verify(connection).prepareStatement(contains("`warehouse`.`orders`"));
        order.verify(statement).setQueryTimeout(15);
        order.verify(statement).setMaxRows(101);
        order.verify(statement).executeQuery();
        order.verify(statement).close();
        order.verify(database).resolveCatalogRelation(connection, "warehouse", "orders", 5);
        order.verify(connection).close();
        verify(connection, never()).commit(); verify(connection, never()).rollback();
    }

    @Test void missingProofRejectsBeforeOpeningConnection() throws Exception {
        for (String identity : new String[]{null, "42", "A".repeat(64)}) {
            var request = request(); request.setExpectedObjectId(identity);
            assertThrows(ServiceException.class, () -> api.executeQuery(request));
        }
        verify(runtime, never()).openConnection(any(), any(), any(), any(), any());
    }

    @Test void replacedRelationRejectsBeforeReadingRows() throws Exception {
        when(database.resolveCatalogRelation(connection, "warehouse", "orders", 5))
                .thenReturn(new CatalogRelationIdentity(ProductTypeEnum.DORIS, "warehouse", "orders", "b".repeat(64)));
        assertThrows(ServiceException.class, () -> api.executeQuery(request()));
        verify(connection, never()).prepareStatement(anyString());
        verify(connection).close();
    }

    @Test void replacementDuringReadDiscardsAlreadyFetchedRows() throws Exception {
        when(database.resolveCatalogRelation(connection, "warehouse", "orders", 5)).thenReturn(before,
                new CatalogRelationIdentity(ProductTypeEnum.DORIS, "warehouse", "orders", "b".repeat(64)));
        assertThrows(ServiceException.class, () -> api.executeQuery(request()));
        verify(statement).executeQuery();
        verify(connection).close();
    }

    @Test void configurationChangeAfterReadingAlsoDiscardsRows() throws Exception {
        when(statement.executeQuery()).thenAnswer(invocation -> {
            var changed = new AccessDatabaseResolver.ConnectionInfo();
            changed.setDatabaseId(7L); changed.setProductType(ProductTypeEnum.DORIS);
            changed.setSchema("warehouse"); changed.setConfigurationRevision("revision-2");
            when(resolver.resolve(eq(7L), any())).thenReturn(changed);
            var rows = mock(ResultSet.class);
            var columns = mock(ResultSetMetaData.class);
            when(rows.getMetaData()).thenReturn(columns);
            return rows;
        });
        assertThrows(ServiceException.class, () -> api.executeQuery(request()));
        verify(statement).executeQuery();
        verify(connection).close();
    }

    @Test void columnScopeStillRejectsUnauthorizedFiltersBeforeConnecting() throws Exception {
        var request = request(); request.setSql("select id from orders where secret=1");
        request.setExpectedSchema("warehouse"); request.setExpectedTable("orders");
        request.setAllowedColumns(List.of("id"));
        assertThrows(ServiceException.class, () -> api.executeQuery(request));
        verify(runtime, never()).openConnection(any(), any(), any(), any(), any());
    }
}

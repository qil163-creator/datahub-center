package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.module.dataforge.service.security.DatabaseQuerySupport;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessNameMapping;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfig;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DataAccessPermissionTest {
    @Test void apiIncrementalStillRequiresTargetWritePermissions() {
        var permissions = mock(DatabaseQuerySupport.class);
        var resolver = mock(AccessDatabaseResolver.class);
        var connection = new AccessDatabaseResolver.ConnectionInfo();
        connection.setProductType(ProductTypeEnum.POSTGRESQL); connection.setSchema("public");
        when(resolver.resolve(anyLong(), any())).thenReturn(connection);
        var executor = new DataAccessExecutor();
        ReflectionTestUtils.setField(executor, "databaseQuerySupport", permissions);
        ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
        var definition = DataAccessDO.builder().engineType(3).targetDatabaseId(20L)
                .apiAccessJson("{\"changeDataSync\":true,\"mappings\":[{\"apiConfig\":{\"dataColumns\":[{\"fieldName\":\"id\",\"pk\":true}]},\"columnMaps\":[{\"sourceColumn\":\"id\",\"targetColumn\":\"id\",\"importFlg\":1}],\"targetTable\":\"output\"}]}").build();
        definition.setCreator("7");
        assertThrows(IllegalStateException.class, () -> executor.validateExecutionPermission(definition));
        verifyNoInteractions(resolver);
    }

    @Test void invalidIncrementalMappingFailsBeforeConnectionOrTargetEffects() {
        var permissions = mock(DatabaseQuerySupport.class);
        var resolver = mock(AccessDatabaseResolver.class);
        var runtime = mock(cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime.class);
        var executor = new DataAccessExecutor();
        ReflectionTestUtils.setField(executor, "databaseQuerySupport", permissions);
        ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
        ReflectionTestUtils.setField(executor, "dbSwitchRuntime", runtime);
        for (String columns : List.of("[{\"fieldName\":\"id\",\"pk\":false}]",
                "[{\"fieldName\":\"id\",\"pk\":true},{\"fieldName\":\"part\",\"pk\":true}]")) {
            var definition = DataAccessDO.builder().engineType(3).targetDatabaseId(20L)
                    .apiAccessJson("{\"targetDrop\":true,\"changeDataSync\":true,\"mappings\":[{\"apiConfig\":{\"dataColumns\":" + columns
                            + "},\"columnMaps\":[{\"sourceColumn\":\"id\",\"targetColumn\":\"id\",\"importFlg\":1}],\"targetTable\":\"output\"}]}").build();
            definition.setCreator("7");
            assertThrows(IllegalStateException.class, () -> executor.validateExecutionPermission(definition));
            assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(executor, "executeApiAccess", definition,
                    new cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessTaskDO(), new StringBuffer(),
                    new java.util.concurrent.atomic.AtomicLong(), new java.util.concurrent.atomic.AtomicLong(), new java.util.concurrent.atomic.AtomicLong()));
        }
        verifyNoInteractions(resolver, runtime);
    }

    @Test void nativeSeatunnelAcceptsConfiguredConnectorsWithoutPlatformTableBindings() {
        var permissions = mock(DatabaseQuerySupport.class);
        var executor = new DataAccessExecutor();
        ReflectionTestUtils.setField(executor, "databaseQuerySupport", permissions);
        var definition = DataAccessDO.builder().engineType(2).configFormat("hocon")
                .seatunnelConfig("source { Kafka {} } sink { Console {} }").build();
        definition.setCreator("7");
        assertDoesNotThrow(() -> executor.validateExecutionPermission(definition));
        verifyNoMoreInteractions(permissions);
        definition.setCreator(null);
        assertThrows(RuntimeException.class, () -> executor.validateExecutionPermission(definition));
    }

    @Test void nativeSubmissionStillRequiresCurrentUserRunPermission() {
        var access = mock(cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService.class);
        var worker = mock(DataAccessExecutor.class);
        var workers = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(workers.getObject()).thenReturn(worker);
        var authorization = new AccessRunAuthorization();
        ReflectionTestUtils.setField(authorization, "access", access);
        ReflectionTestUtils.setField(authorization, "executor", workers);
        var definition = DataAccessDO.builder().engineType(2).sourceDatabaseId(999L).targetDatabaseId(998L)
                .seatunnelConfig("{}").build();
        authorization.execution(definition, 7L);
        verify(access).requireUserPermission(7L, "dataforge:access:run");
        verify(access, never()).requireDatabase(any());
        verify(worker).validateExecutionPermission(definition);
        clearInvocations(worker);
        doThrow(new SecurityException("revoked")).when(access).requireUserPermission(7L, "dataforge:access:run");
        assertThrows(SecurityException.class, () -> authorization.execution(definition, 7L));
        verifyNoInteractions(worker);
    }

    @Test
    void exclusionModeAuthorizesExpandedSourceAndMappedTargetWrites() {
        var permissions = mock(DatabaseQuerySupport.class);
        var resolver = mock(AccessDatabaseResolver.class);
        var connection = new AccessDatabaseResolver.ConnectionInfo();
        connection.setProductType(ProductTypeEnum.POSTGRESQL); connection.setSchema("public");
        connection.setJdbcUrl("jdbc:test"); connection.setUsername("fixture"); connection.setPassword("fixture");
        when(resolver.resolve(anyLong(), any())).thenReturn(connection);
        var executor = new DataAccessExecutor();
        ReflectionTestUtils.setField(executor, "databaseQuerySupport", permissions);
        ReflectionTestUtils.setField(executor, "databaseResolver", resolver);
        var source = new AccessTaskConfig.Source(); source.setIncludeOrExclude(0); source.setSourceExcludes("secret");
        source.setRegexTableMapper(List.of(new AccessNameMapping("^customer$", "person")));
        var target = new AccessTaskConfig.Target(); target.setTablePrefix("ods_"); target.setLowercase(true);
        target.setWriteMode("CREATE_NEW"); target.setSyncExist(false);
        var config = new AccessTaskConfig(); config.setSource(List.of(source)); config.setTarget(target);
        var task = DataAccessDO.builder().engineType(1).sourceDatabaseId(10L).targetDatabaseId(20L).dataAccessJson(config).build();
        task.setCreator("7");
        var customer = new TableDescription(); customer.setTableName("customer");
        var secret = new TableDescription(); secret.setTableName("secret");
        var runtime = mock(cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime.class);
        var metadata = mock(MetaDataByJdbcServiceImpl.class);
        when(runtime.jdbcMetadata(eq(ProductTypeEnum.POSTGRESQL))).thenReturn(metadata);
        when(metadata.queryTableList(anyString(), anyString(), anyString(), anyString())).thenReturn(List.of(customer, secret));
        ReflectionTestUtils.setField(executor, "dbSwitchRuntime", runtime);
        var migrationAuthorization = mock(AccessMigrationAuthorization.class);
        ReflectionTestUtils.setField(executor, "migrationAuthorization", migrationAuthorization);
        ReflectionTestUtils.invokeMethod(executor, "validateAccessPermission", task);
        verify(permissions).requireExactTables(10L, ProductTypeEnum.POSTGRESQL, "public", List.of("customer"));
        verify(migrationAuthorization).requireTargetPermission(7L);
        verify(migrationAuthorization).requireSourceBinding(connection, 7L, "customer");
        assertEquals(1, source.getIncludeOrExclude()); assertEquals(List.of("customer"), source.getSourceTableNames());
        assertEquals("", source.getSourceIncludes());
    }
}

package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.DatabaseTableMetaRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.DevTaskSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskDO;
import cn.iocoder.yudao.module.dataforge.model.dev.DevExecutionConfig;
import cn.iocoder.yudao.module.dataforge.service.database.DatabaseService;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.DATABASE_UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DevTableLineageTest {
    @Test
    void typedJsonRoundtripRetainsLosslessIdsSchemaAndDirection() {
        var config = config(reference(9_007_199_254_740_993L,"测站基础"));
        config.getTableLineage().getUpstream().getFirst().setSchemaName("public");
        var restored = DevTaskDefinition.readExecutionConfig(JsonUtils.toJsonString(config));
        assertEquals(config,restored);
        assertEquals(9_007_199_254_740_993L,restored.getTableLineage().getUpstream().getFirst().getDatabaseId());
        assertNull(DevTaskDefinition.readExecutionConfig("{}").getTableLineage());
    }

    @Test
    void malformedIdsNamesCountsAndDuplicateRelationshipsAreRejected() {
        for (Long id : new Long[]{null,0L,-1L}) assertThrows(ServiceException.class,()->DevTaskDefinition.validateExecutionConfig(config(reference(id,"table_a"))));
        for (String name : List.of(""," ","table\nname","a".repeat(256))) assertThrows(ServiceException.class,()->DevTaskDefinition.validateExecutionConfig(config(reference(1L,name))));
        var duplicates = config(reference(1L,"table_a"));
        duplicates.getTableLineage().setUpstream(List.of(reference(1L,"table_a"),reference(1L,"table_a")));
        assertThrows(ServiceException.class,()->DevTaskDefinition.validateExecutionConfig(duplicates));
        var tooMany = config(reference(1L,"table_a"));
        tooMany.getTableLineage().setUpstream(java.util.stream.IntStream.range(0,51).mapToObj(index->reference(1L,"source_"+index)).toList());
        tooMany.getTableLineage().setDownstream(java.util.stream.IntStream.range(0,50).mapToObj(index->reference(1L,"target_"+index)).toList());
        assertThrows(ServiceException.class,()->DevTaskDefinition.validateExecutionConfig(tooMany));
    }

    @Test
    void missingDatabaseStopsBeforeAnyExternalMetadataRequest() {
        var access = mock(ExecutionAccessService.class); var databases = mock(DatabaseService.class);
        doThrow(exception(DATABASE_UNAVAILABLE)).when(access).requireDatabase(77L);
        var service = service(access,databases);
        assertThrows(ServiceException.class,()->validate(service,config(reference(77L,"table_a")),null));
        verifyNoInteractions(databases);
    }

    @Test
    void onlineTasksCannotChangeTableLineage() {
        var access = mock(ExecutionAccessService.class); var databases = mock(DatabaseService.class);
        var current = DevTaskDO.builder().status(1).executionConfigJson("{}").build();
        assertThrows(ServiceException.class,()->validate(service(access,databases),config(reference(1L,"table_a")),current));
        verifyNoInteractions(access,databases);
    }

    @Test
    void metadataIsReusedAndCanonicalizedWithoutGivingExecutionPermission() {
        var access = mock(ExecutionAccessService.class); var databases = mock(DatabaseService.class);
        var meta = metadata("public","table_a"); when(databases.getTableMeta(1L,"table_a")).thenReturn(meta);
        var config = config(reference(1L,"table_a")); config.getTableLineage().setDownstream(List.of(reference(1L,"table_a")));
        validate(service(access,databases),config,DevTaskDO.builder().status(0).executionConfigJson("{}").build());
        assertEquals("public",config.getTableLineage().getUpstream().getFirst().getSchemaName());
        verify(access,times(1)).requireDatabase(1L); verify(access,never()).requireUserPermission(any(),any());
        verify(databases,times(1)).getTableMeta(1L,"table_a"); verifyNoMoreInteractions(databases);
    }

    @Test
    void nonexistentTablesAndOtherSchemasCannotBePersisted() {
        var access = mock(ExecutionAccessService.class); var databases = mock(DatabaseService.class);
        var service = service(access,databases);
        assertThrows(ServiceException.class,()->validate(service,config(reference(1L,"missing")),null));
        when(databases.getTableMeta(1L,"table_a")).thenReturn(metadata("public","table_a"));
        var config = config(reference(1L,"table_a")); config.getTableLineage().getUpstream().getFirst().setSchemaName("other");
        assertThrows(ServiceException.class,()->validate(service,config,null));
    }

    static DevExecutionConfig config(DevExecutionConfig.TableReference reference) {
        var value = new DevExecutionConfig(); var lineage = new DevExecutionConfig.TableLineage();
        lineage.setUpstream(List.of(reference)); value.setTableLineage(lineage); return value;
    }
    static DevExecutionConfig.TableReference reference(Long databaseId,String tableName) {
        var reference = new DevExecutionConfig.TableReference(); reference.setDatabaseId(databaseId); reference.setTableName(tableName); return reference;
    }
    private static DatabaseTableMetaRespVO metadata(String schema,String table) {
        var metadata = new DatabaseTableMetaRespVO(); metadata.setSchemaName(schema); metadata.setTableName(table); return metadata;
    }
    private static DevTaskServiceImpl service(ExecutionAccessService access,DatabaseService databases) {
        var service = new DevTaskServiceImpl(); ReflectionTestUtils.setField(service,"executionAccessService",access); ReflectionTestUtils.setField(service,"databaseService",databases); return service;
    }
    private static void validate(DevTaskServiceImpl service,DevExecutionConfig config,DevTaskDO current) {
        var request = new DevTaskSaveReqVO(); request.setExecutionConfig(config);
        ReflectionTestUtils.invokeMethod(service,"validateTableLineage",request,current);
    }
}

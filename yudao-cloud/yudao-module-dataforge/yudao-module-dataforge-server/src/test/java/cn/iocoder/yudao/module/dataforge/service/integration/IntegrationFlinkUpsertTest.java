package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.etl.core.connection.EtlConnectionInfo;
import cn.iocoder.yudao.module.dataforge.controller.admin.database.vo.*;
import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationModels.EngineConfig;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.IntegrationTaskDO;
import cn.iocoder.yudao.module.dataforge.service.database.DatabaseService;
import cn.iocoder.yudao.module.dataforge.service.etl.EtlConnectionResolverAdapter;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class IntegrationFlinkUpsertTest {
    private final IntegrationFlowCompiler compiler = new IntegrationFlowCompiler();
    private final IntegrationRuntimeProperties properties = new IntegrationRuntimeProperties();
    private final DatabaseService databases = mock(DatabaseService.class);
    private final EtlConnectionResolverAdapter connections = mock(EtlConnectionResolverAdapter.class);
    private final ExecutionAccessService access = mock(ExecutionAccessService.class);
    private final IntegrationRuntimeProperties.NativeTableBinding binding = new IntegrationRuntimeProperties.NativeTableBinding();
    private boolean nullable;
    IntegrationFlinkUpsertTest() {
        binding.setFlinkNamespace(List.of("default_catalog","default_database")); binding.setFlinkContinuousSource(true); binding.setFlinkContinuousTables(Set.of("source"));
        binding.setFlinkVerifiedJdbcSinkInstalled(true); binding.setFlinkUpsertPrimaryKeys(Map.of("target",List.of("id")));
        properties.setNativeTableBindings(Map.of(1L,binding,2L,binding));
        ReflectionTestUtils.setField(compiler,"properties",properties);ReflectionTestUtils.setField(compiler,"databases",databases);
        ReflectionTestUtils.setField(compiler,"connections",connections);ReflectionTestUtils.setField(compiler,"access",access);
        var connection=new EtlConnectionInfo();connection.setProductType(ProductTypeEnum.POSTGRESQL);connection.setSchema("public");connection.setJdbcUrl("jdbc:postgresql://localhost:5432/fixture");
        when(connections.resolve(anyLong())).thenReturn(connection);
        when(databases.getTableMeta(anyLong(),anyString())).thenAnswer(call->{
            var metadata=new DatabaseTableMetaRespVO();metadata.setSchemaName("public");metadata.setTableName(call.getArgument(1));
            var id=new DatabaseTableColumnRespVO();id.setFieldName("id");id.setFieldTypeName("BIGINT");id.setPk(true);id.setNullable(nullable);
            var label=new DatabaseTableColumnRespVO();label.setFieldName("label");label.setFieldTypeName("STRING");label.setPk(false);label.setNullable(true);
            metadata.setColumns(List.of(id,label));return metadata;
        });
    }
    private List<IntegrationFlowCompiler.Step> compile() {
        var task=new IntegrationTaskDO();task.setId(7L);task.setEngine("FLINK");task.setMode("REALTIME");task.setEngineConfigJson(JSONUtil.toJsonStr(new EngineConfig()));
        var nodes=List.of(Map.of("id","source","properties",Map.of("name","source","toolCode","read-db","nodeJson",Map.of("databaseId",1,"tableName","source"))),
                Map.of("id","sink","properties",Map.of("name","sink","toolCode","insert-db","nodeJson",Map.of("databaseId",2,"tableName","target","dataSyncType",2,"batchSize",1024,"upsertKeys",List.of("id")))));
        String flow=JSONUtil.toJsonStr(Map.of("nodes",nodes,"edges",List.of(Map.of("sourceNodeId","source","targetNodeId","sink"))));
        return compiler.compile(task,flow);
    }
    @Test void usesDatabaseFactsAndControlledCatalogRatherThanNodeSuppliedPrimaryKey() {
        var steps=compile();String sql=String.valueOf(steps.getFirst().parameters().get("rawScript"));
        assertTrue(sql.contains("datahub-verified-jdbc"));assertTrue(sql.contains("LIKE `default_catalog`.`default_database`.`target`"));
        assertFalse(sql.contains("jdbc:postgresql"));verify(access,atLeastOnce()).requireDatabase(2L);
    }
    @Test void upsertAliasIsAdditiveAndDoesNotRewriteExistingSourceOrAppendCatalogs() {
        binding.setTableAliases(Map.of("source","source_live","target","target_append"));
        binding.setFlinkUpsertTableAliases(Map.of("target","target_verified"));
        String sql=String.valueOf(compile().getFirst().parameters().get("rawScript"));
        assertTrue(sql.contains("LIKE `default_catalog`.`default_database`.`target_verified`"));
        assertTrue(sql.contains("`default_catalog`.`default_database`.`source_live`"));
        assertEquals("target_append",binding.getTableAliases().get("target"));
    }
    @Test void refusesUnknownInstallationWrongCatalogPkAndNullableDatabasePk() {
        binding.setFlinkVerifiedJdbcSinkInstalled(false);assertThrows(IllegalArgumentException.class,this::compile);
        binding.setFlinkVerifiedJdbcSinkInstalled(true);binding.setFlinkUpsertPrimaryKeys(Map.of("target",List.of("label")));assertThrows(IllegalArgumentException.class,this::compile);
        binding.setFlinkUpsertPrimaryKeys(Map.of("target",List.of("id")));nullable=true;assertThrows(IllegalArgumentException.class,this::compile);
    }
}

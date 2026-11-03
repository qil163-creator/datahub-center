package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.BasicTableMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.util.MigrationTableNames;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessPreviewReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.service.access.config.*;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import cn.iocoder.yudao.module.dataforge.service.security.DatabaseQuerySupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 业务配置的保存、回显、冻结与授权预览；真实 Doris DDL 由组件集成测试验证。 */
class AccessTableOptionsContractTest {
    private final DataAccessServiceImpl service = new DataAccessServiceImpl();
    private final DataAccessMapper tasks = mock(DataAccessMapper.class);
    private final AccessDatabaseResolver resolver = mock(AccessDatabaseResolver.class);
    private final DbSwitchRuntime runtime = mock(DbSwitchRuntime.class);
    private final MetaDataByJdbcServiceImpl metadata = mock(MetaDataByJdbcServiceImpl.class);
    private final DatabaseQuerySupport permissions = mock(DatabaseQuerySupport.class);
    private final AtomicReference<DataAccessDO> stored = new AtomicReference<>();

    @BeforeEach void setup() {
        lenient().when(permissions.normalizeSelect(anyLong(), anyString())).thenAnswer(call -> call.getArgument(1));
        var databases = mock(DatabaseMapper.class);
        var source = DatabaseDO.builder().id(1L).name("source").build();
        var target = DatabaseDO.builder().id(2L).name("warehouse").usageType(2).build();
        when(databases.selectById(1L)).thenReturn(source);
        when(databases.selectById(2L)).thenReturn(target);
        when(databases.selectByIds(anyCollection())).thenReturn(List.of(source, target));
        when(tasks.insert(any(DataAccessDO.class))).thenAnswer(invocation -> {
            DataAccessDO saved = invocation.getArgument(0); saved.setId(9007199254740993L); stored.set(saved); return 1;
        });
        when(tasks.selectById(anyLong())).thenAnswer(invocation -> stored.get());
        var coordinator = mock(AccessRunCoordinator.class);
        doAnswer(invocation -> { stored.set(invocation.getArgument(1)); return null; })
                .when(coordinator).updateDraft(any(), any());
        when(resolver.resolveProductType(target)).thenReturn(ProductTypeEnum.DORIS);
        var sourceConnection = new AccessDatabaseResolver.ConnectionInfo();
        sourceConnection.setDatabaseId(1L); sourceConnection.setProductType(ProductTypeEnum.POSTGRESQL);
        sourceConnection.setSchema("source_scope"); sourceConnection.setJdbcUrl("fixture-connection");
        when(resolver.resolve(eq(1L), any())).thenReturn(sourceConnection);
        var targetConnection = new AccessDatabaseResolver.ConnectionInfo();
        targetConnection.setDatabaseId(2L); targetConnection.setProductType(ProductTypeEnum.DORIS);
        targetConnection.setSchema("warehouse"); targetConnection.setUsageType(2);
        when(resolver.resolve(eq(2L), any())).thenReturn(targetConnection);
        when(runtime.jdbcMetadata(ProductTypeEnum.POSTGRESQL)).thenReturn(metadata);
        var table = new TableDescription(); table.setTableName("orders"); table.setRemarks("source comment");
        when(metadata.queryTableList("fixture-connection", null, null, "source_scope")).thenReturn(List.of(table));
        ReflectionTestUtils.setField(service, "dataAccessMapper", tasks);
        ReflectionTestUtils.setField(service, "dataAccessExecutor", mock(DataAccessExecutor.class));
        ReflectionTestUtils.setField(service, "runCoordinator", coordinator);
        ReflectionTestUtils.setField(service, "databaseMapper", databases);
        ReflectionTestUtils.setField(service, "databaseResolver", resolver);
        ReflectionTestUtils.setField(service, "dbSwitchRuntime", runtime);
        ReflectionTestUtils.setField(service, "databaseQuerySupport", permissions);
        ReflectionTestUtils.setField(service, "executionAccess", mock(ExecutionAccessService.class));
        ReflectionTestUtils.setField(service, "deptApi", mock(cn.iocoder.yudao.module.system.api.dept.DeptApi.class));
        ReflectionTestUtils.setField(service, "adminUserApi", mock(cn.iocoder.yudao.module.system.api.user.AdminUserApi.class));
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(7L), new MockHttpServletRequest());
    }

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest @ValueSource(strings = {"ALL", "SINGLE"})
    void saveReadAndSnapshotPreserveFinalNameCommentAndLosslessPartitionValues(String mapperType) throws Exception {
        var request = request(); request.setMapperType(mapperType);
        var mapping = mapping(); request.setConfigMap(Map.of("orders", mapping, "archived_orders", mapping()));
        Long id = service.createDataAccess(request);
        var response = service.getDataAccess(id);
        assertEquals(mapping.getDorisOptions(), response.getConfigMap().get("orders").getDorisOptions());
        assertEquals("warehouse_orders", response.getConfigMap().get("orders").getTargetTableName());
        assertNotNull(response.getConfigMap().get("archived_orders")); // 未选表的既有配置允许保留。
        var frozen = AccessRunSnapshot.freeze(stored.get());
        var restored = AccessRunSnapshot.restore(frozen).getDataAccessJson();
        var execution = AccessTaskConfigMapper.toRuntime(restored, 2L);
        execution.getTarget().setTargetProductType(ProductTypeEnum.DORIS);
        var options = execution.getSource().getFirst().getConfigMap().get("orders");
        assertEquals("warehouse_orders", MigrationTableNames.resolve(execution.getSource().getFirst(), execution.getTarget(), "orders"));
        assertEquals("目标表注释", options.getTargetTableComment());
        assertEquals("9007199254740993", options.getDorisOptions().partition().partitions().getFirst().values().getFirst().getFirst());
        assertEquals(List.of("record_id"), options.getDorisOptions().distributionColumns());
        var wire = new tools.jackson.databind.ObjectMapper().writeValueAsString(response);
        assertTrue(wire.contains("\"9007199254740993\""));
        var fromWire = new tools.jackson.databind.ObjectMapper().readValue(
                new tools.jackson.databind.ObjectMapper().writeValueAsString(request), DataAccessSaveReqVO.class);
        assertEquals(mapping.getDorisOptions(), fromWire.getConfigMap().get("orders").getDorisOptions());
        mapping.setTargetTableName("later_edit");
        request.setId(id); service.updateDataAccess(request);
        assertEquals("later_edit", service.getDataAccess(id).getConfigMap().get("orders").getTargetTableName());
        assertEquals("warehouse_orders", AccessRunSnapshot.restore(frozen).getDataAccessJson().getSource().getFirst()
                .getConfigMap().get("orders").getTargetTableName());
    }

    @Test void oldDefinitionsAndCustomTargetsDoNotRequireDorisOptions() {
        var legacy = AccessTaskConfigCodec.read("{\"schemaVersion\":1,\"source\":[{\"configMap\":{\"orders\":{}}}],\"target\":{\"tablePrefix\":\"ods_\"}}");
        assertNull(legacy.getSource().getFirst().getConfigMap().get("orders").getDorisOptions());
        assertFalse(AccessTaskConfigCodec.write(legacy).contains("dorisOptions"));
        var oldRuntime = AccessTaskConfigMapper.toRuntime(legacy, 2L);
        assertEquals("ods_orders", MigrationTableNames.resolve(oldRuntime.getSource().getFirst(), oldRuntime.getTarget(), "orders"));
        var custom = request(); custom.setAccessMode(2);
        var mapping = mapping(); mapping.setDorisOptions(null); custom.setConfigMap(Map.of("orders", mapping));
        assertNotNull(service.createDataAccess(custom));
        verify(resolver, never()).resolveProductType(any());
    }

    @Test void invalidTargetModeProductSourceAndOptionsAreRejectedBeforeSaving() throws Exception {
        var request = request(); request.setConfigMap(Map.of("orders", mapping())); request.setAccessMode(2);
        assertThrows(ServiceException.class, () -> service.createDataAccess(request));
        request.setAccessMode(1); when(resolver.resolveProductType(any())).thenReturn(ProductTypeEnum.POSTGRESQL);
        assertThrows(ServiceException.class, () -> service.createDataAccess(request));
        when(resolver.resolveProductType(any())).thenReturn(ProductTypeEnum.DORIS);
        request.setSourceType(2); request.setSourceSql("select 1"); request.setTargetTable("one_row");
        assertThrows(ServiceException.class, () -> service.createDataAccess(request));
        request.setSourceType(1); request.getConfigMap().get("orders").setTargetTableName(" ");
        assertThrows(ServiceException.class, () -> service.createDataAccess(request));
        request.getConfigMap().get("orders").setTargetTableName("orders");
        request.getConfigMap().get("orders").setDorisOptions(new AccessDorisOptions(null, "HASH", List.of(), "FIXED", 0, 0));
        assertThrows(ServiceException.class, () -> service.createDataAccess(request));
        verify(tasks, never()).insert(any(DataAccessDO.class));
        verify(runtime, never()).openConnection(any(), any(), any(), any(), any());
    }

    @Test void previewAuthorizesTheExplicitFinalTargetAndRetainsSourceComment() {
        var request = preview();
        var result = service.previewTableMap(request);
        assertEquals("warehouse_orders", result.getFirst().getTargetName());
        assertEquals("source comment", result.getFirst().getRemarks());
        request.setAccessMode(2);
        assertThrows(ServiceException.class, () -> service.previewTableMap(request));
        request.setAccessMode(1); request.setTargetDatabaseId(null);
        assertThrows(ServiceException.class, () -> service.previewTableMap(request));
    }

    @Test void columnPreviewRejectsDorisKeysThatDoNotSurviveColumnMapping() {
        var id = new ColumnDescription(); id.setFieldName("record_id"); id.setFieldType(java.sql.Types.BIGINT);
        id.setFieldTypeName("int8"); id.setNullable(false);
        when(metadata.queryTableColumnsAndKeys("fixture-connection", null, null, "source_scope", "orders"))
                .thenReturn(new BasicTableMetadata(List.of(id), List.of("record_id")));
        var request = preview(); request.setPreviewTableName("orders");
        assertEquals("record_id", service.previewColumnMap(request).getFirst().getTargetName());
        request.setColumnNameMapper(List.of(new AccessNameMapping("^record_id$", "renamed_id")));
        assertThrows(ServiceException.class, () -> service.previewColumnMap(request));
    }

    @Test void persistedOptionsRejectUnknownFieldsAndNumericCoercion() {
        var config = new AccessTaskConfig(); config.setSource(new ArrayList<>(List.of(new AccessTaskConfig.Source())));
        config.getSource().getFirst().setConfigMap(Map.of("orders", mapping()));
        String json = AccessTaskConfigCodec.write(config);
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.read(json.replace("\"9007199254740993\"", "9007199254740993")));
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.read(json.replace("\"warehouse_orders\"", "42")));
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.read(json.replace("\"replicationNum\":1", "\"replicationNum\":1,\"sql\":\"DROP TABLE x\"")));
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.read(json.replace("\"bucketCount\":3", "\"bucketCount\":3.5")));
        assertThrows(IllegalArgumentException.class, () -> AccessTaskConfigCodec.read(json.replace("\"distributionType\":\"HASH\"", "\"distributionType\":\"UNKNOWN\"")));
    }

    private static DataAccessSaveReqVO request() {
        var request = new DataAccessSaveReqVO(); request.setTaskName("multi table sync"); request.setEngineType(1);
        request.setSourceType(1); request.setTaskType(2); request.setSourceDatabaseId(1L); request.setTargetDatabaseId(2L);
        request.setAccessMode(1); request.setMapperType("ALL"); request.setSourceSelectedTables(List.of("orders"));
        request.setTargetTableNameCase("UPPER"); request.setTableNameMapper(List.of(new AccessNameMapping("^", "legacy_")));
        return request;
    }

    private static DataAccessPreviewReqVO preview() {
        var request = new DataAccessPreviewReqVO(); request.setSourceType(1); request.setSourceDatabaseId(1L);
        request.setTargetDatabaseId(2L); request.setAccessMode(1); request.setMapperType("ALL");
        request.setSourceSelectedTables(List.of("orders")); request.setConfigMap(Map.of("orders", mapping()));
        request.setTargetTableNameCase("UPPER"); return request;
    }

    private static AccessTableMapping mapping() {
        var mapping = new AccessTableMapping(); mapping.setTargetTableName("warehouse_orders"); mapping.setTargetTableComment("目标表注释");
        mapping.setDorisOptions(new AccessDorisOptions(new AccessDorisOptions.Partition("RANGE", List.of("record_id"),
                List.of(new AccessDorisOptions.PartitionEntry("p_large", List.of(List.of("9007199254740993"), List.of("MAXVALUE"))))),
                "HASH", List.of("record_id"), "FIXED", 3, 1));
        return mapping;
    }
}

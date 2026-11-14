package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.DataAccessSaveReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.access.DataAccessDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.database.DatabaseDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.access.DataAccessMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.database.DatabaseMapper;
import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfigMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实向导省略 writeMode；只有明确关闭已有表同步的窄新表选择可规范化。 */
class AccessWriteModeSaveTest {
    private final DataAccessServiceImpl service = new DataAccessServiceImpl();
    private final DataAccessMapper tasks = mock(DataAccessMapper.class);
    private final AccessDatabaseResolver resolver = mock(AccessDatabaseResolver.class);
    private final DatabaseMapper databases = mock(DatabaseMapper.class);
    private final DatabaseDO database = DatabaseDO.builder().id(1L).databaseType(1).build();

    AccessWriteModeSaveTest() {
        when(databases.selectById(1L)).thenReturn(database);
        when(resolver.resolveProductType(database)).thenReturn(ProductTypeEnum.POSTGRESQL);
        when(tasks.insert(any(DataAccessDO.class))).thenAnswer(invocation -> {
            invocation.<DataAccessDO>getArgument(0).setId(6L); return 1;
        });
        ReflectionTestUtils.setField(service, "databaseMapper", databases);
        ReflectionTestUtils.setField(service, "databaseResolver", resolver);
        ReflectionTestUtils.setField(service, "dataAccessMapper", tasks);
    }

    @Test void actualWizardPayloadPersistsCreateNewAndItsFrozenRuntimePassesTheClosedGate() throws Exception {
        var request = request(); assertNull(request.getWriteMode());
        var saved = save(request);
        assertEquals("CREATE_NEW", saved.getDataAccessJson().getTarget().getWriteMode());
        assertFalse(saved.getDataAccessJson().getTarget().isSyncExist());
        assertNull(request.getWriteMode());
        var runtime = AccessTaskConfigMapper.toRuntime(AccessRunSnapshot.restore(AccessRunSnapshot.freeze(saved))
                .getDataAccessJson(), 1L);
        runtime.getSource().getFirst().setSourceProductType(ProductTypeEnum.POSTGRESQL);
        runtime.getTarget().setTargetProductType(ProductTypeEnum.POSTGRESQL);
        assertEquals("CREATE_NEW", runtime.getTarget().getWriteMode());
        assertDoesNotThrow(() -> MigrationService.validateAuthorizedConfiguration(runtime));
    }

    @Test void postgresToDorisWizardPersistsCreateNewWithoutRequiringHiddenWriteMode() throws Exception {
        var doris = DatabaseDO.builder().id(2L).databaseType(2).build();
        when(databases.selectById(2L)).thenReturn(doris);
        when(resolver.resolveProductType(doris)).thenReturn(ProductTypeEnum.DORIS);
        var request = request(); request.setTargetDatabaseId(2L);
        var saved = save(request);
        assertEquals("CREATE_NEW", saved.getDataAccessJson().getTarget().getWriteMode());
        var runtime = AccessTaskConfigMapper.toRuntime(saved.getDataAccessJson(), 1L);
        runtime.getSource().getFirst().setSourceProductType(ProductTypeEnum.POSTGRESQL);
        runtime.getTarget().setTargetProductType(ProductTypeEnum.DORIS);
        assertDoesNotThrow(() -> MigrationService.validateAuthorizedConfiguration(runtime));
    }

    @ParameterizedTest
    @ValueSource(strings = {"syncExist", "drop", "truncate", "incremental", "onlyCreate", "index", "default", "identity", "comment"})
    void existingTargetsAndAdditionalOperationsNeverAcquireImplicitCreateNew(String flag) throws Exception {
        var request = request();
        switch (flag) {
            case "syncExist" -> request.setTargetSyncExit(true);
            case "drop" -> request.setTargetDropTable(true);
            case "truncate" -> request.setTargetTruncateData(true);
            case "incremental" -> { request.setTargetDataSync(true); request.setChangeDataSyncType("PK_ORDER_COMPARE"); }
            case "onlyCreate" -> request.setTargetOnlyCreate(true);
            case "index" -> request.setTargetIndexCreate(true);
            case "default" -> request.setSyncDefaultVal(true);
            case "identity" -> request.setTargetAutoIncrement(true);
            case "comment" -> request.getConfigMap().get("monitor_source").setTargetTableComment("结构选项");
        }
        var saved = save(request);
        assertNull(saved.getDataAccessJson().getTarget().getWriteMode());
        var runtime = AccessTaskConfigMapper.toRuntime(saved.getDataAccessJson(), 1L);
        runtime.getSource().getFirst().setSourceProductType(ProductTypeEnum.POSTGRESQL);
        runtime.getTarget().setTargetProductType(ProductTypeEnum.POSTGRESQL);
        assertThrows(IllegalArgumentException.class, () -> MigrationService.validateAuthorizedConfiguration(runtime));
    }

    @ParameterizedTest @ValueSource(strings = {"CREATE_NEW", "STRICT_COPY"})
    void explicitModeRemainsUnchanged(String mode) throws Exception {
        var request = request(); request.setWriteMode(mode);
        assertEquals(mode, save(request).getDataAccessJson().getTarget().getWriteMode());
    }

    @Test void unknownModeIsRejectedBeforePersistence() throws Exception {
        var request = request(); request.setWriteMode("UNREVIEWED_MODE");
        assertThrows(ServiceException.class, () -> service.createDataAccess(request));
        verify(tasks, never()).insert(any(DataAccessDO.class));
    }

    @Test void otherProductsAndUnknownProductMetadataAreNotNormalized() throws Exception {
        when(resolver.resolveProductType(database)).thenReturn(ProductTypeEnum.MYSQL);
        assertNull(save(request()).getDataAccessJson().getTarget().getWriteMode());
        clearInvocations(tasks, resolver);
        database.setDatabaseType(null);
        assertNull(save(request()).getDataAccessJson().getTarget().getWriteMode());
        verifyNoInteractions(resolver);
    }

    private DataAccessDO save(DataAccessSaveReqVO request) {
        assertEquals(6L, service.createDataAccess(request));
        var captor = ArgumentCaptor.forClass(DataAccessDO.class);
        verify(tasks).insert(captor.capture());
        return captor.getValue();
    }

    private DataAccessSaveReqVO request() throws Exception {
        return new tools.jackson.databind.ObjectMapper().readValue("""
                {"taskName":"监控读写验收","engineType":1,"taskType":2,"sourceType":1,
                 "sourceDatabaseId":1,"targetDatabaseId":1,"accessMode":2,"includeOrExclude":1,
                 "sourceSelectedTables":["monitor_source"],"sourcePrimaryKeys":[],"mapperType":"SINGLE",
                 "targetOnlyCreate":false,"targetSyncExit":false,"targetDropTable":false,
                 "targetTruncateData":false,"targetDataSync":false,"targetIndexCreate":false,
                 "syncDefaultVal":false,"targetAutoIncrement":false,"targetTableNameCase":"NONE",
                 "targetColumnNameCase":"NONE","batchSize":1000,
                 "configMap":{"monitor_source":{"targetTableName":"monitor_target",
                   "targetTableComment":"","regexTableMapper":[],"regexColumnMapper":[],"sourcePrimaryKeys":[]}}}
                """, DataAccessSaveReqVO.class);
    }
}

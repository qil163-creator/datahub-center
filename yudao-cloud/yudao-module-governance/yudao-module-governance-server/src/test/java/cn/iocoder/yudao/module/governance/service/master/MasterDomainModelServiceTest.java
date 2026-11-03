package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.module.governance.controller.admin.master.MasterModelController;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.*;
import cn.iocoder.yudao.module.governance.dal.mysql.master.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.List;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MasterDomainModelServiceTest {
    private MasterDomainMapper domains;
    private MasterModelMapper models;
    private MasterColumnMapper columns;
    private MasterDistributeMapper distributes;
    private MasterCodeRuleMapper rules;
    private MasterModelServiceImpl modelService;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), "master-model-test"),
                MasterModelDO.class);
        domains = mock(MasterDomainMapper.class);
        models = mock(MasterModelMapper.class);
        columns = mock(MasterColumnMapper.class);
        distributes = mock(MasterDistributeMapper.class);
        rules = mock(MasterCodeRuleMapper.class);
        modelService = new MasterModelServiceImpl();
        ReflectionTestUtils.setField(modelService, "logRecordService", mock(com.mzt.logapi.service.ILogRecordService.class));
        ReflectionTestUtils.setField(modelService, "domainMapper", domains);
        ReflectionTestUtils.setField(modelService, "modelMapper", models);
        ReflectionTestUtils.setField(modelService, "columnMapper", columns);
        ReflectionTestUtils.setField(modelService, "distributeMapper", distributes);
        ReflectionTestUtils.setField(modelService, "codeRuleMapper", rules);
        ReflectionTestUtils.setField(modelService, "dataChangeMapper", mock(MasterDataChangeMapper.class));
        when(domains.selectForUpdate(10L)).thenReturn(MasterDomainDO.builder().id(10L).status(0).build());
    }

    @Test
    void createsMultipleModelsUnderDomainWithoutDirectoryOrClientOrganization() {
        when(models.insert(any(MasterModelDO.class))).thenAnswer(call -> {
            call.<MasterModelDO>getArgument(0).setId(100L);
            return 1;
        });
        MasterModelSaveReqVO req = request();
        req.setCatalogId(999L);
        assertEquals(100L, modelService.createModel(req));
        ArgumentCaptor<MasterModelDO> captured = ArgumentCaptor.forClass(MasterModelDO.class);
        verify(models).insert(captured.capture());
        assertEquals(10L, captured.getValue().getDomainId());
        assertNull(captured.getValue().getCatalogId());
        assertNull(captured.getValue().getOrgId());
        assertEquals(1, captured.getValue().getVersion());
        assertFalse(captured.getValue().getApprovalEnabled());
        verify(models, never()).selectByCatalogId(any());
    }

    @Test
    void disabledDomainCannotReceiveModel() {
        when(domains.selectForUpdate(10L)).thenReturn(MasterDomainDO.builder().id(10L).status(1).build());
        ServiceException error = assertThrows(ServiceException.class, () -> modelService.createModel(request()));
        assertEquals(MASTER_DOMAIN_DISABLED.getCode(), error.getCode());
        verify(models, never()).insert(any(MasterModelDO.class));
    }

    @Test
    void deletionLocksDomainAndRejectsReferencedModels() {
        MasterDomainServiceImpl service = new MasterDomainServiceImpl();
        ReflectionTestUtils.setField(service, "domainMapper", domains);
        ReflectionTestUtils.setField(service, "modelMapper", models);
        when(models.selectCountByDomainId(10L)).thenReturn(1L);
        ServiceException error = assertThrows(ServiceException.class, () -> service.deleteDomain(10L));
        assertEquals(MASTER_DOMAIN_HAS_MODEL.getCode(), error.getCode());
        var ordered = inOrder(domains, models);
        ordered.verify(domains).selectForUpdate(10L);
        ordered.verify(models).selectCountByDomainId(10L);
        verify(domains, never()).deleteById(any(Long.class));
    }

    @Test
    void previouslyPublishedTargetBindingStaysFixedAfterOffline() {
        MasterModelDO old = model(2);
        when(models.selectForUpdate(100L)).thenReturn(old);
        MasterColumnDO column = MasterColumnDO.builder().name("code").fieldType(2).pk(1).nullable(0).build();
        when(columns.selectListByModelId(100L)).thenReturn(List.of(column));
        MasterModelSaveReqVO req = request();
        req.setId(100L);
        req.setTableName("changed_table");
        ServiceException error = assertThrows(ServiceException.class, () -> modelService.updateModel(req));
        assertEquals(MASTER_MODEL_STRUCTURE_FROZEN.getCode(), error.getCode());
        verify(columns, never()).deleteByModelId(any());
    }

    @Test
    void offlineDoesNotResolveExternalDatabaseOrDeletePhysicalTable() {
        when(models.selectForUpdate(100L)).thenReturn(model(1));
        MasterDatabaseResolver resolver = mock(MasterDatabaseResolver.class);
        ReflectionTestUtils.setField(modelService, "databaseResolver", resolver);
        modelService.cancelReleaseModel(100L);
        verifyNoInteractions(resolver);
        verify(models).update(isNull(), any());
        verify(columns, never()).deleteByModelId(any());
    }

    @Test
    void uncertainPublicationCannotBeUsedForMaintenance() {
        MasterModelDO old = model(1);
        old.setPublishState(MasterModelDO.PUBLISH_UNKNOWN);
        when(models.selectById(100L)).thenReturn(old);
        ServiceException error = assertThrows(ServiceException.class, () -> modelService.validateModelReleased(100L));
        assertEquals(MASTER_MODEL_PUBLISH_UNCERTAIN.getCode(), error.getCode());
    }

    @Test
    void partialDdlFailureKeepsPhysicalTableAndMarksUnknown() {
        MasterModelDO draft = model(0);
        draft.setReleaseTime(null);
        when(models.selectById(100L)).thenReturn(draft);
        when(models.claimPublish(draft)).thenReturn(1);
        when(columns.selectListByModelId(100L)).thenReturn(List.of(
                MasterColumnDO.builder().name("code").fieldType(2).fieldLength(64).pk(1).nullable(0).build()));
        MetaDataByJdbcServiceImpl metadata = externalMetadata();
        doThrow(new IllegalStateException("fixture timeout")).when(metadata).executeSql(any(), any(), any(), any());
        ServiceException error = assertThrows(ServiceException.class, () -> modelService.releaseModel(100L));
        assertEquals(MASTER_MODEL_PUBLISH_UNCERTAIN.getCode(), error.getCode());
        verify(models).markPublishUnknown(100L);
        verify(models, never()).completePublish(anyLong(), anyBoolean());
        verify(metadata, never()).dropTable(any(), any(), any(), any(), any());
    }

    @Test
    void republishOfflineModelConfirmsExistingStructureWithoutDdl() {
        MasterModelDO offline = model(2);
        when(models.selectById(100L)).thenReturn(offline);
        when(models.claimPublish(offline)).thenReturn(1);
        when(models.completePublish(100L, false)).thenReturn(1);
        when(columns.selectListByModelId(100L)).thenReturn(List.of(
                MasterColumnDO.builder().name("code").fieldType(2).pk(1).nullable(0).build()));
        MetaDataByJdbcServiceImpl metadata = externalMetadata();
        ColumnDescription actual = new ColumnDescription();
        actual.setFieldName("code"); actual.setFieldType(java.sql.Types.VARCHAR); actual.setNullable(false);
        when(metadata.queryTableColumnMeta(any(), any(), any(), any(), any())).thenReturn(List.of(actual));
        when(metadata.queryTablePrimaryKeys(any(), any(), any(), any(), any())).thenReturn(List.of("code"));
        modelService.releaseModel(100L);
        verify(metadata, never()).executeSql(any(), any(), any(), any());
        verify(metadata, never()).dropTable(any(), any(), any(), any(), any());
        verify(models).completePublish(100L, false);
    }

    private MetaDataByJdbcServiceImpl externalMetadata() {
        DbSwitchRuntime runtime = mock(DbSwitchRuntime.class);
        when(runtime.products()).thenReturn(DbSwitchProductRegistry.discover());
        MetaDataByJdbcServiceImpl metadata = mock(MetaDataByJdbcServiceImpl.class);
        when(runtime.jdbcMetadata(ProductTypeEnum.POSTGRESQL)).thenReturn(metadata);
        MasterDatabaseResolver resolver = mock(MasterDatabaseResolver.class);
        var conn = new MasterDatabaseResolver.ConnectionInfo();
        conn.setDatabaseId(20L); conn.setProductType(ProductTypeEnum.POSTGRESQL); conn.setSchema("public");
        conn.setJdbcUrl("jdbc:postgresql://invalid.test/fixture");
        when(resolver.resolve(eq(20L), any())).thenReturn(conn);
        when(domains.selectById(10L)).thenReturn(MasterDomainDO.builder().id(10L).status(0).build());
        ReflectionTestUtils.setField(modelService, "dbSwitchRuntime", runtime);
        ReflectionTestUtils.setField(modelService, "databaseResolver", resolver);
        return metadata;
    }

    @Test
    void draftWithCodeRuleCannotBeDeleted() {
        MasterModelDO draft = model(0);
        draft.setReleaseTime(null);
        when(models.selectForUpdate(100L)).thenReturn(draft);
        when(rules.selectCount(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1L);
        when(rules.selectCount(any(com.baomidou.mybatisplus.core.toolkit.support.SFunction.class), eq(100L))).thenReturn(1L);
        ServiceException error = assertThrows(ServiceException.class, () -> modelService.deleteModel(100L));
        assertEquals(MASTER_MODEL_HAS_CODE_RULE.getCode(), error.getCode());
        verify(models, never()).deleteById(any(Long.class));
    }

    @Test
    void maintenanceAndRulesCanResolveModelsWithoutModelMenuPermission() throws Exception {
        String getPermission = MasterModelController.class.getMethod("getModel", Long.class)
                .getAnnotation(PreAuthorize.class).value();
        assertTrue(getPermission.contains("governance:master-data:query"));
        assertTrue(getPermission.contains("governance:master-code-rule:query"));
        String listPermission = MasterModelController.class.getMethod("getReleasedModelList")
                .getAnnotation(PreAuthorize.class).value();
        assertTrue(listPermission.contains("governance:master-data:query"));
    }

    private static MasterModelSaveReqVO request() {
        MasterModelSaveReqVO req = new MasterModelSaveReqVO();
        req.setDomainId(10L);
        req.setDatabaseId(20L);
        req.setTableName("master_person");
        req.setTableCn("人员");
        MasterColumnVO column = new MasterColumnVO();
        column.setName("code");
        column.setFieldType(ColumnMetaData.TYPE_STRING);
        column.setPk(1);
        req.setColumns(List.of(column));
        return req;
    }

    private static MasterModelDO model(int status) {
        return MasterModelDO.builder().id(100L).domainId(10L).databaseId(20L).tableName("master_person")
                .tableCn("人员").status(status).releaseTime(LocalDateTime.of(2026, 10, 12, 12, 0))
                .publishState(MasterModelDO.PUBLISH_READY).build();
    }
}

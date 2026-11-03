package cn.iocoder.yudao.module.asset.service.quality;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.asset.dal.dataobject.quality.AssetQualityTaskDO;
import cn.iocoder.yudao.module.asset.dal.dataobject.quality.AssetQualityTaskRuleDO;
import cn.iocoder.yudao.module.asset.dal.mysql.quality.AssetQualityTaskMapper;
import cn.iocoder.yudao.module.asset.dal.mysql.quality.AssetQualityTaskRuleMapper;
import cn.iocoder.yudao.module.governance.api.quality.QualityTaskApi;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityAssetEvaluationDTO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityAssetSyncReqDTO;
import cn.iocoder.yudao.module.asset.controller.admin.quality.vo.AssetQualityTaskSaveReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;
import cn.iocoder.yudao.module.asset.dal.mysql.dataasset.DataAssetMapper;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogService;
import cn.iocoder.yudao.module.governance.api.metadata.MetadataApi;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataTableRespDTO;
import cn.iocoder.yudao.module.governance.api.metadata.dto.MetadataColumnRespDTO;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.DATA_ASSET_QUALITY_DELETE_PENDING;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_TASK_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static cn.iocoder.yudao.module.asset.enums.ErrorCodeConstants.*;

class AssetQualityTaskServiceImplTest extends BaseMockitoUnitTest {

    @InjectMocks
    private AssetQualityTaskServiceImpl service;
    @Mock
    private AssetQualityTaskMapper taskMapper;
    @Mock
    private AssetQualityTaskRuleMapper taskRuleMapper;
    @Mock
    private QualityTaskApi qualityTaskApi;
    @Mock private DataAssetMapper dataAssetMapper;
    @Mock private MetadataApi metadataApi;
    @Mock private AssetCatalogService catalogService;
    @Mock private PlatformTransactionManager transactionManager;

    @BeforeEach
    void binding() {
        var asset = DataAssetDO.builder().id(1L).datasourceId(3L).metaTableId(4L).tableName("source_table").build();
        lenient().when(dataAssetMapper.selectById(1L)).thenReturn(asset);
        var table = new MetadataTableRespDTO(); table.setId(4L); table.setDatasourceId(3L); table.setTableName("source_table");
        lenient().when(metadataApi.getTable(4L)).thenReturn(CommonResult.success(table));
        var column = new MetadataColumnRespDTO(); column.setId(7L); column.setColumnName("amount");
        lenient().when(metadataApi.getColumnList(4L)).thenReturn(CommonResult.success(List.of(column)));
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().doAnswer(invocation -> { ((AssetQualityTaskDO) invocation.getArgument(0)).setId(5L); return 1; }).when(taskMapper).insert(any(AssetQualityTaskDO.class));
    }

    private AssetQualityTaskSaveReqVO inline() {
        var request = new AssetQualityTaskSaveReqVO(); request.setAssetId(1L); request.setTaskName("范围检查");
        request.setScheduleType(0); request.setEnabled(0);
        var evaluation = new QualityAssetEvaluationDTO(); evaluation.setEvaluationName("金额范围");
        evaluation.setRuleCode("306"); evaluation.setStrategyKey("NUMERIC_RANGE_VALIDATION"); evaluation.setQualityDim("CONSISTENCY");
        evaluation.setColumnNames(List.of("amount")); evaluation.setConfig(Map.of("minValue", "9007199254740993.123456789"));
        request.setEvaluations(List.of(evaluation)); return request;
    }

    @Test void inlineDefinitionCommitsBeforeEngineAndKeepsExactConfiguration() {
        when(qualityTaskApi.syncFromAsset(any())).thenReturn(CommonResult.success(88L));
        assertEquals(5L, service.createTask(inline()));
        var definition = ArgumentCaptor.forClass(AssetQualityTaskDO.class);
        verify(taskMapper).insert(definition.capture());
        assertEquals("9007199254740993.123456789", JsonUtils.parseArray(definition.getValue().getEvaluationJson(), QualityAssetEvaluationDTO.class).getFirst().getConfig().get("minValue"));
        var rpc = ArgumentCaptor.forClass(QualityAssetSyncReqDTO.class);
        verify(qualityTaskApi).syncFromAsset(rpc.capture());
        assertEquals(3L, rpc.getValue().getDatasourceId()); assertEquals(4L, rpc.getValue().getTableId());
        assertEquals("source_table", rpc.getValue().getTableName()); assertEquals(1, rpc.getValue().getEvaluations().size());
        var order = inOrder(transactionManager, qualityTaskApi);
        order.verify(transactionManager).commit(any()); order.verify(qualityTaskApi).syncFromAsset(any());
        verify(taskRuleMapper, never()).insertBatch(anyCollection());
    }

    @Test void timeoutPreservesDefinitionAndRecordsUnknownResult() {
        when(qualityTaskApi.syncFromAsset(any())).thenThrow(new RuntimeException("timeout"));
        var failure = assertThrows(ServiceException.class, () -> service.createTask(inline()));
        assertEquals(ASSET_QUALITY_TASK_NOT_SYNCED.getCode(), failure.getCode());
        verify(transactionManager).commit(any()); verify(transactionManager, never()).rollback(any());
        var updates = ArgumentCaptor.forClass(AssetQualityTaskDO.class);
        verify(taskMapper).updateById(updates.capture());
        org.junit.jupiter.api.Assertions.assertTrue(updates.getValue().getRemoteError().contains("未知"));
    }

    @Test void rejectsImportedOrForeignPhysicalColumnsBeforeSaving() {
        var request = inline(); request.getEvaluations().getFirst().setColumnNames(List.of("foreign_column"));
        assertEquals(ASSET_QUALITY_COLUMN_INVALID.getCode(), assertThrows(ServiceException.class, () -> service.createTask(request)).getCode());
        verify(taskMapper, never()).insert(any(AssetQualityTaskDO.class)); verifyNoInteractions(qualityTaskApi);
        when(dataAssetMapper.selectById(1L)).thenReturn(DataAssetDO.builder().id(1L).externalCode("qdata:asset:49").build());
        assertEquals(DATA_ASSET_TECH_UNAVAILABLE.getCode(), assertThrows(ServiceException.class, () -> service.createTask(inline())).getCode());
    }

    @Test void rejectsMaintainScopeBeforeMetadataOrEngine() {
        doThrow(new ServiceException(DATA_ASSET_FORBIDDEN)).when(catalogService).assertCanMaintain(any());
        assertEquals(DATA_ASSET_FORBIDDEN.getCode(), assertThrows(ServiceException.class, () -> service.createTask(inline())).getCode());
        verifyNoInteractions(metadataApi, qualityTaskApi);
    }

    @Test void executeRejectsAStoredEvaluationWhosePhysicalColumnWasRemoved() {
        var definition = inline(); definition.getEvaluations().getFirst().setColumnNames(List.of("removed_column"));
        when(taskMapper.selectById(5L)).thenReturn(AssetQualityTaskDO.builder().id(5L).assetId(1L).qualityTaskId(88L)
                .evaluationJson(JsonUtils.toJsonString(definition.getEvaluations())).build());
        when(taskRuleMapper.selectListByTaskIds(List.of(5L))).thenReturn(List.of());
        assertEquals(ASSET_QUALITY_COLUMN_INVALID.getCode(), assertThrows(ServiceException.class, () -> service.executeTask(5L)).getCode());
        verifyNoInteractions(qualityTaskApi);
    }

    @Test void legacyRulesStillMapTheirRealColumnBinding() {
        var request = inline(); request.setEvaluations(null);
        var rule = new AssetQualityTaskSaveReqVO.Rule(); rule.setQualityRuleId(31L); rule.setRuleName("旧规则"); rule.setMetaColumnId(7L);
        request.setRules(List.of(rule));
        when(taskRuleMapper.selectListByTaskIds(List.of(5L))).thenReturn(List.of(AssetQualityTaskRuleDO.builder().id(9L).taskId(5L).qualityRuleId(31L).metaColumnId(7L).columnName("amount").build()));
        when(qualityTaskApi.syncFromAsset(any())).thenReturn(CommonResult.success(88L));
        assertEquals(5L, service.createTask(request));
        var rpc = ArgumentCaptor.forClass(QualityAssetSyncReqDTO.class); verify(qualityTaskApi).syncFromAsset(rpc.capture());
        assertEquals(List.of(31L), rpc.getValue().getRuleIds()); assertEquals("col-7", rpc.getValue().getBindingKey());
        assertEquals("amount", rpc.getValue().getColumnName());
    }

    @Test
    void deleteTask_keepsLocalWhenRemoteFails() {
        AssetQualityTaskDO task = AssetQualityTaskDO.builder()
                .id(5L)
                .assetId(1L)
                .qualityTaskId(88L)
                .build();
        when(taskMapper.selectById(5L)).thenReturn(task);
        when(taskRuleMapper.selectListByTaskIds(List.of(5L))).thenReturn(List.of());
        when(qualityTaskApi.delete(88L)).thenThrow(new RuntimeException("timeout"));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.deleteTask(5L));
        assertEquals(DATA_ASSET_QUALITY_DELETE_PENDING.getCode(), ex.getCode());
        verify(taskMapper, never()).deleteById(anyLong());
    }

    @Test
    void deleteTask_treatsMissingRemoteAsSuccess() {
        AssetQualityTaskDO task = AssetQualityTaskDO.builder()
                .id(5L)
                .assetId(1L)
                .qualityTaskId(88L)
                .build();
        when(taskMapper.selectById(5L)).thenReturn(task);
        when(taskRuleMapper.selectListByTaskIds(List.of(5L))).thenReturn(List.of());
        when(qualityTaskApi.delete(88L)).thenThrow(new ServiceException(QUALITY_TASK_NOT_EXISTS));

        service.deleteTask(5L);
        verify(taskMapper).deleteById(5L);
        verify(taskRuleMapper).deleteByTaskId(5L);
    }

    @Test
    void deleteTask_checkErrorNotExistsIsSuccess() {
        AssetQualityTaskDO task = AssetQualityTaskDO.builder()
                .id(5L)
                .assetId(1L)
                .qualityTaskId(88L)
                .build();
        when(taskMapper.selectById(5L)).thenReturn(task);
        when(taskRuleMapper.selectListByTaskIds(List.of(5L))).thenReturn(List.of());
        when(qualityTaskApi.delete(88L)).thenReturn(CommonResult.success(true));

        service.deleteTask(5L);
        verify(taskMapper).deleteById(5L);
    }

}

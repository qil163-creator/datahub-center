package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlRegistry;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityAssetEvaluationDTO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityAssetSyncReqDTO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task.QualityTaskSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssetInlineQualitySyncTest {
    private QualityAssetSyncReqDTO request() {
        var request = new QualityAssetSyncReqDTO(); request.setAssetTaskId(41L); request.setTaskName("资产检查");
        request.setDatasourceId(3L); request.setTableId(4L); request.setTableName("public.actual_table"); request.setEnabled(0);
        var evaluation = new QualityAssetEvaluationDTO(); evaluation.setEvaluationName("金额范围");
        evaluation.setRuleCode("306"); evaluation.setStrategyKey("NUMERIC_RANGE_VALIDATION"); evaluation.setQualityDim("CONSISTENCY");
        evaluation.setColumnNames(List.of("amount")); evaluation.setConfig(Map.of("minValue", "9007199254740993.123456789"));
        request.setEvaluations(List.of(evaluation)); return request;
    }

    @Test void inlineUsesCanonicalAssetObjectAndStableTaskCodeWithExistingValidation() {
        var service = spy(new QualityTaskServiceImpl());
        var mapper = mock(QualityTaskMapper.class); var objects = mock(QualityCheckObjectMapper.class);
        ReflectionTestUtils.setField(service, "taskMapper", mapper); ReflectionTestUtils.setField(service, "checkObjectMapper", objects);
        var validator = QualityTaskEvaluationServiceTest.service();
        var captured = new AtomicReference<QualityTaskSaveReqVO>();
        doAnswer(invocation -> { QualityTaskSaveReqVO value = invocation.getArgument(0); validator.validate(value); captured.set(value); return 81L; }).when(service).createTask(any());
        assertEquals(81L, service.syncFromAsset(request()));
        var value = captured.get(); assertEquals("ASSET-41-inline", value.getTaskCode());
        assertEquals(3L, value.getObjects().getFirst().getDatasourceConnId());
        assertEquals(4L, value.getObjects().getFirst().getTableId());
        assertEquals("public.actual_table", value.getObjects().getFirst().getTableName());
        assertEquals("asset", value.getEvaluations().getFirst().getObjectKey());
        assertEquals("9007199254740993.123456789", value.getEvaluations().getFirst().getConfig().get("minValue"));
        assertNull(value.getRuleIds()); assertNull(value.getObjectIds()); verifyNoInteractions(objects);
    }

    @Test void unknownRuleAndMixedModesCannotBecomeExecutableTasks() {
        var service = spy(new QualityTaskServiceImpl()); var mapper = mock(QualityTaskMapper.class);
        ReflectionTestUtils.setField(service, "taskMapper", mapper);
        var validator = QualityTaskEvaluationServiceTest.service();
        doAnswer(invocation -> { validator.validate(invocation.getArgument(0)); return 81L; }).when(service).createTask(any());
        var request = request(); request.getEvaluations().getFirst().setRuleCode("UNKNOWN");
        assertThrows(ServiceException.class, () -> service.syncFromAsset(request));
        verify(mapper, never()).insert(any(cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskDO.class));
        request.setRuleIds(List.of(11L));
        assertFalse(request.isRuleSelectionValid()); assertThrows(ServiceException.class, () -> service.syncFromAsset(request));
    }

    @Test void legacyRuleSelectionStillRequiresRealStoredRule() {
        var service = new QualityTaskServiceImpl(); var rules = mock(QualityRuleMapper.class);
        ReflectionTestUtils.setField(service, "ruleMapper", rules);
        var request = request(); request.setEvaluations(null); request.setRuleIds(List.of(77L));
        assertTrue(request.isRuleSelectionValid());
        assertThrows(ServiceException.class, () -> service.syncFromAsset(request));
        verify(rules).selectById(77L);
    }
}

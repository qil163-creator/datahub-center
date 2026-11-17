package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.api.database.DatabaseApi;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabaseConnectionRespDTO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.checkobject.QualityCheckObjectSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.ruleset.QualityRuleSetItemSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.tasktemplate.QualityTaskTemplateSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QualityConfigurationValidationTest {

    final QualityTaskTemplateMapper templates = mock(QualityTaskTemplateMapper.class);
    final QualityTaskTemplateServiceImpl templateService = new QualityTaskTemplateServiceImpl();

    QualityConfigurationValidationTest() {
        ReflectionTestUtils.setField(templateService, "templateMapper", templates);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-a-cron", "0 0 25 * * ?"})
    void timedTemplateRejectsInvalidCronOnCreateAndEdit(String cron) {
        var req = new QualityTaskTemplateSaveReqVO();
        req.setTemplateName("模板");
        req.setScheduleType("TIMER");
        req.setCronExpression(cron);
        assertEquals(QUALITY_TASK_CRON_INVALID.getCode(),
                assertThrows(ServiceException.class, () -> templateService.createTemplate(req)).getCode());
        req.setId(12L);
        when(templates.selectOneForUpdate(any(), eq(12L))).thenReturn(
                QualityTaskTemplateDO.builder().id(12L).scheduleType("MANUAL").build());
        assertEquals(QUALITY_TASK_CRON_INVALID.getCode(),
                assertThrows(ServiceException.class, () -> templateService.updateTemplate(req)).getCode());
        verify(templates, never()).insert(any(QualityTaskTemplateDO.class));
        verify(templates, never()).updateById(any(QualityTaskTemplateDO.class));
    }

    @Test
    void legacyTimerCannotBeEnabledUntilItsCronIsRepairedAndManualNeedsNoCron() {
        var existing = QualityTaskTemplateDO.builder().id(12L).scheduleType("TIMER").cronExpression("bad").build();
        when(templates.selectOneForUpdate(any(), eq(12L))).thenReturn(existing);
        assertThrows(ServiceException.class, () -> templateService.updateTemplateStatus(12L, 0));
        assertDoesNotThrow(() -> templateService.updateTemplateStatus(12L, 1));
        var req = new QualityTaskTemplateSaveReqVO();
        req.setId(12L);
        req.setCronExpression("@daily");
        assertDoesNotThrow(() -> templateService.updateTemplate(req));
        req.setScheduleType("TIMER");
        req.setCronExpression("0 0 2 * * ?");
        assertDoesNotThrow(() -> templateService.updateTemplate(req));
        req.setScheduleType("MANUAL");
        req.setCronExpression(null);
        assertDoesNotThrow(() -> templateService.updateTemplate(req));
    }

    @Test
    void unavailableRuleRejectsEntireReplacementBeforeDeletingExistingItems() {
        var service = new QualityRuleSetServiceImpl();
        var sets = mock(QualityRuleSetMapper.class);
        var items = mock(QualityRuleSetItemMapper.class);
        var rules = mock(QualityRuleService.class);
        ReflectionTestUtils.setField(service, "ruleSetMapper", sets);
        ReflectionTestUtils.setField(service, "ruleSetItemMapper", items);
        ReflectionTestUtils.setField(service, "qualityRuleService", rules);
        when(sets.selectOneForUpdate(any(), eq(9L))).thenReturn(QualityRuleSetDO.builder().id(9L).build());
        when(rules.getQualityRuleSimpleList()).thenReturn(List.of(QualityRuleDO.builder().id(3L).build()));
        var req = new QualityRuleSetItemSaveReqVO();
        req.setRuleSetId(9L);
        var valid = new QualityRuleSetItemSaveReqVO.Item();
        valid.setRuleId(3L);
        var missing = new QualityRuleSetItemSaveReqVO.Item();
        missing.setRuleId(-1L);
        req.setItems(List.of(valid, missing));
        assertEquals(QUALITY_RULE_SET_RULE_UNAVAILABLE.getCode(),
                assertThrows(ServiceException.class, () -> service.saveRuleSetItems(req)).getCode());
        verifyNoInteractions(items);
        req.setItems(List.of(valid));
        service.saveRuleSetItems(req);
        verify(items).deleteByRuleSetId(9L);
        verify(items).insertBatch(anyCollection());
    }

    @Test
    void checkObjectRequiresAnAvailableSourceOnCreateAndEdit() {
        var service = new QualityCheckObjectServiceImpl();
        var objects = mock(QualityCheckObjectMapper.class);
        var databases = mock(DatabaseApi.class);
        var source = new DatabaseConnectionRespDTO();
        source.setId(42L);
        when(databases.getDatabaseList(null)).thenReturn(CommonResult.success(List.of(source)));
        when(objects.selectById(3L)).thenReturn(QualityCheckObjectDO.builder().id(3L).build());
        ReflectionTestUtils.setField(service, "databaseApi", databases);
        ReflectionTestUtils.setField(service, "checkObjectMapper", objects);
        var req = new QualityCheckObjectSaveReqVO();
        req.setObjectName("核查对象");
        req.setTableName("qa");
        req.setDatasourceConnId(-1L);
        assertEquals(QUALITY_CHECK_OBJECT_SOURCE_MISSING.getCode(),
                assertThrows(ServiceException.class, () -> service.createCheckObject(req)).getCode());
        req.setId(3L);
        assertEquals(QUALITY_CHECK_OBJECT_SOURCE_MISSING.getCode(),
                assertThrows(ServiceException.class, () -> service.updateCheckObject(req)).getCode());
        verify(objects, never()).insert(any(QualityCheckObjectDO.class));
        verify(objects, never()).updateById(any(QualityCheckObjectDO.class));
        req.setDatasourceConnId(42L);
        service.updateCheckObject(req);
        verify(objects).updateById(any(QualityCheckObjectDO.class));
        verify(databases, never()).getDatabaseConnection(anyLong());
    }
}

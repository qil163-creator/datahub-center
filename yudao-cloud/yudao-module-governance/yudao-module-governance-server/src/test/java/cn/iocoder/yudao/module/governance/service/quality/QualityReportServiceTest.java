package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.instance.QualityTaskInstanceRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.*;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QualityReportServiceTest {
    @Test void reportUsesFrozenDefinitionAndLeavesUnmeasuredDaysAndDimensionsEmpty() {
        var service = new QualityReportService();
        var instances = mock(QualityTaskInstanceMapper.class); var audits = mock(QualityAuditLogMapper.class);
        var issues = mock(QualityIssueDataMapper.class); var tasks = mock(QualityTaskMapper.class);
        ReflectionTestUtils.setField(service,"instances",instances); ReflectionTestUtils.setField(service,"audits",audits);
        ReflectionTestUtils.setField(service,"issues",issues); ReflectionTestUtils.setField(service,"tasks",tasks);
        var snapshot = new QualityTaskExecutor.GateDefinition();
        snapshot.setTask(QualityTaskDO.builder().id(7L).taskName("执行时名称").build());
        snapshot.setObjects(List.of(QualityCheckObjectDO.builder().id(10L).datasourceConnName("冻结数据源").datasourceInstance("physical_schema").build()));
        snapshot.setRules(List.of(QualityRuleDO.builder().executionEvaluationId(12L).executionObjectId(10L)
                .strategyKey("CHARACTER_VALIDATION").qualityDim("ACCURACY").severity("CRITICAL").example("非法字符")
                .remark("核对源数据").build()));
        var run = QualityTaskInstanceDO.builder().id(9L).taskId(7L).definitionSnapshot(JsonUtils.toJsonString(snapshot))
                .startTime(LocalDateTime.of(2026,10,16,3,0)).instanceStatus("SUCCESS").totalCount(3L)
                .score(new BigDecimal("66.67")).build();
        when(instances.selectById(9L)).thenReturn(run);
        when(instances.selectList(any(Wrapper.class))).thenReturn(List.of(), List.of(run));
        when(issues.selectList(any(Wrapper.class))).thenReturn(List.of(QualityIssueDataDO.builder().id(13L).auditLogId(11L).build()));
        when(audits.selectListByInstanceId(9L)).thenReturn(List.of(QualityAuditLogDO.builder().id(11L).evaluationId(12L)
                .checkObjectId(10L).ruleName("评测名称").checkField("value").totalCount(3L).failCount(1L).resultType("FAIL").build()));
        var report = service.report(9L, 7);
        assertEquals("执行时名称", report.instance().getTaskName());
        assertEquals("33.33", report.dimensions().getFirst().problemRatio());
        assertNull(report.dimensions().get(1).score()); assertNull(report.dimensions().getFirst().previousProblemRatio());
        assertEquals(7, report.trend().size()); assertNull(report.trend().getFirst().score());
        assertEquals("66.67", report.trend().getLast().score());
        assertEquals("字段字符类型校验", report.rules().getFirst().ruleTypeName());
        assertEquals("冻结数据源", report.rules().getFirst().datasourceName());
        assertEquals("核对源数据", report.rules().getFirst().repairAdvice());
        assertEquals(List.of(13L), report.rules().getFirst().issueIds());
        verifyNoInteractions(tasks);

        when(instances.selectList(any(Wrapper.class))).thenReturn(List.of(), List.of(run));
        when(audits.selectListByInstanceId(9L)).thenReturn(List.of(
                QualityAuditLogDO.builder().id(11L).evaluationId(12L).checkObjectId(10L)
                        .totalCount(3L).failCount(1L).resultType("FAIL").build(),
                QualityAuditLogDO.builder().id(14L).evaluationId(12L).checkObjectId(10L).resultType("ERROR").build()));
        var incomplete = service.report(9L, 7);
        assertNull(incomplete.dimensions().getFirst().score());
        assertNull(incomplete.dimensions().getFirst().problemRatio());
        assertNull(incomplete.rules().getLast().problemRatio());
    }

    @Test void exactDecimalFieldsAreStringsOnTheJsonWire() {
        var instance = new QualityTaskInstanceRespVO();
        instance.setScore(new BigDecimal("99.12345678901234567890")); instance.setPassRate(new BigDecimal("0.00000000000000000001"));
        var json = JsonUtils.parseMap(JsonUtils.toJsonString(instance));
        assertEquals("99.12345678901234567890", json.get("score"));
        assertInstanceOf(String.class, json.get("passRate"));
        assertEquals(new BigDecimal("0.00000000000000000001"), new BigDecimal((String) json.get("passRate")));
    }
}

package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityTaskInstanceMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QualityTaskExecutionIdentityTest {
    @Test void executionErrorsProduceFailedRunAndNoMisleadingScore() {
        var executor = new QualityTaskExecutor(); var mapper = mock(QualityTaskInstanceMapper.class);
        ReflectionTestUtils.setField(executor, "instanceMapper", mapper);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "qualityInstance"), QualityTaskInstanceDO.class);
        ReflectionTestUtils.invokeMethod(executor, "finishInstance", 9L, QualityTaskInstanceDO.STATUS_SUCCESS,
                java.time.LocalDateTime.now(), null, java.util.List.of(
                        QualityAuditLogDO.builder().totalCount(10L).failCount(0L).resultType(QualityAuditLogDO.RESULT_PASS).build(),
                        QualityAuditLogDO.builder().resultType(QualityAuditLogDO.RESULT_ERROR).build()));
        var updates = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), updates.capture());
        var update = updates.getValue();
        assertTrue(update.getParamNameValuePairs().containsValue(QualityTaskInstanceDO.STATUS_FAILED));
        for (String column : java.util.List.of("score", "pass_rate")) {
            var fragment = java.util.Arrays.stream(update.getSqlSet().split(",")).filter(value -> value.startsWith(column + "=")).findFirst().orElseThrow();
            var parameter = fragment.substring(fragment.lastIndexOf('.') + 1, fragment.length() - 1);
            assertNull(update.getParamNameValuePairs().get(parameter));
        }
    }

    @Test void manualAndScheduledRunsPersistTheIdentityThatWasAuthorized() {
        var executor=spy(new QualityTaskExecutor()); var mapper=mock(QualityTaskInstanceMapper.class);
        var authorization=mock(QualityRunAuthorizationService.class);
        ReflectionTestUtils.setField(executor,"instanceMapper",mapper); ReflectionTestUtils.setField(executor,"gateAuthorization",authorization);
        var task=QualityTaskDO.builder().id(1L).taskName("检查").ownerUserId(20L).build();
        doReturn("{}").when(executor).snapshotTaskDefinition(task); doNothing().when(executor).dispatchGate(any());
        when(mapper.selectCountByTaskId(1L)).thenReturn(0L);
        doAnswer(call->{QualityTaskInstanceDO run=call.getArgument(0);run.setId(40L);return 1;}).when(mapper).insert(any(QualityTaskInstanceDO.class));
        try(var security=mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(10L);
            executor.submit(task,QualityTaskInstanceDO.RUN_TYPE_MANUAL,"当前用户");
            executor.submit(task,QualityTaskInstanceDO.RUN_TYPE_SCHEDULE,"schedule");
        }
        var runs=org.mockito.ArgumentCaptor.forClass(QualityTaskInstanceDO.class); verify(mapper,times(2)).insert(runs.capture());
        assertEquals(10L,runs.getAllValues().get(0).getExecutionUserId());
        assertEquals(20L,runs.getAllValues().get(1).getExecutionUserId());
        verify(authorization).require(1L,10L);verify(authorization).require(1L,20L);
    }
}

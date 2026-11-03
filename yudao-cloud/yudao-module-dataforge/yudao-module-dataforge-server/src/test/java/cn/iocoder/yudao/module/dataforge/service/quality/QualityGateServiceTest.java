package cn.iocoder.yudao.module.dataforge.service.quality;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityTaskResultRespDTO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class QualityGateServiceTest {
    private QualityTaskResultRespDTO result() {
        var result = new QualityTaskResultRespDTO();
        result.setInstanceId(1L); result.setInstanceStatus("SUCCESS");
        result.setTotalCount(10L); result.setFailCount(0L);
        result.setErrorRuleCount(0L); result.setFailedRuleCount(0L);
        return result;
    }
    @Test void successfulExecutionWithBadRuleDoesNotPass() {
        var result=result(); result.setFailedRuleCount(1L);
        assertEquals("FAILED",QualityGateService.assess(1L,result).status());
    }
    @Test void successfulExecutionWithRuleErrorDoesNotPass() {
        var result=result(); result.setErrorRuleCount(1L);
        assertEquals("FAILED",QualityGateService.assess(1L,result).status());
    }
    @Test void emptyOrIncompleteResultsDoNotPass() {
        var result=result(); result.setTotalCount(0L);
        assertEquals("FAILED",QualityGateService.assess(1L,result).status());
        result=result(); result.setErrorRuleCount(null);
        assertEquals("FAILED",QualityGateService.assess(1L,result).status());
    }
    @Test void wrongInstanceIsNeverAccepted() {
        assertEquals("UNKNOWN",QualityGateService.assess(99L,result()).status());
    }
    @Test void pendingIsIndependentOfTaskSuccess() {
        var result=result(); result.setInstanceStatus("RUNNING");
        assertEquals("PENDING",QualityGateService.assess(1L,result).status());
        result.setInstanceStatus("UNKNOWN");
        assertEquals("UNKNOWN",QualityGateService.assess(1L,result).status());
    }
    @Test void completePassingResultIsAccepted() {
        assertEquals("PASSED",QualityGateService.assess(1L,result()).status());
    }
    @Test void optionalGateDoesNotPretendToPassAndConfiguredGateNeedsIdentity() {
        var service=new QualityGateService();
        assertDoesNotThrow(() -> service.validate(null,1L));
        assertThrows(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                () -> service.validate(5L,null));
        assertEquals("NOT_CONFIGURED",service.evaluate("run",null,1L).status());
    }
}

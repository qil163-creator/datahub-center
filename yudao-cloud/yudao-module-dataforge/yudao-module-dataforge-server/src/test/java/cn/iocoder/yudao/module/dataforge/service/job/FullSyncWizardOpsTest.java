package cn.iocoder.yudao.module.dataforge.service.job;

import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerClient;
import cn.iocoder.yudao.framework.dolphinscheduler.api.WorkflowInstanceApi;
import cn.iocoder.yudao.module.dataforge.controller.admin.operations.vo.OpsInstanceModels.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.operations.OpsInstanceMapper;
import cn.iocoder.yudao.module.dataforge.service.operations.OpsInstanceService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 向导提交与真实执行结果严格分开；仅接受已存身份对应的调度器状态。 */
class FullSyncWizardOpsTest {
    private OpsInstanceService service(String response,boolean unavailable) {
        var service=new OpsInstanceService();var mapper=mock(OpsInstanceMapper.class);var ds=mock(DolphinSchedulerClient.class);var api=mock(WorkflowInstanceApi.class);
        ReflectionTestUtils.setField(service,"mapper",mapper);ReflectionTestUtils.setField(service,"dolphinScheduler",ds);when(ds.workflowInstance()).thenReturn(api);
        var record=new InstanceResp();record.setId(1L);record.setOrigin("WIZARD");record.setKind("FULL_SYNC");record.setStatus("WAITING");when(mapper.selectInstance(any())).thenReturn(record);
        var target=new WizardTarget();target.setProjectCode(100L);target.setInstanceId(3);target.setWorkflowCode(10L);when(mapper.selectWizardTarget(1L)).thenReturn(target);
        if(unavailable)when(api.queryWorkflowInstanceById(100L,3)).thenThrow(new IllegalStateException("unavailable"));else when(api.queryWorkflowInstanceById(100L,3)).thenReturn(JSONUtil.parseObj(response));return service;
    }
    private IdentityReq identity(){var req=new IdentityReq();req.setId(1L);req.setOrigin("WIZARD");req.setKind("FULL_SYNC");return req;}
    @Test void acceptsOnlyActualMatchedInstanceState() {
        var record=service("{\"code\":0,\"data\":{\"id\":3,\"state\":\"SUCCESS\",\"startTime\":\"2026-11-01 02:00:00\",\"endTime\":\"2026-11-01 02:00:42\"}}",false).get(identity());assertEquals("SUCCESS",record.getStatus());assertEquals(42L,record.getDuration());
        assertEquals("UNKNOWN",service("{\"code\":0,\"data\":{\"id\":4,\"state\":\"SUCCESS\"}}",false).get(identity()).getStatus());
    }
    @Test void unreachableSchedulerCannotImplyWaitingOrSuccess() {assertEquals("UNKNOWN",service("{}",true).get(identity()).getStatus());}
}

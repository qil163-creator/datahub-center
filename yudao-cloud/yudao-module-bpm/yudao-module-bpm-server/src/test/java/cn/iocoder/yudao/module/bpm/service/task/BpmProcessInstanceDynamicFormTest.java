package cn.iocoder.yudao.module.bpm.service.task;

import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import cn.iocoder.yudao.module.bpm.controller.admin.task.vo.instance.BpmApprovalDetailRespVO;
import cn.iocoder.yudao.module.bpm.controller.admin.task.vo.instance.BpmProcessInstanceCreateReqVO;
import cn.iocoder.yudao.module.bpm.dal.dataobject.definition.BpmProcessDefinitionInfoDO;
import cn.iocoder.yudao.module.bpm.enums.definition.BpmModelFormTypeEnum;
import cn.iocoder.yudao.module.bpm.service.definition.BpmProcessDefinitionService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.test.core.util.AssertUtils.assertServiceException;
import static cn.iocoder.yudao.module.bpm.enums.ErrorCodeConstants.PROCESS_INSTANCE_START_FORM_FIELD_REQUIRED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BpmProcessInstanceDynamicFormTest extends BaseMockitoUnitTest {

    @Spy
    @InjectMocks
    private BpmProcessInstanceServiceImpl service;

    @Mock
    private BpmProcessDefinitionService processDefinitionService;
    @Mock
    private RuntimeService runtimeService;
    @Mock
    private ProcessDefinition definition;
    @Mock(answer = Answers.RETURNS_SELF)
    private ProcessInstanceBuilder builder;
    @Mock
    private ProcessInstance instance;

    private BpmProcessDefinitionInfoDO definitionInfo;

    @BeforeEach
    void setUp() {
        definitionInfo = new BpmProcessDefinitionInfoDO();
        definitionInfo.setFormType(BpmModelFormTypeEnum.NORMAL.getType());
        when(processDefinitionService.getProcessDefinition("definition-1")).thenReturn(definition);
        when(definition.getId()).thenReturn("definition-1");
        when(processDefinitionService.getProcessDefinitionInfo("definition-1")).thenReturn(definitionInfo);
    }

    @Test
    void rejectsBlankRequiredFieldBeforeStartingProcess() {
        definitionInfo.setFormFields(List.of("{\"field\":\"reason\",\"title\":\"Reason\",\"validate\":[\"required\"]}"));

        assertServiceException(() -> service.createProcessInstance(1L, request(Map.of("reason", " \t"))),
                PROCESS_INSTANCE_START_FORM_FIELD_REQUIRED, "Reason");

        verifyNoInteractions(runtimeService);
    }

    @Test
    void rejectsMissingRequiredKeyWhenVariablesAreAbsent() {
        definitionInfo.setFormFields(List.of("{\"key\":\"reason\",\"validate\":{\"required\":true}}"));

        assertServiceException(() -> service.createProcessInstance(1L, request(null)),
                PROCESS_INSTANCE_START_FORM_FIELD_REQUIRED, "reason");

        verifyNoInteractions(runtimeService);
    }

    @Test
    void startsProcessWithZeroAndFalseRequiredValues() {
        definitionInfo.setFormFields(List.of(
                "{\"field\":\"amount\",\"validate\":[\"required\"]}",
                "{\"key\":\"confirmed\",\"validate\":{\"required\":true}}"));
        prepareProcessStart();

        String processId = service.createProcessInstance(1L, request(Map.of("amount", 0, "confirmed", false)));

        assertEquals("process-1", processId);
        verify(builder).start();
    }

    @Test
    void keepsCustomBusinessFormValidationWithItsOwner() {
        definitionInfo.setFormType(BpmModelFormTypeEnum.CUSTOM.getType());
        definitionInfo.setFormFields(List.of("{\"field\":\"reason\",\"validate\":[\"required\"]}"));
        prepareProcessStart();

        assertEquals("process-1", service.createProcessInstance(1L, request(null)));
        verify(builder).start();
    }

    private void prepareProcessStart() {
        when(processDefinitionService.canUserStartProcessDefinition(definitionInfo, 1L)).thenReturn(true);
        doReturn(new BpmApprovalDetailRespVO()).when(service).getApprovalDetail(eq(1L), any());
        when(runtimeService.createProcessInstanceBuilder()).thenReturn(builder);
        when(builder.start()).thenReturn(instance);
        when(instance.getId()).thenReturn("process-1");
    }

    private BpmProcessInstanceCreateReqVO request(Map<String, Object> variables) {
        BpmProcessInstanceCreateReqVO request = new BpmProcessInstanceCreateReqVO();
        request.setProcessDefinitionId("definition-1");
        request.setVariables(variables == null ? null : new HashMap<>(variables));
        return request;
    }

}

package cn.iocoder.yudao.module.bpm.service.message;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.module.bpm.service.message.dto.BpmMessageSendWhenProcessInstanceApproveReqDTO;
import cn.iocoder.yudao.module.system.api.sms.SmsSendApi;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.SMS_SEND_MOBILE_NOT_EXISTS;
import static cn.iocoder.yudao.module.system.enums.ErrorCodeConstants.SMS_SEND_TEMPLATE_NOT_EXISTS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BpmMessageServiceImplTest {

    @Test
    void missingOptionalMobileDoesNotPreventApproval() {
        SmsSendApi sms = mock(SmsSendApi.class);
        BpmMessageServiceImpl service = new BpmMessageServiceImpl();
        ReflectionTestUtils.setField(service, "smsSendApi", sms);
        ReflectionTestUtils.setField(service, "webProperties",
                new WebProperties().setAdminUi(new WebProperties.Ui().setUrl("http://localhost")));
        var message = new BpmMessageSendWhenProcessInstanceApproveReqDTO()
                .setStartUserId(1L).setProcessInstanceName("测试流程").setProcessInstanceId("test");
        when(sms.sendSingleSmsToAdmin(any())).thenReturn(CommonResult.error(SMS_SEND_MOBILE_NOT_EXISTS));
        assertDoesNotThrow(() -> service.sendMessageWhenProcessInstanceApprove(message));
        when(sms.sendSingleSmsToAdmin(any())).thenReturn(CommonResult.error(SMS_SEND_TEMPLATE_NOT_EXISTS));
        assertThrows(ServiceException.class, () -> service.sendMessageWhenProcessInstanceApprove(message));
    }
}

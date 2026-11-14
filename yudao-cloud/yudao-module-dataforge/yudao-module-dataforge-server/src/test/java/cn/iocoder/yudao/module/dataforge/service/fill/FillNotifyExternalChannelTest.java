package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillNotifyRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillPlanDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillNotifyRecordMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillPlanMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillNotifyChannelEnum;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillNotifyTypeEnum;
import cn.iocoder.yudao.module.system.api.mail.MailSendApi;
import cn.iocoder.yudao.module.system.api.mail.dto.MailSendSingleToUserReqDTO;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import cn.iocoder.yudao.module.system.api.sms.SmsSendApi;
import cn.iocoder.yudao.module.system.api.sms.dto.send.SmsSendSingleToUserReqDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 短信、邮件附加渠道：按计划配置发送，只用于需要填报人处理的通知
 */
class FillNotifyExternalChannelTest {

    private final FillNotifyRecordMapper notifyRecordMapper = mock(FillNotifyRecordMapper.class);
    private final NotifyMessageSendApi notifyMessageSendApi = mock(NotifyMessageSendApi.class);
    private final SmsSendApi smsSendApi = mock(SmsSendApi.class);
    private final MailSendApi mailSendApi = mock(MailSendApi.class);
    private final FillPlanMapper planMapper = mock(FillPlanMapper.class);

    private final FillTaskDO task = FillTaskDO.builder().id(9L).planId(1L).name("月报").periodKey("2026-10").build();
    private final FillWorkItemDO item = FillWorkItemDO.builder().id(11L).orgName("财务处")
            .deadlineTime(LocalDateTime.of(2026, 11, 20, 18, 0)).build();

    private FillNotifyServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FillNotifyServiceImpl();
        ReflectionTestUtils.setField(service, "notifyRecordMapper", notifyRecordMapper);
        ReflectionTestUtils.setField(service, "notifyMessageSendApi", notifyMessageSendApi);
        ReflectionTestUtils.setField(service, "smsSendApi", smsSendApi);
        ReflectionTestUtils.setField(service, "mailSendApi", mailSendApi);
        ReflectionTestUtils.setField(service, "planMapper", planMapper);
        ReflectionTestUtils.setField(service, "transactionManager", mock(PlatformTransactionManager.class));
        long[] ids = {500L};
        doAnswer(invocation -> {
            invocation.<FillNotifyRecordDO>getArgument(0).setId(ids[0]++);
            return 1;
        }).when(notifyRecordMapper).insert(any(FillNotifyRecordDO.class));
        when(notifyMessageSendApi.sendSingleMessageToAdmin(any())).thenReturn(CommonResult.success(1L));
        when(smsSendApi.sendSingleSmsToAdmin(any())).thenReturn(CommonResult.success(2L));
        when(mailSendApi.sendSingleMailToAdmin(any())).thenReturn(CommonResult.success(3L));
        when(planMapper.selectById(1L)).thenReturn(FillPlanDO.builder().id(1L)
                .remindConfig("{\"channels\":[\"SMS\",\"MAIL\",\"WECHAT\"]}").build());
    }

    @Test
    void actionRequiredNoticeAlsoGoesBySmsAndMailWithReadableNotice() {
        service.sendToUsers(FillNotifyTypeEnum.URGE, task, item, List.of(88L), Map.of());

        ArgumentCaptor<FillNotifyRecordDO> records = ArgumentCaptor.forClass(FillNotifyRecordDO.class);
        verify(notifyRecordMapper, times(3)).insert(records.capture());
        assertThat(records.getAllValues()).extracting(FillNotifyRecordDO::getChannel).containsExactly(
                FillNotifyChannelEnum.NOTIFY_MESSAGE.getChannel(), FillNotifyChannelEnum.SMS.getChannel(),
                FillNotifyChannelEnum.MAIL.getChannel());

        ArgumentCaptor<SmsSendSingleToUserReqDTO> sms = ArgumentCaptor.forClass(SmsSendSingleToUserReqDTO.class);
        verify(smsSendApi).sendSingleSmsToAdmin(sms.capture());
        assertThat(sms.getValue().getTemplateCode()).isEqualTo("fill_sms_notice");
        assertThat(sms.getValue().getUserId()).isEqualTo(88L);
        assertThat(sms.getValue().getTemplateParams())
                .containsEntry("orgName", "财务处")
                .containsEntry("notice", "发起者催办，请尽快填报并提交，截止时间 2026-11-20 18:00");
        ArgumentCaptor<MailSendSingleToUserReqDTO> mail = ArgumentCaptor.forClass(MailSendSingleToUserReqDTO.class);
        verify(mailSendApi).sendSingleMailToAdmin(mail.capture());
        assertThat(mail.getValue().getTemplateCode()).isEqualTo("fill_mail_notice");
    }

    @Test
    void informationalNoticeStaysInApp() {
        service.sendToUsers(FillNotifyTypeEnum.APPROVED, task, item, List.of(88L), Map.of());

        verify(notifyRecordMapper, times(1)).insert(any(FillNotifyRecordDO.class));
        verify(smsSendApi, never()).sendSingleSmsToAdmin(any());
        verify(mailSendApi, never()).sendSingleMailToAdmin(any());
    }

    @Test
    void remindConfigIgnoresUnknownChannels() {
        assertThat(FillRemindConfig.parse("{\"channels\":[\" SMS \",\"SMS\",\"FAX\",null]}").resolveChannels())
                .containsExactly("SMS");
        assertThat(FillRemindConfig.parse(null).resolveChannels()).isEmpty();
    }

}

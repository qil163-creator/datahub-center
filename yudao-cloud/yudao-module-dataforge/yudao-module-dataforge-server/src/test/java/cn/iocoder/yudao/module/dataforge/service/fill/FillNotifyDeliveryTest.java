package cn.iocoder.yudao.module.dataforge.service.fill;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillNotifyRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillWorkItemDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillNotifyRecordMapper;
import cn.iocoder.yudao.module.dataforge.enums.fill.FillNotifyTypeEnum;
import cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi;
import cn.iocoder.yudao.module.system.api.notify.dto.NotifySendSingleToUserReqDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 填报通知的发送时机、失败记录与重试测试
 */
class FillNotifyDeliveryTest {

    private final FillNotifyRecordMapper notifyRecordMapper = mock(FillNotifyRecordMapper.class);
    private final NotifyMessageSendApi notifyMessageSendApi = mock(NotifyMessageSendApi.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    private FillNotifyServiceImpl service;

    private final FillTaskDO task = FillTaskDO.builder().id(9L).name("月报").periodKey("2026-10").build();
    private final FillWorkItemDO item = FillWorkItemDO.builder().id(11L).orgName("财务处")
            .deadlineTime(LocalDateTime.of(2026, 11, 20, 18, 0)).build();

    @BeforeEach
    void setUp() {
        service = new FillNotifyServiceImpl();
        ReflectionTestUtils.setField(service, "notifyRecordMapper", notifyRecordMapper);
        ReflectionTestUtils.setField(service, "notifyMessageSendApi", notifyMessageSendApi);
        ReflectionTestUtils.setField(service, "transactionManager", transactionManager);
        doAnswer(invocation -> {
            invocation.<FillNotifyRecordDO>getArgument(0).setId(500L);
            return 1;
        }).when(notifyRecordMapper).insert(any(FillNotifyRecordDO.class));
        when(notifyMessageSendApi.sendSingleMessageToAdmin(any())).thenReturn(CommonResult.success(1L));
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private FillNotifyRecordDO lastStatusUpdate() {
        ArgumentCaptor<FillNotifyRecordDO> captor = ArgumentCaptor.forClass(FillNotifyRecordDO.class);
        verify(notifyRecordMapper).updateById(captor.capture());
        return captor.getValue();
    }

    @Test
    void sendsImmediatelyOutsideTransactionWithParamsSnapshotAndIdempotencyKey() {
        service.sendToUsers(FillNotifyTypeEnum.REJECTED, task, item, List.of(88L, 88L), Map.of("reason", "口径不符"));

        ArgumentCaptor<FillNotifyRecordDO> record = ArgumentCaptor.forClass(FillNotifyRecordDO.class);
        verify(notifyRecordMapper).insert(record.capture());
        assertEquals(0, record.getValue().getRetryCount());
        assertEquals(true, record.getValue().getTemplateParams().contains("\"reason\":\"口径不符\""));
        ArgumentCaptor<NotifySendSingleToUserReqDTO> request = ArgumentCaptor.forClass(NotifySendSingleToUserReqDTO.class);
        verify(notifyMessageSendApi).sendSingleMessageToAdmin(request.capture());
        assertEquals("fill_rejected", request.getValue().getTemplateCode());
        assertEquals("dataforge:fill-notify:500", request.getValue().getIdempotencyKey());
        assertEquals("财务处", request.getValue().getTemplateParams().get("orgName"));
        assertEquals(1, lastStatusUpdate().getSendStatus());
    }

    @Test
    void insideTransactionNothingIsSentUntilCommit() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);

        service.sendToUsers(FillNotifyTypeEnum.ASSIGNED, task, item, List.of(88L), null);

        // 事务未提交：不写通知记录，也不调用站内信；事务回滚时这条通知就不会发出
        verify(notifyRecordMapper, never()).insert(any(FillNotifyRecordDO.class));
        verify(notifyMessageSendApi, never()).sendSingleMessageToAdmin(any());

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        verify(notifyMessageSendApi).sendSingleMessageToAdmin(any());
        assertEquals(1, lastStatusUpdate().getSendStatus());
    }

    @Test
    void errorResultFromNotifyApiIsRecordedAsFailure() {
        // 站内信接口以错误码返回（如模板缺失）而不是抛异常：也必须记为失败，等待重试
        when(notifyMessageSendApi.sendSingleMessageToAdmin(any()))
                .thenReturn(CommonResult.error(1_002_006_001, "站内信模板不存在"));

        service.sendToUsers(FillNotifyTypeEnum.URGE, task, item, List.of(88L), null);

        assertEquals(2, lastStatusUpdate().getSendStatus());
    }

    @Test
    void failedNotificationIsResentWithItsOriginalParams() {
        LocalDateTime now = LocalDateTime.now();
        when(notifyRecordMapper.selectListToRetry(any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                FillNotifyRecordDO.builder().id(700L).receiverUserId(88L).templateCode("fill_rejected")
                        .templateParams("{\"taskName\":\"月报\",\"reason\":\"口径不符\"}").retryCount(1).build(),
                FillNotifyRecordDO.builder().id(701L).receiverUserId(89L).templateCode("fill_urge")
                        .templateParams("{}").retryCount(0).build()));
        when(notifyRecordMapper.claimForRetry(700L, 1)).thenReturn(1);
        // 701 已被另一个实例取走
        when(notifyRecordMapper.claimForRetry(701L, 0)).thenReturn(0);

        assertEquals(1, service.retryFailed(now));

        ArgumentCaptor<NotifySendSingleToUserReqDTO> request = ArgumentCaptor.forClass(NotifySendSingleToUserReqDTO.class);
        verify(notifyMessageSendApi).sendSingleMessageToAdmin(request.capture());
        assertEquals(88L, request.getValue().getUserId());
        assertEquals("口径不符", request.getValue().getTemplateParams().get("reason"));
        assertEquals("dataforge:fill-notify:700", request.getValue().getIdempotencyKey());
        assertEquals(1, lastStatusUpdate().getSendStatus());
    }

}

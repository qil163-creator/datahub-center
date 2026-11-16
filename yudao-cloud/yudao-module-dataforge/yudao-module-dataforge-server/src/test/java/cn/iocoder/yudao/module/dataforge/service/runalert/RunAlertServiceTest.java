package cn.iocoder.yudao.module.dataforge.service.runalert;

import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.runalert.RunAlertCandidate;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.runalert.RunAlertDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.runalert.RunAlertMapper;
import cn.iocoder.yudao.module.dataforge.framework.rpc.RunAlertSystemClients;
import cn.iocoder.yudao.module.system.api.notify.dto.NotifySendSingleToUserReqDTO;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 任务运行失败通知：接收人选择、通知行构造与发送状态机。
 */
class RunAlertServiceTest {

    private final RunAlertMapper alerts = mock(RunAlertMapper.class);
    private final AdminUserApi adminUserApi = mock(AdminUserApi.class);
    private final RunAlertSystemClients.NotifyClient notifyClient = mock(RunAlertSystemClients.NotifyClient.class);
    private final RunAlertSystemClients.SmsClient smsClient = mock(RunAlertSystemClients.SmsClient.class);
    private final RunAlertSystemClients.MailClient mailClient = mock(RunAlertSystemClients.MailClient.class);
    private final RunAlertProperties properties = new RunAlertProperties();

    private RunAlertService service;

    @BeforeEach
    void setUp() {
        service = new RunAlertService();
        ReflectionTestUtils.setField(service, "alerts", alerts);
        ReflectionTestUtils.setField(service, "properties", properties);
        ReflectionTestUtils.setField(service, "adminUserApi", adminUserApi);
        ReflectionTestUtils.setField(service, "notifyClient", notifyClient);
        ReflectionTestUtils.setField(service, "smsClient", smsClient);
        ReflectionTestUtils.setField(service, "mailClient", mailClient);
        ReflectionTestUtils.setField(service, "transactionManager", mock(PlatformTransactionManager.class));
    }

    private static RunAlertCandidate run(String kind, Long owner, Long release, Long execution, Long creator) {
        RunAlertCandidate run = new RunAlertCandidate();
        run.setSourceKind(kind);
        run.setRunId(88L);
        run.setTaskId(7L);
        run.setTaskName("订单日汇总");
        run.setRunName("订单日汇总-88");
        run.setOccurredTime(LocalDateTime.of(2026, 11, 15, 9, 30, 5));
        run.setOwnerId(owner);
        run.setReleaseUserId(release);
        run.setExecutionUserId(execution);
        run.setCreatorId(creator);
        return run;
    }

    private static AdminUserRespDTO user(long id, CommonStatusEnum status, String mobile, String email) {
        AdminUserRespDTO user = new AdminUserRespDTO();
        user.setId(id);
        user.setStatus(status.getStatus());
        user.setMobile(mobile);
        user.setEmail(email);
        return user;
    }

    private static RunAlertDO claimed(long id, int channel) {
        return RunAlertDO.builder().id(id).channel(channel).receiverUserId(5L).status(RunAlertDO.STATUS_SENDING)
                .templateParams("{\"kindName\":\"数据开发\",\"taskName\":\"t\",\"runName\":\"r\",\"failTime\":\"-\"}")
                .build();
    }

    @Test
    void candidateReceiversKeepPriorityAndDropDuplicatesAndInvalidIds() {
        assertEquals(List.of(3L, 5L, 9L), RunAlertService.candidateReceivers(run("JOB", 3L, 5L, 3L, 9L)));
        assertEquals(List.of(4L), RunAlertService.candidateReceivers(run("JOB", null, 0L, -1L, 4L)));
        assertTrue(RunAlertService.candidateReceivers(run("JOB", null, null, null, null)).isEmpty());
    }

    @Test
    void buildAlertsOnlyNotifiesEnabledUsersAndAddsExtraChannelsWhenContactExists() {
        properties.setExtraChannels(Set.of("SMS", "MAIL"));
        Map<Long, AdminUserRespDTO> enabled = Map.of(
                3L, user(3, CommonStatusEnum.ENABLE, "13800000000", ""),
                9L, user(9, CommonStatusEnum.ENABLE, null, "ops@example.com"));
        List<RunAlertDO> rows = service.buildAlerts(run("DEVELOPMENT", 3L, 5L, null, 9L), enabled);

        assertEquals(4, rows.size());
        assertEquals(List.of(3L, 3L, 9L, 9L), rows.stream().map(RunAlertDO::getReceiverUserId).toList());
        assertEquals(List.of(1, 2, 1, 3), rows.stream().map(RunAlertDO::getChannel).toList());
        assertTrue(rows.stream().allMatch(row -> row.getStatus() == RunAlertDO.STATUS_PENDING));
        assertTrue(rows.stream().allMatch(row -> "FAILURE".equals(row.getEventType()) && row.getRunId() == 88L));
    }

    @Test
    void buildAlertsRegistersDeadLetterWhenNoEnabledReceiver() {
        List<RunAlertDO> rows = service.buildAlerts(run("DATA_ACCESS", 3L, null, null, null), Map.of());

        assertEquals(1, rows.size());
        assertEquals(RunAlertDO.NO_RECEIVER, rows.getFirst().getReceiverUserId());
        assertEquals(RunAlertDO.STATUS_DEAD, rows.getFirst().getStatus());
        assertEquals("NO_RECEIVER", rows.getFirst().getLastError());
    }

    @Test
    void buildAlertsCapsReceivers() {
        properties.setMaxReceivers(1);
        Map<Long, AdminUserRespDTO> enabled = Map.of(3L, user(3, CommonStatusEnum.ENABLE, null, null),
                5L, user(5, CommonStatusEnum.ENABLE, null, null));
        List<RunAlertDO> rows = service.buildAlerts(run("JOB", 3L, 5L, null, null), enabled);
        assertEquals(List.of(3L), rows.stream().map(RunAlertDO::getReceiverUserId).toList());
    }

    @Test
    void templateParamsNameIntegrationEtlRunsAndNeverCarryErrorDetails() {
        RunAlertCandidate etl = run("ETL", null, null, null, null);
        etl.setIntegrationName("客户主题集成");
        Map<String, Object> params = RunAlertService.templateParams(etl);

        assertEquals(Set.of("kindName", "taskName", "runName", "failTime"), params.keySet());
        assertEquals("数据集成", params.get("kindName"));
        assertEquals("客户主题集成", params.get("taskName"));
        assertEquals("2026-11-15 09:30:05", params.get("failTime"));
        assertEquals("可视化ETL", RunAlertService.templateParams(run("ETL", null, null, null, null)).get("kindName"));
    }

    @Test
    void registerSkipsDisabledUsersAndWritesEachRunInOneTransaction() {
        when(alerts.selectFailedRuns(any(), eq(200))).thenReturn(List.of(run("DEVELOPMENT", 3L, null, 4L, null)));
        when(adminUserApi.getUserList(any())).thenReturn(CommonResult.success(List.of(
                user(3, CommonStatusEnum.DISABLE, null, null), user(4, CommonStatusEnum.ENABLE, null, null))));
        when(alerts.insertIgnore(any())).thenReturn(1);

        assertEquals(1, service.registerFailedRuns(LocalDateTime.of(2026, 11, 15, 10, 0)));

        ArgumentCaptor<RunAlertDO> row = ArgumentCaptor.forClass(RunAlertDO.class);
        verify(alerts).insertIgnore(row.capture());
        assertEquals(4L, row.getValue().getReceiverUserId());
        verify(alerts).selectFailedRuns(eq(LocalDateTime.of(2026, 11, 14, 10, 0)), eq(200));
    }

    @Test
    void registerWritesNothingWhenUserLookupFails() {
        when(alerts.selectFailedRuns(any(), anyInt())).thenReturn(List.of(run("JOB", 3L, null, null, null)));
        when(adminUserApi.getUserList(any())).thenThrow(new IllegalStateException("system 不可用"));

        assertThrows(IllegalStateException.class, () -> service.registerFailedRuns(LocalDateTime.now()));
        verify(alerts, never()).insertIgnore(any());
    }

    @Test
    void deliverNotifyWithIdempotencyKeyAndRecordReceipt() {
        when(alerts.claim(eq(10L), anyString(), eq(120), eq(5))).thenReturn(1);
        when(alerts.selectById(10L)).thenReturn(claimed(10, RunAlertDO.CHANNEL_NOTIFY));
        when(notifyClient.sendSingleMessageToAdmin(any())).thenReturn(CommonResult.success(901L));

        assertEquals(RunAlertService.Outcome.DELIVERED, service.deliver(10L));

        ArgumentCaptor<NotifySendSingleToUserReqDTO> request = ArgumentCaptor.forClass(NotifySendSingleToUserReqDTO.class);
        verify(notifyClient).sendSingleMessageToAdmin(request.capture());
        assertEquals("dataforge:run-alert:10", request.getValue().getIdempotencyKey());
        assertEquals("dataforge_run_failed", request.getValue().getTemplateCode());
        assertEquals(5L, request.getValue().getUserId());
        verify(alerts).delivered(eq(10L), anyString(), eq(901L));
    }

    @Test
    void deliverSkipsWhenNotClaimedOrJustExpired() {
        when(alerts.expireUnconfirmed(11L)).thenReturn(1);
        assertEquals(RunAlertService.Outcome.SKIPPED, service.deliver(11L));
        when(alerts.claim(eq(12L), anyString(), anyInt(), anyInt())).thenReturn(0);
        assertEquals(RunAlertService.Outcome.SKIPPED, service.deliver(12L));
        verifyNoInteractions(notifyClient, smsClient, mailClient);
    }

    @Test
    void notifyUnconfirmedIsRetriedButSmsUnconfirmedBecomesUnknown() {
        when(alerts.claim(anyLong(), anyString(), anyInt(), anyInt())).thenReturn(1);
        when(alerts.selectById(20L)).thenReturn(claimed(20, RunAlertDO.CHANNEL_NOTIFY));
        when(alerts.selectById(21L)).thenReturn(claimed(21, RunAlertDO.CHANNEL_SMS));
        when(notifyClient.sendSingleMessageToAdmin(any())).thenThrow(new RuntimeException("read timeout"));
        when(smsClient.sendSingleSmsToAdmin(any())).thenThrow(new RuntimeException("read timeout"));

        assertEquals(RunAlertService.Outcome.NOT_DELIVERED, service.deliver(20L));
        assertEquals(RunAlertService.Outcome.NOT_DELIVERED, service.deliver(21L));

        verify(alerts).retryLater(eq(20L), anyString(), eq("DELIVERY_UNCONFIRMED"), eq(5), eq(60));
        verify(alerts).settle(eq(21L), anyString(), eq(RunAlertDO.STATUS_UNKNOWN), eq("DELIVERY_UNCONFIRMED"));
        verify(alerts, never()).retryLater(eq(21L), anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void disabledNotifyTemplateBecomesDeadAndRemoteRejectionIsRetried() {
        when(alerts.claim(anyLong(), anyString(), anyInt(), anyInt())).thenReturn(1);
        when(alerts.selectById(30L)).thenReturn(claimed(30, RunAlertDO.CHANNEL_NOTIFY));
        when(alerts.selectById(31L)).thenReturn(claimed(31, RunAlertDO.CHANNEL_MAIL));
        when(notifyClient.sendSingleMessageToAdmin(any())).thenReturn(CommonResult.success(null));
        when(mailClient.sendSingleMailToAdmin(any())).thenReturn(CommonResult.error(1_002_000_000, "模板不存在"));

        assertEquals(RunAlertService.Outcome.NOT_DELIVERED, service.deliver(30L));
        assertEquals(RunAlertService.Outcome.NOT_DELIVERED, service.deliver(31L));

        verify(alerts).settle(eq(30L), anyString(), eq(RunAlertDO.STATUS_DEAD), eq("TEMPLATE_DISABLED"));
        verify(alerts).retryLater(eq(31L), anyString(), eq("REMOTE_REJECTED"), eq(5), eq(60));
    }

    @Test
    void disposeValidatesInputStatusReceiverAndIsIdempotentPerCommand() {
        assertThrows(IllegalArgumentException.class, () -> service.dispose(1L, "RERUN", "x", "xxl-job:1:2"));
        assertThrows(IllegalArgumentException.class, () -> service.dispose(1L, "RETRY", " ", "xxl-job:1:2"));
        assertThrows(IllegalArgumentException.class, () -> service.dispose(1L, "RETRY", "x", "browser"));

        when(alerts.selectForUpdate(2L)).thenReturn(RunAlertDO.builder().id(2L).status(RunAlertDO.STATUS_SENT)
                .receiverUserId(5L).build());
        assertThrows(IllegalStateException.class, () -> service.dispose(2L, "RETRY", "重发", "xxl-job:1:2"));

        when(alerts.selectForUpdate(3L)).thenReturn(RunAlertDO.builder().id(3L).status(RunAlertDO.STATUS_DEAD)
                .receiverUserId(0L).build());
        assertThrows(IllegalStateException.class, () -> service.dispose(3L, "RETRY", "重发", "xxl-job:1:2"));

        when(alerts.selectForUpdate(4L)).thenReturn(RunAlertDO.builder().id(4L).status(RunAlertDO.STATUS_ACKNOWLEDGED)
                .receiverUserId(0L).dispositionCommand("xxl-job:1:2").dispositionAction("ACKNOWLEDGE")
                .dispositionReason("无负责人").build());
        service.dispose(4L, "ACKNOWLEDGE", "无负责人", "xxl-job:1:2");
        assertThrows(IllegalStateException.class, () -> service.dispose(4L, "ACKNOWLEDGE", "别的原因", "xxl-job:1:2"));

        when(alerts.selectForUpdate(5L)).thenReturn(RunAlertDO.builder().id(5L).status(RunAlertDO.STATUS_UNKNOWN)
                .receiverUserId(5L).build());
        when(alerts.dispose(5L, "RETRY", "确认未收到", "xxl-job:3:4")).thenReturn(1);
        service.dispose(5L, "RETRY", "确认未收到", "xxl-job:3:4");
        verify(alerts).dispose(5L, "RETRY", "确认未收到", "xxl-job:3:4");
        verify(alerts, never()).dispose(eq(4L), anyString(), anyString(), anyString());
    }

    @Test
    void disabledFeatureDoesNothingAndInvalidConfigIsRejected() {
        properties.setEnabled(false);
        RunAlertService.RoundResult result = service.runOnce();
        assertFalse(result.enabled());
        verifyNoInteractions(alerts);

        properties.setEnabled(true);
        properties.setRetentionDays(2);
        properties.setLookbackHours(72);
        assertThrows(IllegalStateException.class, service::runOnce);
        properties.setRetentionDays(30);
        properties.setExtraChannels(Set.of("DINGTALK"));
        assertThrows(IllegalStateException.class, service::runOnce);
    }

}

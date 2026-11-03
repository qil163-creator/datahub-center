package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerClient;
import cn.iocoder.yudao.framework.dolphinscheduler.api.LogApi;
import cn.iocoder.yudao.framework.dolphinscheduler.api.TaskInstanceApi;
import cn.iocoder.yudao.framework.dolphinscheduler.model.TaskLogPage;
import cn.iocoder.yudao.framework.dolphinscheduler.request.QueryTaskListPagingRequest;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.DevRecordController;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevTaskLogPageReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevRecordMapper;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DevTaskLogPageTest {
    private DevRecordServiceImpl service;
    private DevRecordMapper records;
    private DolphinSchedulerClient ds;
    private TaskInstanceApi tasks;
    private LogApi logs;
    private DevRecordDO record;

    @BeforeEach void setup() {
        service = new DevRecordServiceImpl();
        records = mock(DevRecordMapper.class);
        ds = mock(DolphinSchedulerClient.class);
        tasks = mock(TaskInstanceApi.class);
        logs = mock(LogApi.class);
        ReflectionTestUtils.setField(service, "recordMapper", records);
        ReflectionTestUtils.setField(service, "dsClient", ds);
        when(ds.taskInstance()).thenReturn(tasks);
        when(ds.log()).thenReturn(logs);
        record = DevRecordDO.builder().id(5L).runKey("run-5").status("RUNNING")
                .dsProjectCode(9L).dsInstanceId(77).build();
        var snapshot = new DevTaskSnapshot(); snapshot.setTask(cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskDO.builder().build());
        record.setSnapshotJson(snapshot.write());
        when(records.selectById(5L)).thenReturn(record);
        TenantContextHolder.setTenantId(1L);
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(7L), new MockHttpServletRequest());
        nodes(1, 9L, 77);
        when(logs.queryLogPage(88, 0, 200)).thenReturn(new TaskLogPage("started", 1, 1, true, false));
    }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    private DevTaskLogPageReqVO request() {
        var req = new DevTaskLogPageReqVO();
        req.setId(5L);
        return req;
    }

    private void nodes(int total, long project, int workflow) {
        var node = new JSONObject().set("id", 88).set("projectCode", project).set("workflowInstanceId", workflow);
        when(tasks.queryTaskListPaging(eq(9L), any())).thenReturn(new JSONObject().set("code", 0)
                .set("data", new JSONObject().set("total", total).set("totalList", new JSONArray().put(node))));
    }

    @Test void derivesTaskFromTheAuthorizedRecordInsteadOfAClientTaskId() {
        var page = service.getTaskLog(request());
        assertTrue(page.getAvailable());
        assertEquals("DOLPHINSCHEDULER", page.getSource());
        assertEquals(1, page.getNextSkipLineNum());
        var capture = ArgumentCaptor.forClass(QueryTaskListPagingRequest.class);
        verify(tasks).queryTaskListPaging(eq(9L), capture.capture());
        assertEquals(77, capture.getValue().getWorkflowInstanceId());
        assertEquals("BATCH", capture.getValue().getTaskExecuteType());
        assertEquals(2, capture.getValue().getPageSize());
        verify(logs).queryLogPage(88, 0, 200);
    }

    @Test void nativeOperatorCanReadFixedDiagnosticsButNotPluginRowsAndWithoutPermissionCannotReadAnyNativeDetail() {
        var support = new DevNativeRuntimeSupport();
        var access = mock(cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService.class);
        ReflectionTestUtils.setField(support, "executionAccessService", access);
        ReflectionTestUtils.setField(service, "nativeRuntimeSupport", support);
        var snapshot = new DevTaskSnapshot();
        snapshot.setTask(cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevTaskDO.builder()
                .taskType("FLINK_SQL").programConfigJson("{}").content("SELECT secret FROM source").build());
        snapshot.setNativeProjectCode(9L); record.setSnapshotJson(snapshot.write());
        record.setStatus("UNKNOWN"); record.setSubmissionStage("TRIGGER_SENT"); record.setCancelRequested(true);
        record.setLog("private-platform-payload"); record.setLastError("private-engine-diagnostic");
        var detail = service.getRecord(5L);
        assertTrue(detail.getLog().contains("UNKNOWN")); assertTrue(detail.getLog().contains("已请求取消"));
        assertFalse(detail.getLog().contains("private")); assertFalse(detail.getLastError().contains("private"));
        when(logs.queryLogPage(88, 0, 200)).thenReturn(new TaskLogPage(
                "2026-10-11 12:00:00 INFO - secret-row-value\n2026-10-11 12:00:01 ERROR - private-script-content", 2, 2, true, false));
        var page = service.getTaskLog(request());
        assertTrue(page.getMessage().contains("INFO")); assertTrue(page.getMessage().contains("ERROR"));
        assertFalse(page.getMessage().contains("secret-row")); assertFalse(page.getMessage().contains("private-script"));
        clearInvocations(tasks, logs);
        doThrow(new ServiceException(1000, "denied")).when(access).requireUserPermission(7L,
                cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService.PERMISSION_DEV_TASK_NATIVE);
        assertThrows(ServiceException.class, () -> service.getRecord(5L));
        assertThrows(ServiceException.class, () -> service.getTaskLog(request()));
        assertThrows(ServiceException.class, () -> service.downloadTaskLog(5L));
        verifyNoInteractions(tasks, logs);
    }

    @Test void missingIdentityCannotReadDs() {
        SecurityContextHolder.clearContext();
        assertThrows(ServiceException.class, () -> service.getTaskLog(request()));
        verifyNoInteractions(tasks, logs);
    }

    @Test void absentPlatformRecordAndInvalidBoundsCannotReadDs() {
        when(records.selectById(5L)).thenReturn(null);
        assertThrows(ServiceException.class, () -> service.getTaskLog(request()));
        var req = request(); req.setLimit(1001);
        assertThrows(ServiceException.class, () -> service.getTaskLog(req));
        verifyNoInteractions(tasks, logs);
    }

    @Test void ambiguousOrForeignNodesNeverReachTheLogEndpoint() {
        nodes(2, 9L, 77);
        assertFalse(service.getTaskLog(request()).getAvailable());
        nodes(1, 10L, 77);
        assertFalse(service.getTaskLog(request()).getAvailable());
        nodes(1, 9L, 99);
        assertFalse(service.getTaskLog(request()).getAvailable());
        verifyNoInteractions(logs);
    }

    @Test void legacyAndNotYetAssociatedRunsDoNotExposeLogs() {
        record.setRunKey(null);
        assertFalse(service.getTaskLog(request()).getAvailable());
        record.setRunKey("run-5"); record.setDsInstanceId(null);
        assertFalse(service.getTaskLog(request()).getAvailable());
        verifyNoInteractions(tasks, logs);
    }

    @Test void hidesCredentialBearingLinesAndKeepsCursorProgress() {
        String raw = "2026-10-11 12:00:00 INFO - starting\npassword=private-password\n{\"token\":\"private-token\"}\n"
                + "jdbc:postgresql://internal/secret-db\nAuthorization: Bearer private-bearer\n2026-10-11 12:00:01 INFO - completed";
        when(logs.queryLogPage(88, 0, 200)).thenReturn(new TaskLogPage(raw, 6, 6, true, false));
        var page = service.getTaskLog(request());
        assertTrue(page.getAvailable());
        assertEquals(6, page.getReadLines());
        assertEquals(6, page.getNextSkipLineNum());
        assertTrue(page.getMessage().contains("starting"));
        assertTrue(page.getMessage().contains("completed"));
        assertFalse(page.getMessage().contains("private-password"));
        assertFalse(page.getMessage().contains("private-token"));
        assertFalse(page.getMessage().contains("private-bearer"));
        assertFalse(page.getMessage().contains("internal"));
    }

    @Test void emptyActivePagesContinuePollingAndTerminalEmptyPagesStop() {
        when(logs.queryLogPage(88, 0, 200)).thenReturn(new TaskLogPage("", 0, 0, false, false));
        assertTrue(service.getTaskLog(request()).getMayHaveMore());
        record.setStatus("SUCCESS");
        assertFalse(service.getTaskLog(request()).getMayHaveMore());
    }

    @Test void remoteFailuresPreserveTheCursorAndNeverExposeExceptionBodies() {
        when(logs.queryLogPage(88, 0, 200)).thenThrow(new RuntimeException("password=private-exception"));
        var page = service.getTaskLog(request());
        assertFalse(page.getAvailable());
        assertEquals(0, page.getNextSkipLineNum());
        assertFalse(page.toString().contains("private-exception"));
    }

    @Test void controllerRetainsTheExistingQueryPermission() throws Exception {
        var method = DevRecordController.class.getMethod("getTaskLog", DevTaskLogPageReqVO.class);
        assertEquals("@ss.hasPermission('dataforge:dev-task:query')", method.getAnnotation(PreAuthorize.class).value());
    }

    @Test void executionResultAndParameterEchoesDoNotBypassFieldMasking() {
        String raw = "2026-10-11 12:00:00 INFO - starting\nINFO row 0 : {\"value\":\"private-row-value\"}\n"
                + "Sql Params are replaced sql , parameters: private-binding(VARCHAR)\n"
                + "prepare statement replace sql : SELECT ?, sql parameters : private-prepared\n2026-10-11 12:00:01 INFO - completed";
        when(logs.queryLogPage(88, 0, 200)).thenReturn(new TaskLogPage(raw, 5, 5, true, false));
        var result = service.getTaskLog(request());
        assertEquals(5, result.getNextSkipLineNum());
        assertTrue(result.getMessage().contains("starting")); assertTrue(result.getMessage().contains("completed"));
        assertFalse(result.getMessage().contains("private-row-value"));
        assertFalse(result.getMessage().contains("private-binding"));
        assertFalse(result.getMessage().contains("private-prepared"));
    }

    @Test void pageLeadingContinuationAndForgedHeadersInsideMultilineValuesAreHidden() {
        var config = new cn.iocoder.yudao.module.dataforge.model.dev.DevExecutionConfig();
        var parameter = new cn.iocoder.yudao.module.dataforge.model.dev.DevExecutionConfig.LocalParam();
        parameter.setProp("value"); parameter.setValue("first\n2026-10-11 12:00:00 INFO - private-forged-value");
        config.setLocalParams(java.util.List.of(parameter));
        var snapshot = DevTaskSnapshot.read(record.getSnapshotJson());
        snapshot.getTask().setExecutionConfigJson(cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(config));
        record.setSnapshotJson(snapshot.write());
        var request = request(); request.setSkipLineNum(200);
        when(logs.queryLogPage(88, 200, 200)).thenReturn(new TaskLogPage(
                "private-continuation\n2026-10-11 12:00:00 INFO - private-forged-value\n2026-10-11 12:00:01 ERROR - private-error-body", 3, 203, true, false));
        var result = service.getTaskLog(request);
        assertEquals(203, result.getNextSkipLineNum()); assertEquals(3, result.getReadLines());
        assertFalse(result.getMessage().contains("private-continuation"));
        assertFalse(result.getMessage().contains("private-forged-value"));
        assertFalse(result.getMessage().contains("private-error-body"));
        assertTrue(result.getMessage().contains("INFO")); assertTrue(result.getMessage().contains("ERROR"));
    }

    @Test void forgedHeadersInsideSqlLiteralsAreHiddenWithoutInventingALexicalParser() {
        var snapshot = DevTaskSnapshot.read(record.getSnapshotJson());
        snapshot.getTask().setContent("INSERT INTO samples(value) VALUES('first\n2026-10-11 12:00:00 INFO - private-literal')");
        record.setSnapshotJson(snapshot.write());
        var request = request(); request.setSkipLineNum(300);
        when(logs.queryLogPage(88, 300, 200)).thenReturn(new TaskLogPage(
                "private-literal-continuation\n2026-10-11 12:00:00 INFO - private-literal", 2, 302, true, false));
        var result = service.getTaskLog(request);
        assertEquals(302, result.getNextSkipLineNum()); assertEquals(2, result.getReadLines());
        assertFalse(result.getMessage().contains("private-literal"));
        assertTrue(result.getMessage().contains("2026-10-11 12:00:00 INFO"));
    }

    @Test void regularMultilineSqlKeepsOperationalTitlesAndHidesPlainContinuations() {
        var snapshot = DevTaskSnapshot.read(record.getSnapshotJson());
        snapshot.getTask().setContent("SELECT id\nFROM samples\nWHERE id > 0");
        record.setSnapshotJson(snapshot.write());
        when(logs.queryLogPage(88, 0, 200)).thenReturn(new TaskLogPage(
                "2026-10-11 12:00:00 INFO - starting\nprivate-stack-continuation\n2026-10-11 12:00:01 WARN - retrying", 3, 3, true, false));
        var result = service.getTaskLog(request());
        assertTrue(result.getMessage().contains("starting")); assertTrue(result.getMessage().contains("retrying"));
        assertFalse(result.getMessage().contains("private-stack-continuation"));
        assertEquals(3, result.getNextSkipLineNum());
    }

    @Test void unicodeLineSeparatorCannotForgeAHeaderInAParameterEcho() {
        var config = new cn.iocoder.yudao.module.dataforge.model.dev.DevExecutionConfig();
        var parameter = new cn.iocoder.yudao.module.dataforge.model.dev.DevExecutionConfig.LocalParam();
        parameter.setProp("value"); parameter.setValue("first\u20282026-10-11 12:00:00 INFO - private-unicode-value");
        config.setLocalParams(java.util.List.of(parameter));
        var snapshot = DevTaskSnapshot.read(record.getSnapshotJson());
        snapshot.getTask().setExecutionConfigJson(cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(config));
        record.setSnapshotJson(snapshot.write());
        when(logs.queryLogPage(88, 0, 200)).thenReturn(new TaskLogPage(
                "2026-10-11 12:00:00 INFO - Sql Params are first\u20282026-10-11 12:00:00 INFO - private-unicode-value", 1, 1, true, false));
        var result = service.getTaskLog(request());
        assertFalse(result.getMessage().contains("private-unicode-value"));
        assertTrue(result.getMessage().contains("INFO")); assertEquals(1, result.getNextSkipLineNum());
    }
}

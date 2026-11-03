package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.task.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.*;
import cn.iocoder.yudao.module.dataforge.service.access.AccessDatabaseResolver;
import cn.iocoder.yudao.module.dataforge.service.quality.QualityGateService;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.*;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DevInteractiveIdentityTest {
    private DevTaskServiceImpl service;
    private DevTaskMapper tasks;
    private DevTaskVersionMapper versions;
    private DevScheduleMapper schedules;
    private DevDsSubmitter submitter;
    private ExecutionAccessService access;
    private TransactionTemplate transaction;
    private DevTaskDO task;

    @BeforeEach void setup() {
        var configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "identity-test"), DevTaskDO.class);
        service = new DevTaskServiceImpl(); tasks = mock(DevTaskMapper.class); versions = mock(DevTaskVersionMapper.class);
        schedules = mock(DevScheduleMapper.class); submitter = mock(DevDsSubmitter.class);
        access = mock(ExecutionAccessService.class); transaction = mock(TransactionTemplate.class);
        ReflectionTestUtils.setField(service, "taskMapper", tasks); ReflectionTestUtils.setField(service, "versionMapper", versions);
        ReflectionTestUtils.setField(service, "scheduleMapper", schedules); ReflectionTestUtils.setField(service, "dsSubmitter", submitter);
        ReflectionTestUtils.setField(service, "executionAccessService", access); ReflectionTestUtils.setField(service, "transactionTemplate", transaction);
        ReflectionTestUtils.setField(service, "dirService", mock(DevDirService.class));
        ReflectionTestUtils.setField(service, "qualityGateService", mock(QualityGateService.class));
        ReflectionTestUtils.setField(service, "scheduleCoordinator", mock(DevScheduleCoordinator.class));
        var nativeSupport = new DevNativeRuntimeSupport();
        ReflectionTestUtils.setField(nativeSupport, "properties", new cn.iocoder.yudao.module.dataforge.framework.dev.DevNativeRuntimeProperties());
        ReflectionTestUtils.setField(service, "nativeRuntimeSupport", nativeSupport);
        var resolver = mock(AccessDatabaseResolver.class); var connection = new AccessDatabaseResolver.ConnectionInfo();
        connection.setProductType(ProductTypeEnum.POSTGRESQL); connection.setSchema("public");
        when(resolver.resolve(eq(101L), any(cn.iocoder.yudao.framework.common.exception.ErrorCode.class))).thenReturn(connection);
        ReflectionTestUtils.setField(service, "databaseResolver", resolver);
        task = DevTaskDO.builder().id(1L).revision(3).currentVersionId(80L).latestVersion(1).name("review_task")
                .taskType("SQL").content("DELETE FROM review_rows WHERE id=1").databaseId(101L).dbType("POSTGRESQL")
                .executionUserId(202L).status(1).scheduleStatus(0).timeoutSeconds(300).maxRows(1000)
                .scheduler("DOLPHIN_SCHEDULER").cronExpression("0 * * * * ?").build();
        when(tasks.selectById(1L)).thenReturn(task); when(tasks.selectForUpdate(1L)).thenReturn(task);
        when(tasks.update(any(), any())).thenReturn(1);
        when(submitter.configuredEnvironmentCode()).thenReturn(-1L);
        when(submitter.executionTarget(any())).thenReturn(new DevDsSubmitter.ExecutionTarget("controlled", "isolated"));
        doAnswer(invocation -> { DevTaskVersionDO version = invocation.getArgument(0); version.setId(81L); return 1; }).when(versions).insert(any(DevTaskVersionDO.class));
        doAnswer(invocation -> { ((java.util.function.Consumer<org.springframework.transaction.TransactionStatus>) invocation.getArgument(0))
                .accept(new SimpleTransactionStatus()); return null; }).when(transaction).executeWithoutResult(any());
        when(transaction.execute(any())).thenAnswer(invocation -> ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        TenantContextHolder.setTenantId(7L); login(77L); refreshVersion();
    }

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); TenantContextHolder.clear(); }

    @Test void browserCannotSelectAnotherUsersExecutionIdentity() {
        var request = request(); request.setExecutionUserId(202L);
        assertThrows(ServiceException.class, () -> service.createTask(request));
        verify(tasks, never()).insert(any(DevTaskDO.class)); verifyNoInteractions(transaction, versions, submitter);
    }

    @Test void saveDoesNotInheritThePreviousPrivilegedIdentity() {
        service.updateTask(request());
        var captured = org.mockito.ArgumentCaptor.forClass(DevTaskVersionDO.class); verify(versions).insert(captured.capture());
        assertEquals(77L, DevTaskSnapshot.read(captured.getValue().getSnapshotJson()).getTask().getExecutionUserId());
        verify(submitter).validateForSave(argThat(saved -> saved.getExecutionUserId().equals(77L)));
        verify(access).requireUserPermission(77L, ExecutionAccessService.PERMISSION_DEV_TASK_RUN);
    }

    @Test void manualRunAndEnableCannotBorrowAnotherUsersSavedIdentity() {
        var run = new DevTaskRunReqVO(); run.setId(1L); run.setRequestId("identity-denied"); run.setBusinessDate(LocalDate.now());
        assertThrows(ServiceException.class, () -> service.runTask(run));
        assertThrows(ServiceException.class, () -> service.updateTaskStatus(1L, 1));
        var schedule = new DevTaskScheduleReqVO(); schedule.setId(1L); schedule.setScheduleStatus(1);
        assertThrows(ServiceException.class, () -> service.updateTaskSchedule(schedule));
        verifyNoInteractions(transaction, submitter);
    }

    @Test void ownSavedSqlIsReauthorizedBeforeEnqueueing() {
        task.setExecutionUserId(77L); refreshVersion();
        doThrow(new ServiceException(1000, "test permission revoked")).when(submitter).validateForSave(any());
        var run = new DevTaskRunReqVO(); run.setId(1L); run.setRequestId("identity-recheck"); run.setBusinessDate(LocalDate.now());
        assertThrows(ServiceException.class, () -> service.runTask(run)); verifyNoInteractions(transaction);
        verify(submitter, never()).enqueue(any(), any(), any(), any(), any());
    }

    @Test void backgroundScheduleKeepsItsFrozenIdentityWithoutABrowserLogin() {
        SecurityContextHolder.clearContext(); task.setScheduleStatus(1);
        var binding = new DevScheduleDO(); binding.setId(10L); binding.setTaskId(1L);
        binding.setVersionId(80L); binding.setGeneration(3); binding.setDsProjectCode(19L); binding.setDsWorkflowCode(20L);
        binding.setLeaseOwner("owned"); binding.setLeaseUntil(LocalDateTime.now().plusMinutes(5));
        binding.setDesiredEnabled(true); binding.setSyncedEnabled(true); binding.setSyncStatus("SYNCED");
        when(schedules.selectById(10L)).thenReturn(binding); when(submitter.enqueue(any(), any(), any(), any(), any())).thenReturn(91L);
        assertEquals(91L, service.runTaskScheduled(binding, 5L, LocalDateTime.now()));
        verify(access).requireUserPermission(202L, ExecutionAccessService.PERMISSION_DEV_TASK_RUN);
        verify(submitter).enqueue(any(), any(), eq("SCHEDULE"), eq("DS_SCHEDULE:19:5"), any());
    }

    private void refreshVersion() {
        var snapshot = new DevTaskSnapshot(); snapshot.setTask(task);
        when(versions.selectById(80L)).thenReturn(DevTaskVersionDO.builder().id(80L).taskId(1L).snapshotJson(snapshot.write()).build());
    }

    private DevTaskSaveReqVO request() {
        var request = new DevTaskSaveReqVO(); request.setId(1L); request.setName("review_task");
        request.setTaskType("SQL"); request.setDatabaseId(101L); request.setContent(task.getContent()); request.setExpectedRevision(3);
        return request;
    }

    private void login(Long userId) { SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(userId).setTenantId(7L), new MockHttpServletRequest()); }
}

package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.*;
import cn.iocoder.yudao.module.dataforge.model.dev.DevJobNode;
import cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.*;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 作业准备复用人工执行护栏；能力检查不能创建运行或发布外部定义。 */
class DevJobExecutionTest {
    DevTaskServiceImpl service;DevTaskMapper tasks;DevTaskVersionMapper versions;DevDsSubmitter submitter;DevNativeRuntimeSupport nativeSupport;ExecutionAccessService access;TransactionTemplate transaction;DevTaskDO task;
    @BeforeEach void setup(){service=new DevTaskServiceImpl();tasks=mock(DevTaskMapper.class);versions=mock(DevTaskVersionMapper.class);submitter=mock(DevDsSubmitter.class);nativeSupport=mock(DevNativeRuntimeSupport.class);access=mock(ExecutionAccessService.class);transaction=mock(TransactionTemplate.class);ReflectionTestUtils.setField(service,"taskMapper",tasks);ReflectionTestUtils.setField(service,"versionMapper",versions);ReflectionTestUtils.setField(service,"dsSubmitter",submitter);ReflectionTestUtils.setField(service,"nativeRuntimeSupport",nativeSupport);ReflectionTestUtils.setField(service,"executionAccessService",access);ReflectionTestUtils.setField(service,"transactionTemplate",transaction);task=DevTaskDO.builder().id(1L).revision(3).currentVersionId(80L).taskType("SQL").content("SELECT 1").status(1).executionUserId(77L).build();when(tasks.selectById(1L)).thenReturn(task);when(tasks.selectForUpdate(1L)).thenReturn(task);when(transaction.execute(any())).thenAnswer(i->((TransactionCallback<?>)i.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));when(submitter.previewJobNode(any(),eq(80L))).thenReturn(new DevJobNode(1L,80L,null,100L,null,"controlled","datahub",88L,false));snapshot();SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(77L).setUserType(2),new MockHttpServletRequest());}
    void snapshot(){var snapshot=new DevTaskSnapshot();snapshot.setTask(task);snapshot.setWorkerGroup("controlled");snapshot.setTenantCode("datahub");snapshot.setManagedEnvironmentCode(88L);when(versions.selectById(80L)).thenReturn(DevTaskVersionDO.builder().id(80L).taskId(1L).snapshotJson(snapshot.write()).build());}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void previewIsReadOnlyAndCannotBorrowAnotherSavedActor(){service.previewJobNode(1L);verify(submitter,never()).enqueue(any(),any(),any(),any(),any());verify(submitter,never()).prepareJobNode(anyLong());verifyNoInteractions(transaction);task.setExecutionUserId(99L);snapshot();assertThrows(ServiceException.class,()->service.previewJobNode(1L));}
    @Test void revokedRunPermissionStopsBeforeAnyRecordOrWorkflow(){doThrow(new ServiceException(1000,"revoked")).when(access).requireUserPermission(77L,ExecutionAccessService.PERMISSION_DEV_TASK_RUN);assertThrows(ServiceException.class,()->service.prepareJobNode(1L,80L,"JOB:3:node",LocalDate.now()));verifyNoInteractions(transaction);verify(submitter,never()).prepareJobNode(anyLong());}
    @Test void nativePermissionAndFrozenProfileRemainIndependentChecks(){task.setTaskType("SPARK_SQL");task.setProgramConfigJson("{\"runtimeProfileCode\":\"managed\"}");snapshot();service.previewJobNode(1L);verify(access).requireUserPermission(77L,ExecutionAccessService.PERMISSION_DEV_TASK_RUN);verify(nativeSupport).requireActor(any(),eq(77L));verify(nativeSupport).requireFrozen(any());doThrow(new ServiceException(1000,"native revoked")).when(nativeSupport).requireActor(any(),eq(77L));assertThrows(ServiceException.class,()->service.previewJobNode(1L));}
    @Test void changedVersionUnderLockNeverPreparesTheOldChild(){var changed=DevTaskDO.builder().id(1L).status(1).currentVersionId(81L).build();when(tasks.selectForUpdate(1L)).thenReturn(changed);assertThrows(ServiceException.class,()->service.prepareJobNode(1L,80L,"JOB:3:node",LocalDate.now()));verify(submitter,never()).enqueue(any(),any(),any(),any(),any());verify(submitter,never()).prepareJobNode(anyLong());}
    @Test void prepareUsesDedicatedJobIntentAndCleansUpOnlyNeverTriggeredFailures(){when(submitter.enqueue(any(),any(),eq("JOB"),eq("JOB:3:node"),any())).thenReturn(44L);when(submitter.prepareJobNode(44L)).thenThrow(new ServiceException(1000,"prepare rejected"));assertThrows(ServiceException.class,()->service.prepareJobNode(1L,80L,"JOB:3:node",LocalDate.now()));verify(submitter).cancelPreparedJobNode(44L);}
    @Test void legacyAndSavedEnvironmentNeverUseCurrentReplacement(){var submitter=new DevDsSubmitter();ReflectionTestUtils.setField(submitter,"environmentCode",999L);var old=new DevTaskSnapshot();old.setTask(DevTaskDO.builder().taskType("SQL").build());assertEquals(-1L,submitter.executionEnvironment(old));old.setManagedEnvironmentCode(88L);assertEquals(88L,submitter.executionEnvironment(DevTaskSnapshot.read(old.write())));}
    @Test void backgroundPreviewUsesFrozenActorWithoutInventingALogin() {
        SecurityContextHolder.clearContext();assertNotNull(service.previewJobNodeForActor(1L,77L));verify(access).requireUserPermission(77L,ExecutionAccessService.PERMISSION_DEV_TASK_RUN);
        assertThrows(ServiceException.class,()->service.previewJobNodeForActor(1L,99L));verify(submitter,never()).enqueue(any(),any(),any(),any(),any());
    }

    @Test void oldNativeVersionStopRequiresCallersNativePermissionEvenIfCurrentTaskIsSql() {
        task.setTaskType("SPARK_SQL");task.setProgramConfigJson("{\"runtimeProfileCode\":\"managed\"}");snapshot();task.setTaskType("SQL");
        doThrow(new ServiceException(1000,"native permission denied")).when(nativeSupport).requireActor(any(),eq(99L));
        assertThrows(ServiceException.class,()->service.authorizeJobTaskStop(1L,80L,99L));verify(access).requireUserPermission(99L,ExecutionAccessService.PERMISSION_DEV_TASK_RUN);
        verify(nativeSupport).requireActor(argThat(frozen->"SPARK_SQL".equals(frozen.getTaskType())),eq(99L));verify(submitter,never()).stop(anyLong());
    }

}

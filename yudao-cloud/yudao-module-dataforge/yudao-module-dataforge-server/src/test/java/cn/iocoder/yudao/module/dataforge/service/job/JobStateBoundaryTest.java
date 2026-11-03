package cn.iocoder.yudao.module.dataforge.service.job;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.dataforge.controller.admin.job.vo.JobModels.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.job.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.job.*;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 状态检查与并发占用顺序，以及演示回执不能被当成真实运行的边界。 */
class JobStateBoundaryTest {
    @org.junit.jupiter.api.BeforeAll static void initializeMappings() {
        var configuration=new com.baomidou.mybatisplus.core.MybatisConfiguration();
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(configuration,"job-state-test"),JobTaskDO.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(configuration,"job-record-test"),JobRecordDO.class);
    }
    @Test void locksJobTransactionBeforeCheckingActiveRunsAndChangingStatus() {
        var service=new JobService();var jobs=mock(JobTaskMapper.class);var records=mock(JobRecordMapper.class);
        ReflectionTestUtils.setField(service,"jobs",jobs);ReflectionTestUtils.setField(service,"records",records);
        var job=new JobTaskDO();job.setId(1L);job.setRevision(2);job.setStatus(1);job.setScheduleStatus(0);job.setOperationState("IDLE");
        when(jobs.selectById(1L)).thenReturn(job);when(jobs.selectForUpdate(1L)).thenReturn(job);when(jobs.update(any(),any())).thenReturn(1);transaction(service);when(records.selectList(any(Wrapper.class))).thenReturn(List.of());
        var req=new StatusReq();req.setId(1L);req.setRevision(2);req.setStatus(0);service.status(req);
        var order=inOrder(jobs,records);order.verify(jobs).selectById(1L);order.verify(jobs).selectForUpdate(1L);order.verify(records).selectList(any(Wrapper.class));order.verify(jobs).update(any(),any());
    }
    @Test void staleRevisionRejectsStatusBeforeCheckingOrMutatingRuntime() {
        var service=new JobService();var jobs=mock(JobTaskMapper.class);var records=mock(JobRecordMapper.class);
        ReflectionTestUtils.setField(service,"jobs",jobs);ReflectionTestUtils.setField(service,"records",records);
        var job=new JobTaskDO();job.setId(1L);job.setRevision(3);when(jobs.selectById(1L)).thenReturn(job);when(jobs.selectForUpdate(1L)).thenReturn(job);transaction(service);
        var req=new StatusReq();req.setId(1L);req.setRevision(2);req.setStatus(0);assertThrows(ServiceException.class,()->service.status(req));verifyNoInteractions(records);
    }
    @Test void sourceHistoricalRequestCannotReturnAsARealRunReceipt() {
        var service=new JobService();var jobs=mock(JobTaskMapper.class);var records=mock(JobRecordMapper.class);
        ReflectionTestUtils.setField(service,"jobs",jobs);ReflectionTestUtils.setField(service,"records",records);
        var source=new JobRecordDO();source.setId(10L);source.setJobId(1L);source.setOrigin("SOURCE");when(records.selectOne(any(Wrapper.class))).thenReturn(source);
        var req=new RunReq();req.setId(1L);req.setRevision(0);req.setRequestId("demo0255_job_1");assertThrows(ServiceException.class,()->service.run(req));verifyNoInteractions(jobs);
    }
    private void transaction(JobService service) {
        var tx=mock(org.springframework.transaction.support.TransactionTemplate.class);
        doAnswer(call->{((java.util.function.Consumer<org.springframework.transaction.TransactionStatus>)call.getArgument(0)).accept(new org.springframework.transaction.support.SimpleTransactionStatus());return null;}).when(tx).executeWithoutResult(any());ReflectionTestUtils.setField(service,"transaction",tx);
    }

}

package cn.iocoder.yudao.module.dataforge.service.job;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinCronSchedule;
import cn.iocoder.yudao.module.dataforge.controller.admin.job.vo.JobModels.RunReq;
import cn.iocoder.yudao.module.dataforge.controller.admin.job.vo.JobModels.ScheduleReq;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.job.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.job.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** 作业领域持久受理、Quartz 槽位与真实终态驱动的并发队列；不持有内存计时器。 */
@Service
public class JobQueueCoordinator {
    @Resource private JobTaskMapper jobs;
    @Resource private JobRecordMapper records;
    @Resource private JobManagedRunService runtime;
    @Resource private TransactionTemplate transaction;
    private static final Set<String> TERMINAL=Set.of("SUCCESS","FAILURE","STOPPED");
    private static final Set<String> PENDING=Set.of("QUEUED","DISPATCHING","ACTIVE","STOP_REQUESTED","UNKNOWN");
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");

    /** 权限和冻结目标在短事务之前检查，受理只锁作业及本地记录。 */
    public Long enqueueManual(JobTaskDO observed,RunReq request,Long actor,String snapshot) {
        var authorized=authorizePriority(observed.getId(),observed.getStrategy(),actor);
        Long id=transaction.execute(status->{
            var job=locked(observed.getId());
            var prior=records.selectOne(Wrappers.<JobRecordDO>lambdaQuery().eq(JobRecordDO::getRequestId,request.getRequestId()));
            if(prior!=null){if(!"PLATFORM".equals(prior.getOrigin())||!Objects.equals(prior.getJobId(),job.getId()))throw exception(JOB_CONFLICT);return prior.getId();}
            if(!Objects.equals(job.getRevision(),request.getRevision())||job.getStatus()!=1)throw exception(JOB_STATE_INVALID);
            if(!cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseTree(job.getFlowJson()).equals(cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseTree(observed.getFlowJson()))||!Objects.equals(job.getStrategy(),observed.getStrategy()))throw exception(JOB_CONFLICT);
            requireAuthorizedPending(job,authorized);
            return enqueueLocked(job,request.getRequestId(),actor,snapshot,null,null,null).getId();
        });
        processJob(observed.getId(),false);return id;
    }

    /** 代次切换与取消旧代未启动槽位属于同一事务；已启动运行保留真实对账链。 */
    public void configureSchedule(ScheduleReq request,Long actor,String snapshot) {
        transaction.executeWithoutResult(status->{
            var job=locked(request.getId());if(!Objects.equals(job.getRevision(),request.getRevision()))throw exception(JOB_CONFLICT);
            if(request.getScheduleStatus()==1&&(job.getStatus()!=1||StrUtil.isBlank(snapshot)))throw exception(JOB_STATE_INVALID);
            cancelQueued(job.getId(),job.getScheduleGeneration(),true,"周期代次已关闭或更新，未派发槽位已取消");
            long generation=job.getScheduleGeneration()==null?1:job.getScheduleGeneration()+1;
            var changed=new JobTaskDO();changed.setId(job.getId());changed.setRevision(job.getRevision()+1);changed.setScheduleStatus(request.getScheduleStatus());
            changed.setUpdater(String.valueOf(actor));changed.setUpdateTime(now());changed.setScheduleGeneration(generation);changed.setCronExpression(request.getCronExpression());changed.setOperationState("IDLE");
            if(request.getScheduleStatus()==1){changed.setScheduleUserId(actor);changed.setScheduleSnapshotJson(snapshot);changed.setNextRunTime(DolphinCronSchedule.parse(request.getCronExpression()).next(now(),ZONE));if(changed.getNextRunTime()==null)throw exception(JOB_CONFIG_INVALID,"周期没有未来可执行时间");}
            jobs.updateById(changed);
            if(request.getScheduleStatus()==0)jobs.update(null,Wrappers.<JobTaskDO>lambdaUpdate().eq(JobTaskDO::getId,job.getId()).set(JobTaskDO::getNextRunTime,null));
        });
    }

    private JobRecordDO enqueueLocked(JobTaskDO job,String key,Long actor,String snapshot,Long generation,LocalDateTime scheduled,String rejection) {
        var pending=pending(job.getId());var record=new JobRecordDO();var frozen=JSONUtil.parseObj(snapshot);
        record.setJobId(job.getId());record.setOrigin("PLATFORM");record.setRequestId(key);record.setExecutionUserId(actor);
        record.setExecutionType(scheduled==null?"START":"SCHEDULE");record.setInstanceName(frozen.getStr("name")+"-"+now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")));
        record.setStatus("WAITING");record.setQueueState("QUEUED");record.setQueueReason("已持久受理，等待作业级派发；尚无 Worker 执行");record.setQueuedTime(now());
        record.setScheduledTime(scheduled);record.setScheduleGeneration(generation);record.setRunSnapshotJson(snapshot);record.setFlowJson(frozen.getStr("flowJson"));
        record.setCreator(String.valueOf(actor));record.setUpdater(String.valueOf(actor));record.setCreateTime(record.getQueuedTime());record.setUpdateTime(record.getQueuedTime());
        record.setSerialSlot(false);record.setStopRequested(false);record.setSubmissionStage("QUEUED");record.setLogContent(record.getQueueReason());
        var target=frozen.getJSONArray("bindings").getJSONObject(0).getJSONObject("target");record.setRuntimeTargetJson(target.toString());record.setDsProjectCode(target.getLong("project"));
        if(rejection!=null)terminateQueued(record,"REJECTED",rejection);
        else if(pendingCount(job.getId(),false)>=100&&!Set.of("SERIAL_DISCARD","SERIAL_PRIORITY").contains(job.getStrategy()))terminateQueued(record,"REJECTED","作业最多保留 100 个未结束请求；本次超过队列容量，已拒绝且未触发业务");
        else if("SERIAL_DISCARD".equals(job.getStrategy())&&pendingCount(job.getId(),false)>0)terminateQueued(record,"DISCARDED","作业已有未结束运行或排队，本次串行抛弃；未创建子运行、未触发 Worker");
        else if("SERIAL_PRIORITY".equals(job.getStrategy())) {
            for(var old:pending) {
                if("QUEUED".equals(old.getQueueState()))finishQueued(old,"SUPERSEDED","已被更新的串行优先请求取代；未执行");
                else records.update(null,Wrappers.<JobRecordDO>lambdaUpdate().eq(JobRecordDO::getId,old.getId()).set(JobRecordDO::getUpdateTime,now()).notIn(JobRecordDO::getStatus,TERMINAL)
                        .set(JobRecordDO::getStopRequested,true).set(JobRecordDO::getQueueState,"STOP_REQUESTED")
                        .set(JobRecordDO::getQueueReason,"后续串行优先请求已受理；等待本次真实父子退出后才晋级"));
            }
        }
        records.insert(record);return record;
    }

    /** 一轮最多二十个作业、每作业五个到期槽，重启后沿持久游标继续有界追赶。 */
    public int maintain() {
        int failures=0;
        var due=jobs.selectList(Wrappers.<JobTaskDO>lambdaQuery().eq(JobTaskDO::getStatus,1).eq(JobTaskDO::getScheduleStatus,1)
                .isNotNull(JobTaskDO::getScheduleSnapshotJson).le(JobTaskDO::getNextRunTime,now()).orderByAsc(JobTaskDO::getNextRunTime,JobTaskDO::getId).last("LIMIT 20"));
        for(var observed:due)try{for(int i=0;i<5;i++)if(!materializeSlot(observed.getId()))break;}catch(RuntimeException unavailable){failures++;}
        var active=records.selectList(Wrappers.<JobRecordDO>lambdaQuery().select(JobRecordDO::getJobId).eq(JobRecordDO::getOrigin,"PLATFORM").in(JobRecordDO::getQueueState,PENDING)
                .groupBy(JobRecordDO::getJobId).last("ORDER BY max(update_time),job_id LIMIT 20"));
        var ids=new LinkedHashSet<Long>();active.forEach(record->ids.add(record.getJobId()));
        for(Long id:ids)try{failures+=processJob(id,true);}catch(RuntimeException unavailable){failures++;}
        return failures;
    }

    private boolean materializeSlot(Long id) {
        var observed=jobs.selectById(id);if(observed==null||observed.getNextRunTime()==null)return false;
        var probe=new JobRecordDO();probe.setJobId(id);probe.setExecutionUserId(observed.getScheduleUserId());probe.setRunSnapshotJson(observed.getScheduleSnapshotJson());
        String rejected=null;
        try{runtime.validateFrozen(probe);}catch(ServiceException denied){rejected="本周期槽权限、冻结版本或目标校验未通过；未触发、未抢占："+reason(denied);}
        Set<Long> authorized=Set.of();
        if(rejected==null)try{authorized=authorizePriority(id,observed.getStrategy(),observed.getScheduleUserId());}catch(ServiceException denied){rejected="周期槽未获授权抢占旧冻结运行："+reason(denied);}
        final String rejection=rejected;final Set<Long> authorizedPending=authorized;
        return Boolean.TRUE.equals(transaction.execute(status->{
            var job=locked(id);var due=job.getNextRunTime();
            if(!Objects.equals(job.getScheduleGeneration(),observed.getScheduleGeneration())||!Objects.equals(due,observed.getNextRunTime()))return false;
            if(job.getStatus()!=1||job.getScheduleStatus()!=1||due==null||due.isAfter(now())||StrUtil.isBlank(job.getScheduleSnapshotJson()))return false;
            if(rejection==null&&"SERIAL_PRIORITY".equals(job.getStrategy())&&!authorizedPending.containsAll(pending(id).stream().map(JobRecordDO::getId).toList()))return false;
            var prior=records.selectOne(Wrappers.<JobRecordDO>lambdaQuery().eq(JobRecordDO::getJobId,id).eq(JobRecordDO::getScheduleGeneration,job.getScheduleGeneration()).eq(JobRecordDO::getScheduledTime,due));
            if(prior==null)enqueueLocked(job,"CRON_"+id+"_"+job.getScheduleGeneration()+"_"+due.atZone(ZONE).toEpochSecond(),job.getScheduleUserId(),job.getScheduleSnapshotJson(),job.getScheduleGeneration(),due,rejection);
            var next=DolphinCronSchedule.parse(job.getCronExpression()).next(due,ZONE);
            jobs.update(null,Wrappers.<JobTaskDO>lambdaUpdate().eq(JobTaskDO::getId,id).eq(JobTaskDO::getScheduleGeneration,job.getScheduleGeneration()).eq(JobTaskDO::getNextRunTime,due).set(JobTaskDO::getNextRunTime,next).set(JobTaskDO::getUpdateTime,now()));return true;
        }));
    }

    /** 普通读取只观察原实例和释放已证明的终态，不创建 Cron 槽或发送控制命令。 */
    public void reconcileForRead(Long id) {reconcile(id,false);}

    private int processJob(Long id,boolean maintenance) {
        int failures=reconcile(id,true);
        for(int count=0;count<5;count++) {
            var claimed=claimNext(id);if(claimed==null)return failures;
            try {
                runtime.executeAccepted(claimed,claimed.getLeaseOwner());
                records.update(null,Wrappers.<JobRecordDO>lambdaUpdate().eq(JobRecordDO::getId,claimed.getId()).set(JobRecordDO::getUpdateTime,now()).eq(JobRecordDO::getLeaseOwner,claimed.getLeaseOwner())
                        .eq(JobRecordDO::getQueueState,"DISPATCHING").set(JobRecordDO::getQueueState,"ACTIVE").set(JobRecordDO::getQueueReason,"真实父工作流已确认，等待所有子执行终态"));
            } catch(RuntimeException failure) {rejectOrPreserve(claimed,failure);if(!(failure instanceof ServiceException))failures++;}
            failures+=reconcile(id,false);
            if(!"PARALLEL".equals(JSONUtil.parseObj(claimed.getRunSnapshotJson()).getStr("strategy")))return failures;
        }
        return failures;
    }

    private JobRecordDO claimNext(Long id) {
        return transaction.execute(status->{
            var job=locked(id);if(job.getStatus()!=1)return null;
            var pending=pending(id);var queued=pending.stream().filter(r->"QUEUED".equals(r.getQueueState())).findFirst().orElse(null);if(queued==null)return null;
            String strategy=JSONUtil.parseObj(queued.getRunSnapshotJson()).getStr("strategy");
            if(!"PARALLEL".equals(strategy)&&pendingCount(id,true)>0)return null;
            String owner=UUID.randomUUID().toString();
            if(records.update(null,Wrappers.<JobRecordDO>lambdaUpdate().eq(JobRecordDO::getId,queued.getId()).set(JobRecordDO::getUpdateTime,now()).eq(JobRecordDO::getQueueState,"QUEUED")
                    .eq(JobRecordDO::getStopRequested,false).set(JobRecordDO::getQueueState,"DISPATCHING")
                    .set(JobRecordDO::getSubmissionStage,"PREPARING").set(JobRecordDO::getSerialSlot,!"PARALLEL".equals(strategy))
                    .set(JobRecordDO::getLeaseOwner,owner).set(JobRecordDO::getLeaseUntil,now().plusMinutes(5))
                    .set(JobRecordDO::getQueueReason,"已领取冻结运行，准备本次独立父子工作流"))!=1)return null;
            return records.selectById(queued.getId());
        });
    }

    private int reconcile(Long id,boolean control) {
        int failures=0;
        var active=records.selectList(Wrappers.<JobRecordDO>lambdaQuery().eq(JobRecordDO::getJobId,id).eq(JobRecordDO::getOrigin,"PLATFORM")
                .and(q->q.in(JobRecordDO::getQueueState,PENDING).ne(JobRecordDO::getQueueState,"QUEUED").or(x->x.isNull(JobRecordDO::getQueueState).in(JobRecordDO::getStatus,List.of("WAITING","RUNNING","UNKNOWN"))))
                .orderByAsc(JobRecordDO::getUpdateTime,JobRecordDO::getId).last("LIMIT 10"));
        for(var record:active) {
            try {
                records.update(null,Wrappers.<JobRecordDO>lambdaUpdate().eq(JobRecordDO::getId,record.getId()).set(JobRecordDO::getUpdateTime,now()));
                if(record.getLeaseUntil()!=null&&record.getLeaseUntil().isBefore(now())&&record.getDsInstanceId()==null
                        &&!"TRIGGER_SENT".equals(record.getSubmissionStage())) {
                    records.update(null,Wrappers.<JobRecordDO>lambdaUpdate().eq(JobRecordDO::getId,record.getId()).set(JobRecordDO::getUpdateTime,now()).eq(JobRecordDO::getLeaseOwner,record.getLeaseOwner())
                            .le(JobRecordDO::getLeaseUntil,now()).notIn(JobRecordDO::getStatus,TERMINAL).set(JobRecordDO::getQueueState,"UNKNOWN")
                            .set(JobRecordDO::getStatus,"UNKNOWN").set(JobRecordDO::getQueueReason,"准备者租约已过期，保留占位；不得盲目重建或触发，需核查专属准备链"));
                }
                if(runtime.owned(record))runtime.reconcile(record);
                var actual=records.selectById(record.getId());if(actual==null)continue;
                if(TERMINAL.contains(actual.getStatus())) {complete(actual);continue;}
                if(control&&Boolean.TRUE.equals(actual.getStopRequested()))runtime.stopForActor(actual);
            } catch(RuntimeException unavailable) {
                failures++;
                records.update(null,Wrappers.<JobRecordDO>lambdaUpdate().eq(JobRecordDO::getId,record.getId()).set(JobRecordDO::getUpdateTime,now()).notIn(JobRecordDO::getStatus,TERMINAL)
                        .set(JobRecordDO::getQueueReason,"原运行尚不可确认；保留占位，不重投、不晋级："+reason(unavailable)));
            }
        }
        return failures;
    }

    private void rejectOrPreserve(JobRecordDO claimed,RuntimeException failure) {
        var actual=records.selectById(claimed.getId());if(actual==null)return;
        if(actual.getDsInstanceId()!=null||Set.of("TRIGGER_SENT","BOUND").contains(String.valueOf(actual.getSubmissionStage()))) {
            records.update(null,Wrappers.<JobRecordDO>lambdaUpdate().eq(JobRecordDO::getId,actual.getId()).set(JobRecordDO::getUpdateTime,now()).notIn(JobRecordDO::getStatus,TERMINAL)
                    .set(JobRecordDO::getQueueState,Boolean.TRUE.equals(actual.getStopRequested())?"STOP_REQUESTED":"UNKNOWN")
                    .set(JobRecordDO::getQueueReason,"提交结果待核对，禁止重新触发："+reason(failure)));return;
        }
        records.update(null,Wrappers.<JobRecordDO>lambdaUpdate().eq(JobRecordDO::getId,actual.getId()).set(JobRecordDO::getUpdateTime,now()).eq(JobRecordDO::getLeaseOwner,claimed.getLeaseOwner())
                .in(JobRecordDO::getSubmissionStage,List.of("PREPARING","PREPARED")).set(JobRecordDO::getStatus,Boolean.TRUE.equals(actual.getStopRequested())?"STOPPED":"FAILURE")
                .set(JobRecordDO::getQueueState,Boolean.TRUE.equals(actual.getStopRequested())?"CANCELLED":"REJECTED")
                .set(JobRecordDO::getQueueReason,"业务父流程未触发，本次准备已终止："+reason(failure)).set(JobRecordDO::getEndTime,now())
                .set(JobRecordDO::getSerialSlot,false).set(JobRecordDO::getLeaseOwner,null).set(JobRecordDO::getLeaseUntil,null));
    }

    /** 停止全作业未启动队列并持久请求停止真实运行，不把控制回执当作退出证明。 */
    public void stop(Long id) {
        Long caller=cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId();
        var checked=pending(id);for(var record:checked)runtime.authorizeStopRequest(record,caller);
        var checkedIds=new HashSet<Long>();checked.forEach(record->checkedIds.add(record.getId()));
        transaction.executeWithoutResult(status->{locked(id);var current=pending(id);
            if(!checkedIds.containsAll(current.stream().map(JobRecordDO::getId).toList()))throw exception(JOB_CONFLICT);
            for(var record:current) {
                if("QUEUED".equals(record.getQueueState()))finishQueued(record,"CANCELLED","用户取消尚未派发的作业请求；无 Worker 执行");
                else records.update(null,Wrappers.<JobRecordDO>lambdaUpdate().eq(JobRecordDO::getId,record.getId()).set(JobRecordDO::getUpdateTime,now()).notIn(JobRecordDO::getStatus,TERMINAL)
                        .set(JobRecordDO::getStopRequested,true).set(JobRecordDO::getQueueState,"STOP_REQUESTED").set(JobRecordDO::getQueueReason,"已持久请求停止，等待真实父子与原生门禁确认退出"));
            }
        });reconcile(id,true);
    }

    private Set<Long> authorizePriority(Long id,String strategy,Long caller) {
        if(!"SERIAL_PRIORITY".equals(strategy))return Set.of();
        var checked=pending(id);var result=new HashSet<Long>();
        for(var old:checked){runtime.authorizeStopRequest(old,caller);result.add(old.getId());}return result;
    }
    private void requireAuthorizedPending(JobTaskDO job,Set<Long> authorized) {
        if("SERIAL_PRIORITY".equals(job.getStrategy())&&!authorized.containsAll(pending(job.getId()).stream().map(JobRecordDO::getId).toList()))throw exception(JOB_CONFLICT);
    }

    private void cancelQueued(Long id,Long generation,boolean scheduledOnly,String reason) {
        var query=Wrappers.<JobRecordDO>lambdaQuery().eq(JobRecordDO::getJobId,id).eq(JobRecordDO::getQueueState,"QUEUED");
        if(scheduledOnly)query.isNotNull(JobRecordDO::getScheduledTime).eq(JobRecordDO::getScheduleGeneration,generation);
        for(var record:records.selectList(query))finishQueued(record,"CANCELLED",reason);
    }
    private void finishQueued(JobRecordDO record,String state,String reason) {records.update(null,Wrappers.<JobRecordDO>lambdaUpdate().eq(JobRecordDO::getId,record.getId()).set(JobRecordDO::getUpdateTime,now()).eq(JobRecordDO::getQueueState,"QUEUED")
            .set(JobRecordDO::getStatus,"STOPPED").set(JobRecordDO::getQueueState,state).set(JobRecordDO::getQueueReason,reason).set(JobRecordDO::getLogContent,reason).set(JobRecordDO::getEndTime,now()).set(JobRecordDO::getSerialSlot,false));}
    private void terminateQueued(JobRecordDO record,String state,String reason) {record.setStatus("REJECTED".equals(state)?"FAILURE":"STOPPED");record.setQueueState(state);record.setQueueReason(reason);record.setLogContent(reason);record.setEndTime(now());}
    private void complete(JobRecordDO record) {records.update(null,Wrappers.<JobRecordDO>lambdaUpdate().eq(JobRecordDO::getId,record.getId()).set(JobRecordDO::getUpdateTime,now()).in(JobRecordDO::getStatus,TERMINAL)
            .set(JobRecordDO::getQueueState,"DONE").set(JobRecordDO::getQueueReason,"真实父子终态已确认："+record.getStatus()).set(JobRecordDO::getSerialSlot,false).set(JobRecordDO::getLeaseOwner,null).set(JobRecordDO::getLeaseUntil,null));}
    private JobTaskDO locked(Long id) {var job=jobs.selectForUpdate(id);if(job==null)throw exception(JOB_NOT_EXISTS);return job;}
    private List<JobRecordDO> pending(Long id) {return records.selectList(Wrappers.<JobRecordDO>lambdaQuery().eq(JobRecordDO::getJobId,id).eq(JobRecordDO::getOrigin,"PLATFORM")
            .and(q->q.in(JobRecordDO::getQueueState,PENDING).or(x->x.isNull(JobRecordDO::getQueueState).in(JobRecordDO::getStatus,List.of("WAITING","RUNNING","UNKNOWN"))))
            .orderByAsc(JobRecordDO::getQueuedTime,JobRecordDO::getId).last("LIMIT 100"));}
    private long pendingCount(Long id,boolean nonQueuedOnly) {
        var query=Wrappers.<JobRecordDO>lambdaQuery().eq(JobRecordDO::getJobId,id).eq(JobRecordDO::getOrigin,"PLATFORM")
                .and(q->q.in(JobRecordDO::getQueueState,PENDING).or(x->x.isNull(JobRecordDO::getQueueState).in(JobRecordDO::getStatus,List.of("WAITING","RUNNING","UNKNOWN"))));
        if(nonQueuedOnly)query.and(q->q.ne(JobRecordDO::getQueueState,"QUEUED").or().isNull(JobRecordDO::getQueueState));
        return records.selectCount(query);
    }
    private static String reason(RuntimeException error) {return StrUtil.sub(error instanceof ServiceException?error.getMessage():error.getClass().getSimpleName(),0,500);}
    private static LocalDateTime now(){return LocalDateTime.now(ZONE);}
}

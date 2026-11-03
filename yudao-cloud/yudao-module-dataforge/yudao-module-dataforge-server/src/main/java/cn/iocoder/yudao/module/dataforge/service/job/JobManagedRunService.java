package cn.iocoder.yudao.module.dataforge.service.job;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.*;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerClient;
import cn.iocoder.yudao.framework.dolphinscheduler.model.WorkflowDefinitionParameters;
import cn.iocoder.yudao.framework.dolphinscheduler.request.*;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.job.vo.JobModels.RunReq;
import cn.iocoder.yudao.module.dataforge.controller.admin.operations.vo.OpsInstanceModels.InstanceResp;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.job.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.job.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.integration.*;
import cn.iocoder.yudao.module.dataforge.service.dev.*;
import cn.iocoder.yudao.module.dataforge.model.dev.DevJobNode;
import cn.iocoder.yudao.module.dataforge.service.integration.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** 每次人工运行拥有独立父子身份；开发发布、终态和日志始终委托其原有受管边界。 */
@Service
public class JobManagedRunService {
    @Resource private JobRecordMapper records;
    @Resource private JobNodeRunMapper nodes;
    @Resource private IntegrationTaskService integrations;
    @Resource private IntegrationDsRuntime integrationRuntime;
    @Resource private IntegrationRuntimeProperties runtime;
    @Resource private IntegrationRecordMapper integrationRecords;
    @Resource private DevTaskService development;
    @Resource private DevRecordService developmentRecords;
    @Resource private DolphinSchedulerClient ds;
    @Resource private SecurityFrameworkService security;
    @Resource private cn.iocoder.yudao.module.dataforge.service.security.ExecutionAccessService executionAccess;
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> TERMINAL=Set.of("SUCCESS","FAILURE","STOPPED");
    private static final int MAX_LOG=1_000_000;

    public record Target(Long project, String worker, String tenant, Long environment) { }
    private record Reference(JSONObject node, Target target, IntegrationTaskDO integration, DevJobNode development) { }

    /** 能力检查只读当前冻结版本，不分配平台运行、发布定义或发送触发。 */
    public void validateForRead(String graph) { describe(graph); }

    private List<Reference> describe(String graph) {return describe(graph,SecurityFrameworkUtils.getLoginUserId(),true);}

    private List<Reference> describe(String graph,Long actor,boolean interactive) {
        var flow=JSONUtil.parseObj(graph);var source=flow.getJSONArray("nodes");var edges=flow.getJSONArray("edges");
        if(source==null||source.isEmpty())throw exception(JOB_CONFIG_INVALID,"请先添加作业节点");
        var result=new ArrayList<Reference>();var devTasks=new HashSet<Long>();Target common=null;
        for(Object raw:source) {
            var node=(JSONObject)raw;Long taskId=node.getLong("taskId");Reference ref;
            if("DEVELOPMENT".equals(node.getStr("kind"))) {
                if(!devTasks.add(taskId))throw exception(JOB_CONFIG_INVALID,"同一次作业不能重复引用同一个开发任务，请使用独立保存版本的任务");
                var binding=interactive?development.previewJobNode(taskId):development.previewJobNodeForActor(taskId,actor);
                if(binding.terminalGuardRequired()&&edges.stream().map(e->(JSONObject)e).anyMatch(e->node.getStr("id").equals(e.getStr("source"))))throw exception(JOB_CONFIG_INVALID,"包含原生独立终态或质量门禁的开发任务必须位于作业末端，父作业会等待真实门禁通过");
                ref=new Reference(node,new Target(binding.projectCode(),binding.workerGroup(),binding.tenantCode(),binding.environmentCode()),null,binding);
            } else {
                if(interactive){if(!security.hasPermission("dataforge:integration:run"))throw exception(OPERATION_PERMISSION_DENIED);}
                else executionAccess.requireUserPermission(actor,"dataforge:integration:run");
                var task=integrations.validateJobReference(taskId);
                ref=new Reference(node,new Target(task.getDsProjectCode(),task.getWorkerGroup(),runtime.getTenantCode(),integrationRuntime.environmentCode(task)),task,null);
            }
            var target=ref.target();
            if(target.project()==null||target.project()<=0||StrUtil.isBlank(target.worker())||StrUtil.isBlank(target.tenant())||target.environment()==null)throw exception(JOB_CONFIG_INVALID,"节点未冻结完整受管执行目标，请重新保存任务");
            if(common!=null&&!common.equals(target))throw exception(JOB_CONFIG_INVALID,"子工作流会继承父执行目标；所有节点的项目、工作组、执行用户和环境必须完全一致，请重新保存配置");
            common=target;result.add(ref);
        }
        return result;
    }

    /** 入队即冻结实际执行人、子版本和目标，不发布定义或创建子运行。 */
    public String freeze(JobTaskDO job,Long actor,boolean interactive) {
        executionAccess.requireUserPermission(actor,"dataforge:job:run");
        var references=describe(job.getFlowJson(),actor,interactive);
        return new JSONObject().set("jobId",job.getId()).set("name",job.getName()).set("revision",job.getRevision())
                .set("strategy",job.getStrategy()).set("actor",actor).set("flowJson",job.getFlowJson())
                .set("bindings",bindingSnapshot(references)).toString();
    }

    private JSONArray bindingSnapshot(List<Reference> references) {
        var result=new JSONArray();for(var ref:references)result.add(new JSONObject().set("nodeId",ref.node().getStr("id"))
                .set("kind",ref.node().getStr("kind")).set("taskId",ref.node().getLong("taskId"))
                .set("version",ref.development()!=null?ref.development().versionId():ref.integration().getRevision().longValue())
                .set("definition",ref.integration()==null?null:ref.integration().getDsDefinitionCode())
                .set("target",JSONUtil.parseObj(targetJson(ref.target()))));return result;
    }

    /** 派发复核入队快照；当前配置变化只能拒绝，不能悄悄换新版本。 */
    public void validateFrozen(JobRecordDO record) {
        var frozen=JSONUtil.parseObj(record.getRunSnapshotJson());
        if(!Objects.equals(record.getExecutionUserId(),frozen.getLong("actor"))||!Objects.equals(record.getJobId(),frozen.getLong("jobId")))throw exception(JOB_CONFIG_INVALID,"冻结作业身份不一致");
        executionAccess.requireUserPermission(record.getExecutionUserId(),"dataforge:job:run");
        var actual=bindingSnapshot(describe(frozen.getStr("flowJson"),record.getExecutionUserId(),false));
        if(!JsonUtils.parseTree(actual.toString()).equals(JsonUtils.parseTree(frozen.getJSONArray("bindings").toString())))throw exception(JOB_CONFIG_INVALID,"入队后引用版本或受管目标发生变化，本次冻结运行已拒绝");
    }

    /** 仅领取成功的持久记录可调用；外部准备与触发不占有数据库事务锁。 */
    public Long executeAccepted(JobRecordDO record,String leaseOwner) {
        validateFrozen(record);var snapshot=JSONUtil.parseObj(record.getRunSnapshotJson());
        var references=describe(snapshot.getStr("flowJson"),record.getExecutionUserId(),false);var target=references.getFirst().target();
        boolean sent=false;
        try {
            for(var ref:references) {
                fence(record,leaseOwner);
                var link=new JobNodeRunDO();link.setCreator(String.valueOf(record.getExecutionUserId()));link.setUpdater(String.valueOf(record.getExecutionUserId()));link.setCreateTime(now());link.setUpdateTime(now());link.setJobRecordId(record.getId());link.setNodeId(ref.node().getStr("id"));link.setKind(ref.node().getStr("kind"));link.setTaskId(ref.node().getLong("taskId"));link.setTaskName(ref.integration()==null?ref.node().getStr("name"):ref.integration().getName());link.setDsProjectCode(ref.target().project());link.setStatus("WAITING");
                if(ref.development()!=null) {
                    link.setTaskVersion(ref.development().versionId());nodes.insert(link);
                    var prepared=development.prepareJobNodeForActor(link.getTaskId(),link.getTaskVersion(),"JOB:"+record.getId()+":"+link.getNodeId(),record.getScheduledTime()==null?record.getQueuedTime().toLocalDate():record.getScheduledTime().toLocalDate(),record.getExecutionUserId());
                    if(!target.equals(new Target(prepared.projectCode(),prepared.workerGroup(),prepared.tenantCode(),prepared.environmentCode())))throw exception(JOB_CONFIG_INVALID,"开发节点准备期间受管目标已变化");
                    link.setPlatformRecordId(prepared.recordId());link.setDsDefinitionCode(prepared.workflowCode());link.setTaskName(development.observeJobNode(prepared.recordId()).getTaskName());nodes.updateById(link);
                } else {link.setTaskVersion(ref.integration().getRevision().longValue());link.setDsDefinitionCode(ref.integration().getDsDefinitionCode());nodes.insert(link);}
            }
            var frozen=links(record.getId());var codes=checked(ds.taskDefinition().genTaskCodeList(target.project(),frozen.size())).getJSONArray("data");
            if(codes==null||codes.size()!=frozen.size())throw exception(JOB_RUNTIME_FAILED);
            var steps=new ArrayList<WorkflowDefinitionParameters.Node>();var edges=JSONUtil.parseObj(record.getFlowJson()).getJSONArray("edges");
            for(int index=0;index<frozen.size();index++) {
                var link=frozen.get(index);link.setParentTaskCode(codes.getLong(index));nodes.updateById(link);
                var name=StrUtil.blankToDefault(link.getTaskName(),link.getNodeId());
                var upstream=edges.stream().map(e->(JSONObject)e).filter(e->link.getNodeId().equals(e.getStr("target"))).map(e->e.getStr("source")).toList();
                var priority=references.stream().filter(ref->link.getNodeId().equals(ref.node().getStr("id"))).findFirst().orElseThrow().node().getStr("priority","MEDIUM");
                steps.add(new WorkflowDefinitionParameters.Node(link.getNodeId(),StrUtil.sub(name,0,100),"SUB_WORKFLOW",Map.of("workflowDefinitionCode",link.getDsDefinitionCode(),"localParams",List.of(),"resourceList",List.of()),upstream,priority));
            }
            var definition=WorkflowDefinitionParameters.build("dh_job_run_"+record.getId(),"DataHub 作业实例 "+record.getId(),steps,codes.toList(Long.class),new WorkflowDefinitionParameters.Execution("MEDIUM",target.worker(),target.environment(),0,1,0,"PARALLEL"));
            var data=checked(ds.workflowDefinition().createWorkflowDefinition(target.project(),definition)).getJSONObject("data");if(data==null||data.getLong("code")==null)throw exception(JOB_RUNTIME_FAILED);
            record.setDsDefinitionCode(data.getLong("code"));record.setSubmissionStage("PREPARED");persistFenced(record,leaseOwner);
            checked(ds.workflowDefinition().releaseWorkflowDefinition(target.project(),record.getDsDefinitionCode(),"ONLINE",null));
            // 每次实际触发前重验权限、冻结版本与领取令牌，抢占后的旧准备者不得触发。
            validateFrozen(record);record.setSubmissionStage("TRIGGER_SENT");record.setStartTime(now());
            record.setLogContent("已持久化触发意图，等待调度器确认真实父子实例");persistFenced(record,leaseOwner);sent=true;
            var ids=checked(ds.executor().triggerWorkflowDefinition(target.project(),new TriggerWorkflowDefinitionRequest().setWorkflowDefinitionCode(record.getDsDefinitionCode()).setScheduleTime("").setFailureStrategy("END").setWarningType("NONE").setExecType("START_PROCESS").setWorkflowInstancePriority("MEDIUM").setWorkerGroup(target.worker()).setTenantCode(target.tenant()).setEnvironmentCode(target.environment()).setStartParams(new JSONObject().set("datahub_job_request",record.getRequestId()).toString()).setDryRun(0))).getJSONArray("data");
            if(ids==null||ids.size()!=1||ids.getLong(0)<=0||ids.getLong(0)>Integer.MAX_VALUE)throw exception(JOB_RUNTIME_FAILED);
            acceptParentIdentity(record,ids.getInt(0));return record.getId();
        } catch(RuntimeException failure) {
            var failed=Wrappers.<JobRecordDO>lambdaUpdate().eq(JobRecordDO::getId,record.getId()).eq(JobRecordDO::getLeaseOwner,leaseOwner).notIn(JobRecordDO::getStatus,TERMINAL);
            if(sent)failed.eq(JobRecordDO::getSubmissionStage,"TRIGGER_SENT").isNull(JobRecordDO::getDsInstanceId)
                    .set(JobRecordDO::getStatus,"UNKNOWN").set(JobRecordDO::getLogContent,"触发结果未知，禁止重新投递；将按本次专属定义核对原实例");
            else {
                failed.in(JobRecordDO::getSubmissionStage,List.of("PREPARING","PREPARED")).set(JobRecordDO::getStatus,"FAILURE")
                        .set(JobRecordDO::getEndTime,now()).set(JobRecordDO::getLogContent,"作业准备未完成，业务父流程未触发");
                for(var link:links(record.getId()))if("DEVELOPMENT".equals(link.getKind())&&link.getPlatformRecordId()!=null)development.cancelPreparedJobNode(link.getPlatformRecordId());
            }
            records.update(null,failed);throw failure;
        }
    }

    /** 派发令牌仅控制准备者；失租约不会释放真实运行占位。 */
    private void fence(JobRecordDO record,String owner) {
        if(records.update(null,Wrappers.<JobRecordDO>lambdaUpdate().eq(JobRecordDO::getId,record.getId())
                .eq(JobRecordDO::getLeaseOwner,owner).gt(JobRecordDO::getLeaseUntil,now()).eq(JobRecordDO::getQueueState,"DISPATCHING")
                .eq(JobRecordDO::getStopRequested,false).notIn(JobRecordDO::getStatus,TERMINAL)
                .set(JobRecordDO::getLeaseUntil,now().plusMinutes(5)))!=1)throw exception(JOB_CONFLICT);
    }

    private void persistFenced(JobRecordDO record,String owner) {
        if(records.update(runtimeFields(record),Wrappers.<JobRecordDO>lambdaUpdate().eq(JobRecordDO::getId,record.getId())
                .eq(JobRecordDO::getLeaseOwner,owner).gt(JobRecordDO::getLeaseUntil,now()).eq(JobRecordDO::getQueueState,"DISPATCHING")
                .eq(JobRecordDO::getStopRequested,false).notIn(JobRecordDO::getStatus,TERMINAL))!=1)throw exception(JOB_CONFLICT);
    }

    /** 运行观察不能以过期对象覆盖队列抢占、租约或串行占位。 */
    private static JobRecordDO runtimeFields(JobRecordDO source) {
        var result=new JobRecordDO();result.setId(source.getId());result.setStatus(source.getStatus());result.setDsProjectCode(source.getDsProjectCode());
        result.setDsDefinitionCode(source.getDsDefinitionCode());result.setDsInstanceId(source.getDsInstanceId());result.setSubmissionStage(source.getSubmissionStage());
        result.setStartTime(source.getStartTime());result.setEndTime(source.getEndTime());result.setDuration(source.getDuration());
        result.setLogContent(source.getLogContent());result.setChildrenJson(source.getChildrenJson());result.setUpdater(source.getExecutionUserId()==null?"job-coordinator":String.valueOf(source.getExecutionUserId()));result.setUpdateTime(now());return result;
    }

    /** 触发响应和恢复只绑定身份；已被观察者写入的终态不能被迟到响应覆盖。 */
    private void acceptParentIdentity(JobRecordDO record,Integer instanceId) {
        records.update(null,Wrappers.<JobRecordDO>lambdaUpdate().eq(JobRecordDO::getId,record.getId())
                .eq(JobRecordDO::getSubmissionStage,"TRIGGER_SENT").notIn(JobRecordDO::getStatus,TERMINAL)
                .and(q->q.isNull(JobRecordDO::getDsInstanceId).or().eq(JobRecordDO::getDsInstanceId,instanceId))
                .set(JobRecordDO::getDsInstanceId,instanceId).set(JobRecordDO::getSubmissionStage,"BOUND")
                .set(JobRecordDO::getStatus,"WAITING"));
        var actual=records.selectById(record.getId());
        if(actual==null||!Objects.equals(actual.getDsInstanceId(),instanceId)
                ||!Objects.equals(actual.getDsDefinitionCode(),record.getDsDefinitionCode())
                ||!Objects.equals(actual.getDsProjectCode(),record.getDsProjectCode()))throw exception(JOB_CONFLICT);
        record.setDsInstanceId(actual.getDsInstanceId());record.setSubmissionStage(actual.getSubmissionStage());
        record.setStatus(actual.getStatus());record.setEndTime(actual.getEndTime());record.setDuration(actual.getDuration());
        record.setChildrenJson(actual.getChildrenJson());record.setLogContent(actual.getLogContent());
    }

    /** 最多一次父列表及每个已冻结节点的有界身份核对，不依据任务名称或最近记录认领。 */
    public void reconcile(JobRecordDO record) {
        if(record.getDsDefinitionCode()==null)return;
        if(record.getDsInstanceId()==null) {
            if(!"TRIGGER_SENT".equals(record.getSubmissionStage()))return;
            var data=checked(ds.workflowInstance().queryWorkflowInstanceList(record.getDsProjectCode(),new QueryWorkflowInstanceListRequest().setWorkflowDefinitionCode(record.getDsDefinitionCode()).setPageNo(1).setPageSize(2))).getJSONObject("data");var list=data==null?null:data.getJSONArray("totalList");if(list==null||list.size()!=1)return;
            var candidate=list.getJSONObject(0);verifyWorkflow(candidate,record.getDsProjectCode(),record.getDsDefinitionCode(),candidate.getInt("id"));acceptParentIdentity(record,candidate.getInt("id"));if(TERMINAL.contains(record.getStatus()))return;
        }
        var parent=checked(ds.workflowInstance().queryWorkflowInstanceById(record.getDsProjectCode(),record.getDsInstanceId())).getJSONObject("data");verifyWorkflow(parent,record.getDsProjectCode(),record.getDsDefinitionCode(),record.getDsInstanceId());
        var result=checked(ds.workflowInstance().queryTaskListByWorkflowInstanceId(record.getDsProjectCode(),record.getDsInstanceId())).getJSONObject("data");var tasks=result==null?null:result.getJSONArray("taskList");
        if(tasks==null)throw exception(JOB_RUNTIME_FAILED);
        var children=new ArrayList<InstanceResp>();boolean failed=false,pending=false,stopped=false,recoveredParent=false;
        for(var link:links(record.getId())) {
            var matches=tasks.stream().map(t->(JSONObject)t).filter(t->Objects.equals(t.getLong("taskCode"),link.getParentTaskCode())).toList();
            if(matches.size()>1)throw exception(JOB_CONFIG_INVALID,"父任务实例编码出现重复，拒绝猜测子实例");
            JSONObject child=null;
            if(matches.size()==1) {
                var task=matches.getFirst();Integer taskId=task.getInt("id");
                if(taskId==null||!"SUB_WORKFLOW".equals(task.getStr("taskType"))||!Objects.equals(task.getInt("workflowInstanceId"),record.getDsInstanceId())||!Objects.equals(task.getLong("projectCode"),record.getDsProjectCode()))throw exception(JOB_CONFIG_INVALID,"父任务实例身份与冻结节点不一致");
                Integer childId=findSubInstance(record.getDsProjectCode(),taskId);
                if(childId!=null&&childId>0) {
                    child=checked(ds.workflowInstance().queryWorkflowInstanceById(link.getDsProjectCode(),childId)).getJSONObject("data");verifyWorkflow(child,link.getDsProjectCode(),link.getDsDefinitionCode(),childId);
                    boolean recovered=verifyParent(record,link,task,childId);recoveredParent|=recovered;
                    // 恢复时保留最初双向核验的父任务锚点，以便下一次对账再次验证。
                    if(!recovered)link.setParentTaskInstanceId(taskId);
                    if(link.getDsInstanceId()!=null&&!Objects.equals(link.getDsInstanceId(),childId))throw exception(JOB_CONFIG_INVALID,"冻结节点被关联到另一子实例");link.setDsInstanceId(childId);
                }
            }
            InstanceResp visible;
            if("DEVELOPMENT".equals(link.getKind())) {
                if (child == null && Set.of("FAILURE","STOPPED").contains(status(parent.getStr("state")))) development.cancelPreparedJobNode(link.getPlatformRecordId());
                var actual=child==null?development.observeJobNode(link.getPlatformRecordId()):development.bindJobNode(link.getPlatformRecordId(),link.getDsProjectCode(),link.getDsDefinitionCode(),link.getDsInstanceId());
                visible=new InstanceResp();visible.setId(actual.getId());visible.setOrigin("PLATFORM");visible.setKind("DEVELOPMENT");visible.setTaskId(actual.getTaskId());visible.setTaskName(actual.getTaskName());visible.setInstanceName(actual.getTaskName()+"-"+actual.getId());visible.setStartTime(actual.getStartTime());visible.setEndTime(actual.getEndTime());visible.setDuration(actual.getDurationMs()==null?null:actual.getDurationMs()/1000);visible.setStatus(devStatus(actual.getStatus(),actual.getQualityStatus()));
            } else {
                visible=integrationChild(link,child,record.getExecutionUserId());
            }
            if (visible == null) {
                if (Set.of("FAILURE","STOPPED").contains(status(parent.getStr("state")))) {
                    link.setStatus("SKIPPED");nodes.updateById(link);
                } else {link.setStatus("WAITING");nodes.updateById(link);pending=true;}
                continue;
            }
            link.setStatus(visible.getStatus());nodes.update(link,Wrappers.<JobNodeRunDO>lambdaUpdate().eq(JobNodeRunDO::getId,link.getId()).and(q->q.notIn(JobNodeRunDO::getStatus,TERMINAL).or().eq(JobNodeRunDO::getStatus,link.getStatus())));children.add(visible);failed|="FAILURE".equals(visible.getStatus());stopped|="STOPPED".equals(visible.getStatus());pending|=!TERMINAL.contains(visible.getStatus());
        }
        String actualParent=status(parent.getStr("state"));
        record.setStatus(pending?(TERMINAL.contains(actualParent)?"UNKNOWN":actualParent):failed?"FAILURE":stopped?"STOPPED":actualParent);
        if("SUCCESS".equals(record.getStatus())&&children.stream().anyMatch(c->!"SUCCESS".equals(c.getStatus())))record.setStatus("UNKNOWN");
        record.setChildrenJson(JsonUtils.toJsonString(children));record.setStartTime(time(parent.get("startTime"),record.getStartTime()));
        if(TERMINAL.contains(record.getStatus())){record.setEndTime(time(parent.get("endTime"),now()));record.setDuration(record.getStartTime()==null?null:Math.max(0,Duration.between(record.getStartTime(),record.getEndTime()).toSeconds()));}
        record.setLogContent("真实父实例 "+record.getDsInstanceId()+"；DS 状态 "+parent.getStr("state")+"；平台状态 "+record.getStatus()+"；节点日志通过各自真实记录身份读取"+(recoveredParent?"；DS 恢复产生重复反查关联，已核对恢复前后父任务均指向原双向验证的同一子实例":""));records.update(runtimeFields(record),Wrappers.<JobRecordDO>lambdaUpdate().eq(JobRecordDO::getId,record.getId()).and(q->q.notIn(JobRecordDO::getStatus,TERMINAL).or().eq(JobRecordDO::getStatus,record.getStatus())));
    }

    /** DS 故障恢复可能保留两个父任务关联；仅对已经持久双向核验的同一子实例恢复对账。 */
    private boolean verifyParent(JobRecordDO record,JobNodeRunDO link,JSONObject task,Integer childId) {
        JSONObject response;
        try { response=ds.workflowInstance().queryParentInstanceBySubId(link.getDsProjectCode(),childId); }
        catch(cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerException failure) {
            if(!isDuplicateParentLookup(failure.getCode(),failure.getMessage())||!verifiedRecoveryAnchor(link,task,childId))throw failure;
            return true;
        }
        if(response!=null&&isDuplicateParentLookup(response.getInt("code"),response.getStr("msg"))&&verifiedRecoveryAnchor(link,task,childId))return true;
        var reverse=checked(response).getJSONObject("data");Integer actualParent=reverse==null?null:reverse.getInt("parentWorkflowInstance");
        if(!Objects.equals(actualParent,record.getDsInstanceId()))throw exception(JOB_CONFIG_INVALID,"子实例反向父关系不匹配");
        return false;
    }
    private static boolean isDuplicateParentLookup(Integer code,String message) {
        return Integer.valueOf(10119).equals(code)&&message!=null&&message.contains("org.apache.ibatis.exceptions.TooManyResultsException");
    }
    private boolean verifiedRecoveryAnchor(JobNodeRunDO link,JSONObject currentTask,Integer childId) {
        return Objects.equals(link.getDsInstanceId(),childId)&&link.getParentTaskInstanceId()!=null
                &&!Objects.equals(link.getParentTaskInstanceId(),currentTask.getInt("id"))&&"YES".equals(currentTask.getStr("flag"))
                &&Objects.equals(findSubInstance(link.getDsProjectCode(),link.getParentTaskInstanceId()),childId);
    }

    private InstanceResp integrationChild(JobNodeRunDO link, JSONObject child, Long actor) {
        IntegrationRecordDO actual=null;
        if(link.getPlatformRecordId()!=null)actual=integrationRecords.selectById(link.getPlatformRecordId());
        if(child!=null) {
            if(actual==null)actual=integrationRecords.selectOne(Wrappers.<IntegrationRecordDO>lambdaQuery().eq(IntegrationRecordDO::getDsProjectCode,link.getDsProjectCode()).eq(IntegrationRecordDO::getDsInstanceId,link.getDsInstanceId()));
            if(actual==null){actual=new IntegrationRecordDO();actual.setTaskId(link.getTaskId());actual.setTriggerType("JOB");actual.setDsProjectCode(link.getDsProjectCode());actual.setDsInstanceId(link.getDsInstanceId());actual.setCreator(String.valueOf(actor));actual.setUpdater(String.valueOf(actor));actual.setCreateTime(now());actual.setUpdateTime(now());}
            if(!Objects.equals(actual.getTaskId(),link.getTaskId()))throw exception(JOB_CONFIG_INVALID,"集成实例被关联到另一任务");
            actual.setInstanceName(child.getStr("name"));actual.setStatus(status(child.getStr("state")));actual.setStartTime(time(child.get("startTime"),null));actual.setEndTime(time(child.get("endTime"),null));actual.setMessage("作业子实例，已通过稳定父任务编码与双向父子关系核验");if(actual.getStartTime()!=null&&actual.getEndTime()!=null)actual.setDuration(Math.max(0,Duration.between(actual.getStartTime(),actual.getEndTime()).toSeconds()));
            try{if(actual.getId()==null)integrationRecords.insert(actual);else {
                String observedStatus=actual.getStatus();
                int updated=integrationRecords.update(actual,Wrappers.<IntegrationRecordDO>lambdaUpdate().eq(IntegrationRecordDO::getId,actual.getId()).and(q->q.notIn(IntegrationRecordDO::getStatus,List.of("SUCCESS","FAILURE","STOPPED","IDLE")).or().eq(IntegrationRecordDO::getStatus,observedStatus)
                        .or("STOPPED".equals(observedStatus),nested->nested.eq(IntegrationRecordDO::getStatus,"IDLE"))));
                if(updated==0)actual=integrationRecords.selectById(actual.getId());
            }}catch(DuplicateKeyException race){actual=integrationRecords.selectOne(Wrappers.<IntegrationRecordDO>lambdaQuery().eq(IntegrationRecordDO::getDsProjectCode,link.getDsProjectCode()).eq(IntegrationRecordDO::getDsInstanceId,link.getDsInstanceId()));}
            integrationRecords.update(null,Wrappers.<IntegrationRecordDO>lambdaUpdate().eq(IntegrationRecordDO::getId,actual.getId())
                    .eq(IntegrationRecordDO::getTaskId,link.getTaskId()).eq(IntegrationRecordDO::getDsProjectCode,link.getDsProjectCode())
                    .eq(IntegrationRecordDO::getDsInstanceId,link.getDsInstanceId()).set(IntegrationRecordDO::getTriggerType,"JOB"));
            link.setPlatformRecordId(actual.getId());
        }
        if (actual == null) return null;
        var visible=new InstanceResp();visible.setId(actual.getId());visible.setOrigin("MANAGED");visible.setKind("INTEGRATION");visible.setTaskId(link.getTaskId());
        visible.setTaskName(StrUtil.blankToDefault(link.getTaskName(),"集成任务 "+link.getTaskId()));visible.setInstanceName(actual==null?"等待父任务创建真实子实例":actual.getInstanceName());visible.setStatus(actual==null?"WAITING":actual.getStatus());if(actual!=null){visible.setStartTime(actual.getStartTime());visible.setEndTime(actual.getEndTime());visible.setDuration(actual.getDuration());}return visible;
    }

    /** 父日志汇总真实子记录日志；开发分支保留原有身份、原生项目及脱敏门禁。 */
    public String log(Long recordId) {
        var record=records.selectById(recordId);if(record==null||!"PLATFORM".equals(record.getOrigin()))throw exception(DEV_RECORD_NOT_EXISTS);
        var out=new StringBuilder("平台作业审计（不代表 Worker 输出）\n触发方式："+record.getExecutionType()+"；业务周期槽："+record.getScheduledTime()
                +"\n队列状态："+record.getQueueState()+"；运行状态："+record.getStatus()+"\n当前决策："+StrUtil.nullToEmpty(record.getQueueReason())
                +"\n控制摘要："+StrUtil.nullToEmpty(record.getLogContent()));
        for(var link:links(recordId)) {
            out.append("\n\n========== ").append(link.getKind()).append(" 节点 ").append(link.getNodeId()).append(" ==========");
            if(link.getPlatformRecordId()==null){out.append("SKIPPED".equals(link.getStatus())?"\n上游失败或父流程已停止，节点未执行；没有真实子实例与 Worker 日志":"\n子实例尚未确认，不提供其他任务的日志");continue;}
            String text="DEVELOPMENT".equals(link.getKind())?developmentRecords.downloadTaskLog(link.getPlatformRecordId()):integrationLog(link.getPlatformRecordId());
            int remaining=MAX_LOG-out.length();if(remaining<=0){out.append("\n[聚合日志达到上限]");break;}out.append('\n').append(StrUtil.sub(text,0,remaining));
        }
        return out.toString();
    }

    public String integrationLog(Long recordId) {
        var record=integrationRecords.selectById(recordId);if(record==null||record.getDsProjectCode()==null||record.getDsInstanceId()==null)throw exception(DEV_RECORD_NOT_EXISTS);
        var data=checked(ds.workflowInstance().queryTaskListByWorkflowInstanceId(record.getDsProjectCode(),record.getDsInstanceId())).getJSONObject("data");var tasks=data==null?null:data.getJSONArray("taskList");if(tasks==null)return "调度器尚未生成节点日志";
        var out=new StringBuilder();
        for(Object raw:tasks.stream().limit(200).toList()) {
            var task=(JSONObject)raw;Integer id=task.getInt("id");if(id==null||!Objects.equals(task.getInt("workflowInstanceId"),record.getDsInstanceId())||!Objects.equals(task.getLong("projectCode"),record.getDsProjectCode()))throw exception(DEV_RECORD_NOT_EXISTS);
            out.append("\n--- ").append(task.getStr("name","任务节点")).append(" ---\n");int cursor=0;
            for(int page=0;page<20&&out.length()<MAX_LOG;page++){var log=ds.log().queryLogPage(id,cursor,1000);out.append(StrUtil.sub(log.message(),0,MAX_LOG-out.length()));if(!log.mayHaveMore()||log.nextSkipLineNum()<=cursor)break;cursor=log.nextSkipLineNum();}
            if(out.length()>=MAX_LOG){out.append("\n[日志达到上限]");break;}
        }
        return out.toString();
    }

    public void stop(JobRecordDO record) {
        for(var link:links(record.getId())) {
            if("DEVELOPMENT".equals(link.getKind())&&link.getPlatformRecordId()!=null) {
                if (!security.hasPermission("dataforge:dev-task:run")) throw exception(OPERATION_PERMISSION_DENIED);
                developmentRecords.getRecord(link.getPlatformRecordId());
            }
            else if(!security.hasPermission("dataforge:integration:run"))throw exception(OPERATION_PERMISSION_DENIED);
        }
        for(var link:links(record.getId()))if("DEVELOPMENT".equals(link.getKind())&&link.getPlatformRecordId()!=null) {
            var child=developmentRecords.getRecord(link.getPlatformRecordId());
            if(cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevRecordDO.ACTIVE_STATUSES.contains(child.getStatus())) {
                try { developmentRecords.stopRecord(link.getPlatformRecordId()); }
                catch(cn.iocoder.yudao.framework.common.exception.ServiceException race) {
                    if(!Objects.equals(race.getCode(),DEV_RECORD_NOT_RUNNING.getCode()))throw race;
                }
            }
        }
        if(record.getDsInstanceId()==null)throw exception(JOB_CONFIG_INVALID,"父流程触发结果尚未确认，请先核对原实例");
        checked(ds.executor().controlWorkflowInstance(record.getDsProjectCode(),record.getDsInstanceId(),"STOP"));
    }
    /** 公开停止和优先抢占先以发起人预检旧版本，不能借用原运行执行人的授权。 */
    public void authorizeStopRequest(JobRecordDO record,Long caller) {
        executionAccess.requireUserPermission(caller,"dataforge:job:run");
        if(StrUtil.isNotBlank(record.getRunSnapshotJson())) {
            for(Object raw:JSONUtil.parseObj(record.getRunSnapshotJson()).getJSONArray("bindings")) {
                var binding=(JSONObject)raw;
                if("DEVELOPMENT".equals(binding.getStr("kind")))development.authorizeJobTaskStop(binding.getLong("taskId"),binding.getLong("version"),caller);
                else executionAccess.requireUserPermission(caller,"dataforge:integration:run");
            }
        } else for(var link:links(record.getId())) {
            if("DEVELOPMENT".equals(link.getKind()))development.authorizeJobTaskStop(link.getTaskId(),link.getTaskVersion(),caller);
            else executionAccess.requireUserPermission(caller,"dataforge:integration:run");
        }
        for(var link:links(record.getId()))if("DEVELOPMENT".equals(link.getKind())&&link.getPlatformRecordId()!=null)development.authorizeJobNodeForCaller(link.getPlatformRecordId(),caller);
    }

    public void stopForActor(JobRecordDO record) {
        var actor=record.getExecutionUserId();executionAccess.requireUserPermission(actor,"dataforge:job:run");
        var links=links(record.getId());
        for(var link:links)if("DEVELOPMENT".equals(link.getKind())) {
            if(link.getPlatformRecordId()!=null)development.authorizeJobNode(link.getPlatformRecordId(),actor);
            else development.authorizeJobTaskStop(link.getTaskId(),link.getTaskVersion(),actor);
        } else executionAccess.requireUserPermission(actor,"dataforge:integration:run");
        for(var link:links)if("DEVELOPMENT".equals(link.getKind())&&link.getPlatformRecordId()!=null)development.stopJobNode(link.getPlatformRecordId(),actor);
        if(record.getDsInstanceId()!=null)checked(ds.executor().controlWorkflowInstance(record.getDsProjectCode(),record.getDsInstanceId(),"STOP"));
    }

    public List<JobNodeRunDO> links(Long recordId){return nodes.selectList(Wrappers.<JobNodeRunDO>lambdaQuery().eq(JobNodeRunDO::getJobRecordId,recordId).orderByAsc(JobNodeRunDO::getId));}
    public boolean owned(JobRecordDO record){return record.getDsDefinitionCode()!=null&&record.getSubmissionStage()!=null;}
    /** DS 3.4.2 的明确“子实例不存在”与网络失败分开处理，缺少证据不能当未执行。 */
    private Integer findSubInstance(Long project,Integer parentTaskId) {
        JSONObject response;
        try { response=ds.workflowInstance().querySubWorkflowInstanceByTaskId(project,parentTaskId,null); }
        catch(cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerException failure) {
            if(Integer.valueOf(50007).equals(failure.getCode()))return null;
            throw failure;
        }
        if(response!=null&&Integer.valueOf(50007).equals(response.getInt("code")))return null;
        var data=checked(response).getJSONObject("data");return data==null?null:data.getInt("subWorkflowInstanceId");
    }
    private static void verifyWorkflow(JSONObject data,Long project,Long definition,Integer instance){if(data==null||instance==null||instance<=0||!Objects.equals(data.getInt("id"),instance)||!Objects.equals(data.getLong("projectCode"),project)||!Objects.equals(data.getLong("workflowDefinitionCode"),definition))throw exception(JOB_CONFIG_INVALID,"调度器工作流身份与冻结记录不一致");}
    private static JSONObject checked(JSONObject data){if(data==null||data.getInt("code",-1)!=0)throw exception(JOB_RUNTIME_FAILED);return data;}
    private static String targetJson(Target t){return new JSONObject().set("project",t.project()).set("worker",t.worker()).set("tenant",t.tenant()).set("environment",t.environment()).toString();}
    private static String devStatus(String status,String quality){if("FAILED".equals(status))return "FAILURE";if("SUCCESS".equals(status)&&!Set.of("PASSED","NOT_CONFIGURED").contains(String.valueOf(quality)))return Set.of("FAILED","FAILURE","REJECTED").contains(String.valueOf(quality))?"FAILURE":"UNKNOWN";return switch(String.valueOf(status)){case "WAITING","SUBMITTING"->"WAITING";case "CANCEL_REQUESTED"->"RUNNING";case "SUCCESS","STOPPED","RUNNING"->status;default->"UNKNOWN";};}
    private static String status(String state){return switch(String.valueOf(state)){case "SUCCESS","FORCED_SUCCESS"->"SUCCESS";case "FAILURE","FAILED"->"FAILURE";case "STOP","KILL","PAUSE"->"STOPPED";case "RUNNING_EXECUTION"->"RUNNING";case "SUBMITTED_SUCCESS","DISPATCH","DELAY_EXECUTION","SERIAL_WAIT"->"WAITING";default->"UNKNOWN";};}
    private static LocalDateTime time(Object value,LocalDateTime fallback){try{if(value instanceof Number n)return LocalDateTime.ofInstant(Instant.ofEpochMilli(n.longValue()),ZoneId.of("Asia/Shanghai"));if(value instanceof String text)return LocalDateTime.parse(text.replace('T',' ').substring(0,19),TIME);}catch(RuntimeException ignored){ }return fallback;}
    private static LocalDateTime now(){return LocalDateTime.now(ZoneId.of("Asia/Shanghai"));}
}

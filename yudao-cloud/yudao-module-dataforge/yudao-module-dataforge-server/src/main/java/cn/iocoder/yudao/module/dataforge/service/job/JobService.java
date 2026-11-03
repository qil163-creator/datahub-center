package cn.iocoder.yudao.module.dataforge.service.job;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.*;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerClient;
import cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinCronSchedule;
import cn.iocoder.yudao.framework.dolphinscheduler.model.WorkflowDefinitionParameters;
import cn.iocoder.yudao.framework.dolphinscheduler.request.*;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.module.dataforge.controller.admin.job.vo.JobModels.*;
import cn.iocoder.yudao.module.dataforge.controller.admin.integration.vo.IntegrationModels.OwnerResp;
import cn.iocoder.yudao.module.dataforge.controller.admin.operations.vo.OpsInstanceModels.InstanceResp;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.job.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.job.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.integration.IntegrationTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevTaskMapper;
import cn.iocoder.yudao.module.dataforge.service.integration.IntegrationTaskService;
import cn.iocoder.yudao.module.dataforge.service.integration.IntegrationRuntimeProperties;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** 作业编排领域服务。平台共享元数据，运行复用受管 DS 子流程且不绕过开发任务执行身份。 */
@Service
public class JobService {
    @Resource private JobTaskMapper jobs;
    @Resource private JobCategoryMapper categories;
    @Resource private JobRecordMapper records;
    @Resource private IntegrationTaskMapper integrations;
    @Resource private DevTaskMapper developments;
    @Resource private IntegrationTaskService integrationService;
    @Resource private IntegrationRuntimeProperties runtime;
    @Resource private DolphinSchedulerClient ds;
    @Resource private SecurityFrameworkService security;
    @Resource private JobManagedRunService managedRuns;
    @Resource private JobQueueCoordinator queue;
    @Resource private org.springframework.transaction.support.TransactionTemplate transaction;
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> ACTIVE=Set.of("WAITING","RUNNING","UNKNOWN");

    public PageResult<TaskResp> page(PageReq req) {
        var reconciled=reconcileRecentForRead();
        var query=new LambdaQueryWrapperX<JobTaskDO>().likeIfPresent(JobTaskDO::getName,req.getName())
                .inIfPresent(JobTaskDO::getCategoryId,categoryScope(req.getCategoryId()))
                .eqIfPresent(JobTaskDO::getOwnerId,req.getOwnerId()).eqIfPresent(JobTaskDO::getStatus,req.getStatus())
                .orderByDesc(JobTaskDO::getId);
        if(req.getRunStatus()!=null) query.apply("EXISTS(SELECT 1 FROM dh_dataforge_job_record r WHERE r.job_id=dh_dataforge_job.id AND r.deleted=0 AND r.status={0} AND r.id=(SELECT max(r2.id) FROM dh_dataforge_job_record r2 WHERE r2.job_id=r.job_id AND r2.deleted=0))",req.getRunStatus());
        var page=jobs.selectPage(req,query);
        var selected=page.getList().stream().map(JobTaskDO::getId).filter(id->!reconciled.contains(id)).limit(10).toList();
        selected.forEach(this::reconcileForRead);
        if(req.getRunStatus()!=null&&!selected.isEmpty())page=jobs.selectPage(req,query);
        return new PageResult<>(page.getList().stream().map(task->response(require(task.getId()))).toList(),page.getTotal());
    }
    public TaskResp get(Long id) { refresh(id); return response(require(id)); }
    public List<CategoryResp> categories() { return categories.selectList(Wrappers.<JobCategoryDO>lambdaQuery().orderByAsc(JobCategoryDO::getSort,JobCategoryDO::getId)).stream().map(c->BeanUtils.toBean(c,CategoryResp.class)).toList(); }
    public List<OwnerResp> owners() { return integrationService.owners(); }
    @Transactional(rollbackFor=Exception.class)
    public Long create(SaveReq req) {
        if(req.getId()!=null) throw exception(JOB_CONFIG_INVALID,"新增作业不能指定编号");
        categories.lockTree(); var job=prepare(req); job.setRevision(0);job.setStatus(0);job.setScheduleStatus(0);job.setOperationState("IDLE");
        try { jobs.insert(job); } catch(DuplicateKeyException e) { throw exception(JOB_CONFLICT); } return job.getId();
    }
    @Transactional(rollbackFor=Exception.class)
    public void update(SaveReq req) {
        categories.lockTree(); var old=require(req.getId()); requireEditable(old); var changed=prepare(req);
        changed.setRevision(old.getRevision()+1); changed.setDsDefinitionCode(null);
        if(!Objects.equals(req.getRevision(),old.getRevision())||jobs.update(changed,Wrappers.<JobTaskDO>lambdaUpdate().eq(JobTaskDO::getId,old.getId()).eq(JobTaskDO::getRevision,old.getRevision()).eq(JobTaskDO::getOperationState,"IDLE"))!=1) throw exception(JOB_CONFLICT);
        jobs.update(null,Wrappers.<JobTaskDO>lambdaUpdate().eq(JobTaskDO::getId,old.getId()).set(JobTaskDO::getDsDefinitionCode,null).set(JobTaskDO::getDsProjectCode,null).set(JobTaskDO::getDsScheduleId,null));
    }
    @Transactional(rollbackFor=Exception.class)
    public Long cloneTask(Long id) {
        var old=require(id); var copy=BeanUtils.toBean(old,SaveReq.class); copy.setId(null);copy.setRevision(null);
        copy.setName(old.getName().substring(0,Math.min(175,old.getName().length()))+"_副本_"+UUID.randomUUID().toString().substring(0,8)); return create(copy);
    }
    @Transactional(rollbackFor=Exception.class)
    public void delete(Long id,Integer revision) {
        categories.lockTree();var job=require(id);requireEditable(job);
        if(!Objects.equals(revision,job.getRevision())||jobs.update(null,Wrappers.<JobTaskDO>lambdaUpdate().eq(JobTaskDO::getId,id).eq(JobTaskDO::getRevision,revision).eq(JobTaskDO::getOperationState,"IDLE").set(JobTaskDO::getDeleted,1))!=1) throw exception(JOB_CONFLICT);
    }
    public void status(StatusReq req) {
        var observed=require(req.getId());
        if(req.getStatus()==1){var check=checkFlow(observed.getFlowJson());if(!check.isValid()||JSONUtil.parseObj(observed.getFlowJson()).getJSONArray("nodes").isEmpty())throw exception(JOB_CONFIG_INVALID,"作业流程不能为空且必须通过结构校验");}
        transaction.executeWithoutResult(status->{
            var job=jobs.selectForUpdate(req.getId());if(job==null)throw exception(JOB_NOT_EXISTS);
            if(!Objects.equals(job.getRevision(),req.getRevision()))throw exception(JOB_CONFLICT);
            if(job.getScheduleStatus()==1||!active(req.getId()).isEmpty())throw exception(JOB_STATE_INVALID);
            if(jobs.update(null,Wrappers.<JobTaskDO>lambdaUpdate().eq(JobTaskDO::getId,req.getId()).eq(JobTaskDO::getRevision,req.getRevision()).set(JobTaskDO::getStatus,req.getStatus()).set(JobTaskDO::getRevision,req.getRevision()+1).set(JobTaskDO::getOperationState,"IDLE"))!=1)throw exception(JOB_CONFLICT);
        });
    }
    @Transactional(rollbackFor=Exception.class)
    public Long category(CategoryReq req,boolean update) {
        categories.lockTree(); var all=categories();
        if(update && all.stream().noneMatch(c->Objects.equals(c.getId(),req.getId()))) throw exception(JOB_CONFIG_INVALID,"作业类目不存在");
        if(req.getParentId()!=0 && all.stream().noneMatch(c->Objects.equals(c.getId(),req.getParentId()))) throw exception(JOB_CONFIG_INVALID,"父类目不存在");
        if(update&&categoryScope(req.getId()).contains(req.getParentId())) throw exception(JOB_CONFIG_INVALID,"不能将自身或子类目作为父类目");
        var value=BeanUtils.toBean(req,JobCategoryDO.class);value.setName(req.getName().trim());
        if(update) categories.updateById(value); else {value.setId(null);categories.insert(value);} return value.getId();
    }
    @Transactional(rollbackFor=Exception.class)
    public void deleteCategory(Long id) {
        categories.lockTree();
        if(categories.selectCount(Wrappers.<JobCategoryDO>lambdaQuery().eq(JobCategoryDO::getParentId,id))>0||jobs.selectCount(Wrappers.<JobTaskDO>lambdaQuery().eq(JobTaskDO::getCategoryId,id))>0) throw exception(JOB_CONFIG_INVALID,"类目下存在子类目或作业");
        if(categories.deleteById(id)!=1) throw exception(JOB_CONFIG_INVALID,"作业类目不存在");
    }
    public List<TaskOption> taskOptions() {
        var result=new ArrayList<TaskOption>();
        result.addAll(jobs.integrationOptions(runtime.getDsProjectCode()));
        result.addAll(jobs.developmentOptions());
        return result;
    }
    public CheckResp checkFlow(String json) { return checkFlow(json, true); }

    /** 列表与草稿保存仅校验结构；完整权限和运行目标在显式检查、冻结及提交时复核。 */
    private CheckResp checkFlow(String json, boolean inspectRuntime) {
        var result=new CheckResp();var errors=new ArrayList<String>();result.setErrors(errors);
        JSONObject flow;
        try {flow=JSONUtil.parseObj(json);} catch(RuntimeException e) {errors.add("流程必须为 JSON 对象");return result;}
        if(!Set.of("nodes","edges").containsAll(flow.keySet())) {errors.add("流程包含未支持的顶层字段");return result;}
        JSONArray nodes=flow.getJSONArray("nodes"),edges=flow.getJSONArray("edges");
        if(nodes==null||edges==null||nodes.size()>200||edges.size()>1000) {errors.add("流程必须包含 nodes/edges，最多200节点和1000连线");return result;}
        var ids=new LinkedHashSet<String>();var links=new HashMap<String,List<String>>();var options=new HashMap<String,TaskOption>();
        for(var o:taskOptions()) options.put(o.getKind()+":"+o.getId(),o);
        boolean executable=!nodes.isEmpty();var executionErrors=new ArrayList<String>();
        for(Object raw:nodes) {
            if(!(raw instanceof JSONObject n)||!Set.of("id","kind","taskId","name","x","y","status","description","taskType","dbType","priority").containsAll(n.keySet())) {errors.add("节点字段无效");continue;}
            if(n.containsKey("priority")&&!Set.of("HIGHEST","HIGH","MEDIUM","LOW","LOWEST").contains(String.valueOf(n.get("priority")))) errors.add("节点任务优先级无效");
            String id=n.getStr("id"),kind=n.getStr("kind"),taskId=n.getStr("taskId");
            if(id==null||!id.matches("[A-Za-z0-9_-]{1,80}")||"_root".equals(id)||!ids.add(id)) {errors.add("节点编号缺失、重复或使用保留编号");continue;}
            links.put(id,new ArrayList<>());
            if(!Set.of("INTEGRATION","DEVELOPMENT").contains(String.valueOf(kind))||taskId==null||!taskId.matches("[1-9][0-9]{0,18}")) {errors.add("节点任务引用无效");continue;}
            var option=options.get(kind+":"+taskId);
            if(option==null) errors.add("节点引用的任务不存在："+id);
            else if(!option.isExecutable()) {executable=false;executionErrors.add("任务“"+option.getName()+"”的当前执行身份、保存版本或受管目标未就绪，请检查节点配置");}
            for(String coordinate:List.of("x","y")) {Object v=n.get(coordinate); if(v!=null) {try {double d=Double.parseDouble(v.toString());if(!Double.isFinite(d)||Math.abs(d)>100000) errors.add("节点坐标无效");}catch(NumberFormatException e){errors.add("节点坐标无效");}}}
        }
        var uniqueEdges=new HashSet<String>();
        for(Object raw:edges) {
            if(!(raw instanceof JSONObject e)||!Set.of("source","target").containsAll(e.keySet())) {errors.add("连线字段无效");continue;}
            String from=e.getStr("source"),to=e.getStr("target");
            if(!ids.contains(from)||!ids.contains(to)||Objects.equals(from,to)||!uniqueEdges.add(from+"→"+to)) errors.add("连线无效、重复或引用不存在的节点");
            else links.get(from).add(to);
        }
        var counts=new HashMap<String,Integer>();ids.forEach(id->counts.put(id,0));links.values().forEach(list->list.forEach(id->counts.computeIfPresent(id,(k,v)->v+1)));
        var queue=new ArrayDeque<String>();counts.forEach((k,v)->{if(v==0) queue.add(k);});int visited=0;
        while(!queue.isEmpty()){String id=queue.remove();visited++;for(String to:links.get(id)) if(counts.compute(to,(k,v)->v-1)==0) queue.add(to);}
        if(visited!=ids.size()) errors.add("作业流程不能存在循环依赖");
        result.setValid(errors.isEmpty());result.setExecutable(result.isValid()&&executable);
        if(result.isValid()) {
            if(nodes.isEmpty()) executionErrors.add("请先添加任务节点");
            if(inspectRuntime&&!nodes.isEmpty()) {
                try { managedRuns.validateForRead(json); }
                catch (cn.iocoder.yudao.framework.common.exception.ServiceException invalid) { result.setExecutable(false);executionErrors.add(invalid.getMessage()); }
                catch (RuntimeException unavailable) { result.setExecutable(false);executionErrors.add("受管运行预检暂不可用，请检查当前身份、保存版本与目标配置"); }
            }
            errors.addAll(executionErrors);
        }return result;
    }
    public List<String> cronPreview(String cron) {
        try{return DolphinCronSchedule.parse(cron).nextTimes(now(),ZoneId.of("Asia/Shanghai"),5).stream().map(TIME::format).toList();}
        catch(RuntimeException e){throw exception(JOB_CONFIG_INVALID,"Cron 表达式无效");}
    }
    public PageResult<InstanceResp> recordPage(RecordPageReq req) {
        require(req.getTaskId());refresh(req.getTaskId());var page=records.selectPage(req,Wrappers.<JobRecordDO>lambdaQuery().eq(JobRecordDO::getJobId,req.getTaskId()).orderByDesc(JobRecordDO::getId));
        var job=require(req.getTaskId());return new PageResult<>(page.getList().stream().map(r->instance(r,job)).toList(),page.getTotal());
    }
    public Long run(RunReq req) {
        var prior=records.selectOne(Wrappers.<JobRecordDO>lambdaQuery().eq(JobRecordDO::getRequestId,req.getRequestId()));
        if(prior!=null){if(!"PLATFORM".equals(prior.getOrigin())||!Objects.equals(prior.getJobId(),req.getId()))throw exception(JOB_CONFLICT);return prior.getId();}
        var job=require(req.getId());var actor=cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId();
        var check=checkFlow(job.getFlowJson());if(!check.isExecutable())throw exception(JOB_CONFIG_INVALID,String.join("；",check.getErrors()));
        var snapshot=managedRuns.freeze(job,actor,true);if(job.getScheduleSnapshotJson()==null)requireLegacyQuiescent(job,true);
        return queue.enqueueManual(job,req,actor,snapshot);
    }

    public void populateRuntimeIdentity(InstanceResp row) {
        if (!"JOB".equals(row.getKind()) || !"PLATFORM".equals(row.getOrigin())) return;
        var record=records.selectById(row.getId());
        if(record!=null&&Objects.equals(record.getJobId(),row.getTaskId()))fillRuntimeIdentity(row,record);
    }
    private static void fillRuntimeIdentity(InstanceResp row,JobRecordDO record) {
        row.setDsProjectCode(record.getDsProjectCode());row.setDsDefinitionCode(record.getDsDefinitionCode());row.setDsInstanceId(record.getDsInstanceId());row.setSubmissionStage(record.getSubmissionStage());row.setQueueState(record.getQueueState());row.setQueueReason(record.getQueueReason());row.setQueuedTime(record.getQueuedTime());row.setScheduledTime(record.getScheduledTime());
    }
    public String managedLog(Long recordId) { return managedRuns.log(recordId); }
    public String managedIntegrationLog(Long recordId) { return managedRuns.integrationLog(recordId); }
    public void stop(Long id) {require(id);queue.stop(id);}

    /** 平台周期逐槽重新核验冻结身份；旧 DS 业务周期必须先停用并确认。 */
    public void schedule(ScheduleReq req) {
        var job=require(req.getId());if(!Objects.equals(job.getRevision(),req.getRevision()))throw exception(JOB_CONFLICT);
        if(req.getScheduleStatus()==1)cronPreview(req.getCronExpression());
        Long actor=cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId();
        String snapshot=req.getScheduleStatus()==1?managedRuns.freeze(job,actor,true):null;
        offlineLegacySchedule(job);if(req.getScheduleStatus()==1)requireLegacyQuiescent(job,false);queue.configureSchedule(req,actor,snapshot);
    }

    private void offlineLegacySchedule(JobTaskDO job) {
        if(job.getDsDefinitionCode()==null)return;
        var data=checked(ds.schedule().queryScheduleListPaging(job.getDsProjectCode(),job.getDsDefinitionCode(),null,1,100)).getJSONObject("data");
        var schedules=data==null?null:data.getJSONArray("totalList");if(schedules==null||schedules.size()>1)throw exception(JOB_CONFIG_INVALID,"旧调度状态不可核验，未开启平台周期");
        for(Object raw:schedules){var schedule=(JSONObject)raw;if("ONLINE".equals(schedule.getStr("releaseState")))checked(ds.schedule().offlineSchedule(job.getDsProjectCode(),schedule.getInt("id")));}
        var verified=checked(ds.schedule().queryScheduleListPaging(job.getDsProjectCode(),job.getDsDefinitionCode(),null,1,100)).getJSONObject("data");
        var list=verified==null?null:verified.getJSONArray("totalList");if(list==null||list.stream().map(raw->(JSONObject)raw).anyMatch(item->!"OFFLINE".equals(item.getStr("releaseState"))))throw exception(JOB_CONFIG_INVALID,"旧 DS 周期尚未确认停用，禁止双重计时");
    }

    /** 无法有界证明旧执行链已静止时拒绝新链路，不能凭停用未来周期推断在途退出。 */
    private void requireLegacyQuiescent(JobTaskDO job,boolean checkSchedule) {
        if(job.getDsDefinitionCode()==null)return;
        if(checkSchedule) {
            var data=checked(ds.schedule().queryScheduleListPaging(job.getDsProjectCode(),job.getDsDefinitionCode(),null,1,100)).getJSONObject("data");
            var list=data==null?null:data.getJSONArray("totalList");
            if(list==null||list.size()>1||list.stream().map(raw->(JSONObject)raw).anyMatch(item->!"OFFLINE".equals(item.getStr("releaseState"))))throw exception(JOB_CONFIG_INVALID,"旧 DS 周期尚未停用，请先在周期配置中显式关闭或接管；本次未受理新运行");
        }
        var data=checked(ds.workflowInstance().queryWorkflowInstanceList(job.getDsProjectCode(),new QueryWorkflowInstanceListRequest().setWorkflowDefinitionCode(job.getDsDefinitionCode()).setPageNo(1).setPageSize(100))).getJSONObject("data");
        var list=data==null?null:data.getJSONArray("totalList");
        if(list==null||list.size()>=100||data.getLong("total",0L)>100L)throw exception(JOB_CONFIG_INVALID,"旧定义历史超出有界核对范围，尚不能证明在途实例已结束，请管理员核查");
        for(Object raw:list) {
            var instance=(JSONObject)raw;
            if(!Objects.equals(instance.getLong("workflowDefinitionCode"),job.getDsDefinitionCode())||!Objects.equals(instance.getLong("projectCode"),job.getDsProjectCode())
                    ||!Set.of("SUCCESS","FORCED_SUCCESS","FAILURE","STOP","KILL").contains(String.valueOf(instance.getStr("state"))))throw exception(JOB_CONFIG_INVALID,"旧定义仍有在途或身份未确认实例，请等待原父子运行结束后接管");
        }
    }

    private void requireExecutable(JobTaskDO job) {
        if(!security.hasPermission("dataforge:integration:run"))throw exception(OPERATION_PERMISSION_DENIED);
        var check=checkFlow(job.getFlowJson());if(!check.isExecutable())throw exception(JOB_CONFIG_INVALID,String.join("；",check.getErrors()));
        if(runtime.getDsProjectCode()==null||StrUtil.isBlank(runtime.getTenantCode())||!runtime.getWorkerGroups().contains("default"))throw exception(JOB_CONFIG_INVALID,"受管 DS 项目、执行用户或工作组尚未配置");
    }
    private void publish(JobTaskDO job) {
        var flow=JSONUtil.parseObj(job.getFlowJson());var nodes=flow.getJSONArray("nodes");var edges=flow.getJSONArray("edges");var steps=new ArrayList<WorkflowDefinitionParameters.Node>();var bindings=new ArrayList<String>();
        for(Object raw:nodes) {
            var n=(JSONObject)raw;var task=integrations.selectById(n.getLong("taskId"));
            if(task==null||!Objects.equals(task.getStatus(),1)||!"OFFLINE".equals(task.getMode())||task.getDsDefinitionCode()==null||!Objects.equals(task.getDsProjectCode(),runtime.getDsProjectCode()))throw exception(JOB_CONFIG_INVALID,"节点引用任务的可执行版本已变化，请刷新后重试");
            var upstream=new ArrayList<String>();for(Object rawEdge:edges){var edge=(JSONObject)rawEdge;if(n.getStr("id").equals(edge.getStr("target")))upstream.add(edge.getStr("source"));}Collections.sort(upstream);
            steps.add(new WorkflowDefinitionParameters.Node(n.getStr("id"),task.getName(),"SUB_WORKFLOW",Map.of("workflowDefinitionCode",task.getDsDefinitionCode(),"localParams",List.of(),"resourceList",List.of()),upstream,n.getStr("priority","MEDIUM")));
            bindings.add(n.getStr("id")+"|"+task.getId()+"|"+task.getDsProjectCode()+"|"+task.getDsDefinitionCode()+"|"+task.getRevision()+"|"+n.getStr("priority","MEDIUM")+"|"+String.join(",",upstream));
        }
        Collections.sort(bindings);
        String fingerprint;
        try {fingerprint=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((job.getStrategy()+"\n"+String.join("\n",bindings)).getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,20);}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        String name="dh_job_"+job.getId()+"_"+fingerprint;
        if(job.getDsDefinitionCode()!=null) {
            reconcileSchedule(job);
            var data=checked(ds.workflowDefinition().queryWorkflowDefinitionByCode(job.getDsProjectCode(),job.getDsDefinitionCode())).getJSONObject("data");
            var definition=data==null?null:data.getJSONObject("workflowDefinition");
            if(definition==null)throw exception(JOB_RUNTIME_FAILED);
            if(name.equals(definition.getStr("name"))) {
                checked(ds.workflowDefinition().releaseWorkflowDefinition(job.getDsProjectCode(),job.getDsDefinitionCode(),"ONLINE",null));
                return;
            }
            if(job.getScheduleStatus()==1)throw exception(JOB_CONFIG_INVALID,"引用任务的发布版本已变化，请先关闭作业调度，再重新配置或执行");
            job.setDsScheduleId(null);
        }
        var page=checked(ds.workflowDefinition().queryWorkflowDefinitionListPaging(runtime.getDsProjectCode(),name,null,null,1,100)).getJSONObject("data");Long code=null;
        if(page!=null&&page.getJSONArray("totalList")!=null)for(Object raw:page.getJSONArray("totalList")){var found=new JSONObject(raw);if(name.equals(found.getStr("name"))){if(code!=null)throw exception(JOB_CONFIG_INVALID,"受管作业定义存在同名重复，请管理员核对");code=found.getLong("code");}}
        if(code==null){var codes=checked(ds.taskDefinition().genTaskCodeList(runtime.getDsProjectCode(),steps.size())).getJSONArray("data");if(codes==null||codes.size()!=steps.size())throw exception(JOB_RUNTIME_FAILED);var definition=WorkflowDefinitionParameters.build(name,"DataHub 作业 "+job.getId(),steps,codes.toList(Long.class),new WorkflowDefinitionParameters.Execution("MEDIUM","default",runtime.getEnvironmentCode(),0,1,0,job.getStrategy()));var data=checked(ds.workflowDefinition().createWorkflowDefinition(runtime.getDsProjectCode(),definition)).getJSONObject("data");if(data==null||data.getLong("code")==null)throw exception(JOB_RUNTIME_FAILED);code=data.getLong("code");}
        job.setDsProjectCode(runtime.getDsProjectCode());job.setDsDefinitionCode(code);jobs.updateById(job);
        jobs.update(null,Wrappers.<JobTaskDO>lambdaUpdate().eq(JobTaskDO::getId,job.getId()).eq(JobTaskDO::getRevision,job.getRevision()).eq(JobTaskDO::getOperationState,"BUSY").set(JobTaskDO::getDsScheduleId,null));
        checked(ds.workflowDefinition().releaseWorkflowDefinition(job.getDsProjectCode(),code,"ONLINE",null));
    }

    /** 普通列表和运维读取共用有界对账入口，不执行远端控制命令。 */
    public void reconcileForRead(Long jobId) { refresh(jobId); }

    /** 每轮最多核对十个较久未更新的绑定作业，包含尚无本地实例的周期作业。 */
    public Set<Long> reconcileRecentForRead() {
        var selected=jobs.selectList(Wrappers.<JobTaskDO>lambdaQuery().and(q->q.isNotNull(JobTaskDO::getDsDefinitionCode).or().apply("EXISTS (SELECT 1 FROM dh_dataforge_job_record r WHERE r.job_id=dh_dataforge_job.id AND r.deleted=0 AND r.submission_stage IS NOT NULL AND r.status IN ('WAITING','RUNNING','UNKNOWN'))")).eq(JobTaskDO::getOperationState,"IDLE").orderByAsc(JobTaskDO::getUpdateTime,JobTaskDO::getId).last("LIMIT 10"));
        var ids=new LinkedHashSet<Long>();
        for(var job:selected){ids.add(job.getId());refresh(job.getId());}
        return ids;
    }
    private void reconcileSchedule(JobTaskDO job) {
        if(job.getDsDefinitionCode()==null)return;
        var data=checked(ds.schedule().queryScheduleListPaging(job.getDsProjectCode(),job.getDsDefinitionCode(),null,1,100)).getJSONObject("data");
        var schedules=data==null?null:data.getJSONArray("totalList");
        if(schedules==null||schedules.size()>1)throw exception(JOB_CONFIG_INVALID,"调度周期状态无法唯一确认，请管理员核对");
        Integer scheduleId=schedules.isEmpty()?null:schedules.getJSONObject(0).getInt("id");
        int enabled=!schedules.isEmpty()&&"ONLINE".equals(schedules.getJSONObject(0).getStr("releaseState"))?1:0;
        job.setDsScheduleId(scheduleId);job.setScheduleStatus(enabled);
        jobs.update(null,Wrappers.<JobTaskDO>lambdaUpdate().eq(JobTaskDO::getId,job.getId()).eq(JobTaskDO::getDsDefinitionCode,job.getDsDefinitionCode()).eq(JobTaskDO::getRevision,job.getRevision()).eq(JobTaskDO::getOperationState,job.getOperationState()).set(JobTaskDO::getDsScheduleId,scheduleId).set(JobTaskDO::getScheduleStatus,enabled));
    }
    private void refresh(Long jobId) {
        var job=require(jobId);
        queue.reconcileForRead(jobId);
        var observed=new HashSet<Integer>();
        if(job.getDsDefinitionCode()!=null) {
            try {
                var data=checked(ds.workflowInstance().queryWorkflowInstanceList(job.getDsProjectCode(),new QueryWorkflowInstanceListRequest().setWorkflowDefinitionCode(job.getDsDefinitionCode()).setPageNo(1).setPageSize(100))).getJSONObject("data");
                var instances=data==null?null:data.getJSONArray("totalList");
                if(instances!=null) for(Object raw:instances) {
                    var instance=new JSONObject(raw);Integer id=instance.getInt("id");if(id==null||id<=0)continue;observed.add(id);
                    var record=records.selectOne(Wrappers.<JobRecordDO>lambdaQuery().eq(JobRecordDO::getDsProjectCode,job.getDsProjectCode()).eq(JobRecordDO::getDsInstanceId,id));
                    if(record==null) {
                        var globals=instance.getJSONArray("globalParams");
                        if(globals!=null)for(Object rawParam:globals){var param=new JSONObject(rawParam);if("datahub_job_request".equals(param.getStr("prop")))record=records.selectOne(Wrappers.<JobRecordDO>lambdaQuery().eq(JobRecordDO::getJobId,jobId).eq(JobRecordDO::getRequestId,param.getStr("value")));}
                    }
                    if(record==null){record=new JobRecordDO();record.setJobId(jobId);record.setOrigin("PLATFORM");record.setExecutionType("START");record.setFlowJson(job.getFlowJson());}
                    record.setDsProjectCode(job.getDsProjectCode());record.setDsInstanceId(id);record.setInstanceName(instance.getStr("name",job.getName()+"-"+id));applyInstance(record,instance);
                    try{if(record.getId()==null)records.insert(record);else records.updateById(record);}catch(DuplicateKeyException ignored){/* 并发读取由实例唯一键收敛。 */}
                }
                if(job.getScheduleSnapshotJson()==null&&"IDLE".equals(job.getOperationState()))reconcileSchedule(job);
            } catch(RuntimeException ignored){return;/* 读取失败保留未知与原状态，不自动重新提交。 */}
        }
        for(var r:active(jobId).stream().filter(item->item.getDsInstanceId()!=null&&!observed.contains(item.getDsInstanceId())).limit(10).toList()) {
            try{if(managedRuns.owned(r))continue;var data=checked(ds.workflowInstance().queryWorkflowInstanceById(r.getDsProjectCode(),r.getDsInstanceId())).getJSONObject("data");if(data==null)continue;applyInstance(r,data);records.updateById(r);}catch(RuntimeException ignored){/* 查询失败保留原状态，不推断已成功或已停止。 */}
        }
    }
    private void applyInstance(JobRecordDO r,JSONObject data) {
        String state=data.getStr("state");
        r.setStatus(switch(String.valueOf(state)){case "SUCCESS","FORCED_SUCCESS"->"SUCCESS";case "FAILURE"->"FAILURE";case "STOP","KILL"->"STOPPED";case "RUNNING_EXECUTION"->"RUNNING";case "SUBMITTED_SUCCESS","DISPATCH","DELAY_EXECUTION","SERIAL_WAIT"->"WAITING";default->"UNKNOWN";});
        r.setStartTime(time(data.get("startTime"),r.getStartTime()==null?now():r.getStartTime()));r.setEndTime(time(data.get("endTime"),null));
        if(r.getEndTime()!=null)r.setDuration(Math.max(0,Duration.between(r.getStartTime(),r.getEndTime()).toSeconds()));
        r.setLogContent("DolphinScheduler 实例 "+r.getDsInstanceId()+"；实际状态："+state);
    }
    private static LocalDateTime time(Object value,LocalDateTime fallback){try{if(value instanceof Number n)return LocalDateTime.ofInstant(Instant.ofEpochMilli(n.longValue()),ZoneId.of("Asia/Shanghai"));if(value!=null)return LocalDateTime.parse(value.toString().replace('T',' ').substring(0,19),TIME);}catch(RuntimeException ignored){ }return fallback;}
    private static JSONObject checked(JSONObject result){if(result==null||result.getInt("code",-1)!=0)throw exception(JOB_RUNTIME_FAILED);return result;}
    private List<JobRecordDO> active(Long id){return records.selectList(Wrappers.<JobRecordDO>lambdaQuery().eq(JobRecordDO::getJobId,id).eq(JobRecordDO::getOrigin,"PLATFORM").in(JobRecordDO::getStatus,ACTIVE).last("LIMIT 100"));}
    private JobTaskDO require(Long id){var job=id==null?null:jobs.selectById(id);if(job==null)throw exception(JOB_NOT_EXISTS);return job;}
    private void requireEditable(JobTaskDO job){if(job.getStatus()!=0||job.getScheduleStatus()!=0||!"IDLE".equals(job.getOperationState())||!active(job.getId()).isEmpty())throw exception(JOB_STATE_INVALID);}
    private JobTaskDO prepare(SaveReq req){if(categories.selectById(req.getCategoryId())==null)throw exception(JOB_CONFIG_INVALID,"作业类目不存在");var job=BeanUtils.toBean(req,JobTaskDO.class);job.setName(req.getName().trim());if(job.getName().isBlank())throw exception(JOB_CONFIG_INVALID,"作业名称不能为空");if(StrUtil.isBlank(job.getFlowJson()))job.setFlowJson("{\"nodes\":[],\"edges\":[]}");var check=checkFlow(job.getFlowJson(),false);if(!check.isValid())throw exception(JOB_CONFIG_INVALID,String.join("；",check.getErrors()));if(StrUtil.isNotBlank(job.getCronExpression()))cronPreview(job.getCronExpression());if(job.getOwnerId()!=null){var owner=owners().stream().filter(o->o.getId().equals(job.getOwnerId())).findFirst().orElseThrow(()->exception(JOB_CONFIG_INVALID,"负责人不存在或已停用"));job.setOwnerName(owner.getName());}return job;}
    private Set<Long> categoryScope(Long id){if(id==null)return null;var all=categories();var result=new HashSet<Long>();result.add(id);boolean changed;do{changed=false;for(var c:all)if(result.contains(c.getParentId()))changed|=result.add(c.getId());}while(changed);return result;}
    private TaskResp response(JobTaskDO job){var result=BeanUtils.toBean(job,TaskResp.class);long total=records.selectCount(Wrappers.<JobRecordDO>lambdaQuery().eq(JobRecordDO::getJobId,job.getId()).eq(JobRecordDO::getOrigin,"PLATFORM").in(JobRecordDO::getStatus,ACTIVE));long waiting=records.selectCount(Wrappers.<JobRecordDO>lambdaQuery().eq(JobRecordDO::getJobId,job.getId()).eq(JobRecordDO::getQueueState,"QUEUED"));result.setActiveRunCount(Math.max(0,total-waiting));result.setQueuedRunCount(waiting);result.setHasActiveRuns(total>0);var category=categories.selectById(job.getCategoryId());result.setCategoryName(category==null?"":category.getName());var latest=records.selectList(Wrappers.<JobRecordDO>lambdaQuery().eq(JobRecordDO::getJobId,job.getId()).orderByDesc(JobRecordDO::getId).last("LIMIT 1"));if(!latest.isEmpty()){var r=latest.getFirst();result.setLatestRecord(instance(r,job));result.setLastRunStatus(r.getStatus());result.setLastRunTime(r.getStartTime());result.setLastRunDuration(r.getDuration());}result.setConfigurationStatus(checkFlow(job.getFlowJson(),false).isExecutable()?"READY":"DRAFT");return result;}
    public static InstanceResp instance(JobRecordDO r,JobTaskDO job){var result=new InstanceResp();result.setId(r.getId());result.setKind("JOB");result.setOrigin(r.getOrigin());result.setTaskId(job.getId());result.setTaskName(job.getName());result.setInstanceName(r.getInstanceName());result.setCategoryId("platform:"+job.getCategoryId());result.setStatus(r.getStatus());result.setExecutionType(r.getExecutionType());result.setStartTime(r.getStartTime());result.setEndTime(r.getEndTime());result.setDuration(r.getDuration());result.setOwnerName(job.getOwnerName());result.setCreatorName("SOURCE".equals(r.getOrigin())?"演示用户":r.getCreator());if("SOURCE".equals(r.getOrigin()))result.setRunCount(1);result.setDescription(job.getDescription());result.setFlowJson(r.getFlowJson());fillRuntimeIdentity(result,r);if(StrUtil.isNotBlank(r.getChildrenJson()))result.setChildren(cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseArray(r.getChildrenJson(),InstanceResp.class));return result;}
    private static LocalDateTime now(){return LocalDateTime.now(ZoneId.of("Asia/Shanghai"));}
}

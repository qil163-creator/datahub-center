package cn.iocoder.yudao.module.dataforge.service.integration;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerClient;
import cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerException;
import cn.iocoder.yudao.framework.dolphinscheduler.request.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.integration.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.integration.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** DS 控制面薄适配，不在本服务执行数据处理，也不把 HTTP 接收成功当作业务成功。 */
@Component
public class IntegrationDsRuntime {
    @Resource private DolphinSchedulerClient client;
    @Resource private IntegrationRuntimeProperties properties;
    @Resource private IntegrationTaskMapper tasks;
    @Resource private IntegrationRecordMapper records;

    public Long publish(IntegrationTaskDO task, List<IntegrationFlowCompiler.Step> steps) {
        requireEnvironment(task);
        String name = "dh_integration_" + task.getId() + "_v" + (task.getRevision() + 1) + "_" + fingerprint(task, steps).substring(0, 12);
        var existing = checked(client.workflowDefinition().queryWorkflowDefinitionListPaging(properties.getDsProjectCode(), name, null, null, 1, 100)).getJSONObject("data");
        if (existing != null && existing.getJSONArray("totalList") != null) {
            for (Object item : existing.getJSONArray("totalList")) {
                var definition = new JSONObject(item);
                if (name.equals(definition.getStr("name"))) return definition.getLong("code");
            }
        }
        var codes = checked(client.taskDefinition().genTaskCodeList(properties.getDsProjectCode(), steps.size())).getJSONArray("data");
        if (codes == null || codes.size() != steps.size()) throw new IllegalStateException("DS 未返回完整节点编码");
        var request = cn.iocoder.yudao.framework.dolphinscheduler.model.WorkflowDefinitionParameters.build(name,
                "集成任务 " + task.getId(), steps.stream().map(step -> new cn.iocoder.yudao.framework.dolphinscheduler.model.WorkflowDefinitionParameters.Node(
                        step.id(), step.name(), step.taskType(), step.parameters(), step.upstream())).toList(),
                codes.toList(Long.class), new cn.iocoder.yudao.framework.dolphinscheduler.model.WorkflowDefinitionParameters.Execution(
                        task.getPriority(), task.getWorkerGroup(), environmentCode(task), task.getRetryCount(),
                        task.getRetryInterval(), task.getDelayMinutes(), task.getExecutionStrategy()));
        var created = checked(client.workflowDefinition().createWorkflowDefinition(properties.getDsProjectCode(), request)).getJSONObject("data");
        if (created == null || created.getLong("code") == null) throw new IllegalStateException("DS 未返回工作流编码");
        return created.getLong("code");
    }

    public void release(IntegrationTaskDO task, boolean online) {
        checked(client.workflowDefinition().releaseWorkflowDefinition(task.getDsProjectCode(), task.getDsDefinitionCode(),
                online ? "ONLINE" : "OFFLINE", null));
    }

    public Integer schedule(IntegrationTaskDO task, String cron, boolean enable) {
        requireEnvironment(task);
        if (!enable) {
            if (task.getDsScheduleId() != null) checked(client.schedule().offlineSchedule(task.getDsProjectCode(), task.getDsScheduleId()));
            return task.getDsScheduleId();
        }
        Integer id = task.getDsScheduleId();
        if (id == null) {
            var zone = ZoneId.of("Asia/Shanghai"); var now = ZonedDateTime.now(zone);
            var schedule = new JSONObject().set("startTime", now.toInstant().toEpochMilli())
                    .set("endTime", now.plusYears(10).toInstant().toEpochMilli()).set("crontab", cron).set("timezoneId", zone.getId());
            var request = new CreateScheduleRequest().setWorkflowDefinitionCode(task.getDsDefinitionCode())
                    .setSchedule(schedule.toString()).setWarningType("NONE").setFailureStrategy("END")
                    .setWorkerGroup(task.getWorkerGroup()).setTenantCode(properties.getTenantCode())
                    .setEnvironmentCode(environmentCode(task)).setWorkflowInstancePriority(task.getPriority());
            var result = checked(client.schedule().createSchedule(task.getDsProjectCode(), request)).getJSONObject("data");
            id = result == null ? null : result.getInt("id");
            if (id == null) throw new IllegalStateException("DS 未返回调度编号");
            // 先保存外部身份再上线，防止上线响应丢失后留下不可追踪的活动调度。
            if (tasks.update(null, Wrappers.lambdaUpdate(IntegrationTaskDO.class).eq(IntegrationTaskDO::getId, task.getId())
                    .eq(IntegrationTaskDO::getDsDefinitionCode, task.getDsDefinitionCode())
                    .set(IntegrationTaskDO::getDsScheduleId, id)) != 1) {
                checked(client.schedule().deleteScheduleById(task.getDsProjectCode(), id));
                throw new IllegalStateException("任务已变化，未上线的 DS 调度已清理，请刷新");
            }
            task.setDsScheduleId(id);
        }
        else {
            var now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
            var schedule = new JSONObject().set("startTime", now.toInstant().toEpochMilli())
                    .set("endTime", now.plusYears(10).toInstant().toEpochMilli()).set("crontab", cron).set("timezoneId", "Asia/Shanghai");
            checked(client.schedule().updateSchedule(task.getDsProjectCode(), id, new UpdateScheduleRequest()
                    .setSchedule(schedule.toString()).setWorkerGroup(task.getWorkerGroup()).setTenantCode(properties.getTenantCode())
                    .setEnvironmentCode(environmentCode(task)).setWorkflowInstancePriority(task.getPriority())
                    .setWarningType("NONE").setFailureStrategy("END")));
        }
        checked(client.schedule().publishScheduleOnline(task.getDsProjectCode(), id));
        return id;
    }

    public Long run(IntegrationTaskDO task) {
        requireEnvironment(task);
        if (records.selectCount(Wrappers.lambdaQuery(IntegrationRecordDO.class).eq(IntegrationRecordDO::getTaskId, task.getId())
                .eq(IntegrationRecordDO::getStatus, "RUNNING").isNotNull(IntegrationRecordDO::getDsProjectCode)
                .isNull(IntegrationRecordDO::getDsInstanceId)) > 0) throw new IllegalArgumentException("有一次 DS 提交尚未确认，请先刷新运行实例对账");
        var record = new IntegrationRecordDO(); record.setTaskId(task.getId()); record.setStatus("RUNNING");
        record.setTriggerType("MANUAL"); record.setStartTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        record.setDsProjectCode(task.getDsProjectCode()); record.setMessage("正在向 DolphinScheduler 提交");
        records.insert(record);
        var request = new TriggerWorkflowDefinitionRequest().setWorkflowDefinitionCode(task.getDsDefinitionCode())
                .setScheduleTime("").setFailureStrategy("END").setWarningType("NONE").setExecType("START_PROCESS")
                .setWorkflowInstancePriority(task.getPriority()).setWorkerGroup(task.getWorkerGroup())
                .setTenantCode(properties.getTenantCode()).setEnvironmentCode(environmentCode(task))
                .setStartParams(new JSONObject().set("integration_run_id", String.valueOf(record.getId())).toString()).setDryRun(0);
        try {
            var ids = checked(client.executor().triggerWorkflowDefinition(task.getDsProjectCode(), request)).getJSONArray("data");
            if (ids == null || ids.size() != 1 || ids.getLong(0) <= 0 || ids.getLong(0) > Integer.MAX_VALUE) {
                throw new IllegalStateException("DS 未返回唯一实例编号");
            }
            record.setDsInstanceId(ids.getInt(0)); record.setMessage("DS 已接收，等待引擎执行；实例 " + record.getDsInstanceId());
            records.updateById(record);
        } catch (RuntimeException failure) {
            // 网络超时可能已经触发，保留未知提交并通过唯一启动参数对账，不能自动重复投递。
            if (failure instanceof cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerException rejected && rejected.getCode() != null) {
                record.setStatus("FAILURE"); record.setEndTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
                record.setMessage("DS 拒绝提交，错误码 " + rejected.getCode());
            } else record.setMessage("DS 提交结果待确认，请刷新运行实例核对；本平台不会自动重复投递");
            records.updateById(record);
            throw failure;
        }
        tasks.update(null, Wrappers.lambdaUpdate(IntegrationTaskDO.class).eq(IntegrationTaskDO::getId, task.getId())
                .set(IntegrationTaskDO::getLastRunStatus, "RUNNING").set(IntegrationTaskDO::getLastRunTime, record.getStartTime()));
        return record.getId();
    }

    public void stop(IntegrationTaskDO task) {
        refresh(task);
        var running = records.selectList(Wrappers.lambdaQuery(IntegrationRecordDO.class)
                .eq(IntegrationRecordDO::getTaskId, task.getId()).eq(IntegrationRecordDO::getStatus, "RUNNING")
                .isNotNull(IntegrationRecordDO::getDsInstanceId));
        if (running.isEmpty()) throw new IllegalArgumentException("没有可停止的 DS 实例");
        for (var record : running) {
            record.setCommandType("STOP_REQUESTED"); record.setMessage("已请求 DS 停止，等待工作进程退出"); records.updateById(record);
            checked(client.executor().controlWorkflowInstance(record.getDsProjectCode(), record.getDsInstanceId(), "STOP"));
        }
    }

    public void refresh(IntegrationTaskDO task) {
        if (task.getDsDefinitionCode() == null) return;
        refreshRelease(task);
        refreshSchedule(task);
        var page = checked(client.workflowInstance().queryWorkflowInstanceList(task.getDsProjectCode(),
                new QueryWorkflowInstanceListRequest().setWorkflowDefinitionCode(task.getDsDefinitionCode()).setPageNo(1).setPageSize(100))).getJSONObject("data");
        var list = page == null ? null : page.getJSONArray("totalList");
        if (list == null) throw new IllegalStateException("DS 实例查询缺少分页数据");
        // 超出首页的长跑实例仍需按编号对账，不能长期保留过期 RUNNING。
        var visible = new HashSet<Integer>();
        for (Object item : list) visible.add(new JSONObject(item).getInt("id"));
        var active = records.selectList(Wrappers.lambdaQuery(IntegrationRecordDO.class)
                .eq(IntegrationRecordDO::getTaskId, task.getId()).eq(IntegrationRecordDO::getStatus, "RUNNING")
                .isNotNull(IntegrationRecordDO::getDsInstanceId));
        for (var record : active) {
            if (visible.contains(record.getDsInstanceId())) continue;
            var instance = checked(client.workflowInstance().queryWorkflowInstanceById(record.getDsProjectCode(), record.getDsInstanceId())).getJSONObject("data");
            if (instance == null || !Objects.equals(instance.getInt("id"), record.getDsInstanceId()))
                throw new IllegalStateException("DS 实例详情缺失，暂不能确认执行状态");
            list.put(instance);
        }
        for (Object item : list) {
            var instance = new JSONObject(item); Integer instanceId = instance.getInt("id");
            if (instanceId == null) continue;
            var record = records.selectOne(Wrappers.lambdaQuery(IntegrationRecordDO.class).eq(IntegrationRecordDO::getDsProjectCode, task.getDsProjectCode())
                    .eq(IntegrationRecordDO::getDsInstanceId, instanceId));
            if (record == null) {
                record = reconcile(task, instance);
                if (record == null) { record = new IntegrationRecordDO(); record.setTaskId(task.getId()); record.setTriggerType("SCHEDULE"); }
                record.setDsProjectCode(task.getDsProjectCode()); record.setDsInstanceId(instanceId);
            }
            String state = instance.getStr("state", "UNKNOWN");
            boolean stopRequested = "STOP_REQUESTED".equals(record.getCommandType());
            record.setStatus(stopRequested && "FAILURE".equals(status(state)) ? "IDLE" : status(state));
            record.setStartTime(time(instance.get("startTime"))); record.setEndTime(time(instance.get("endTime")));
            if (record.getStartTime() != null && record.getEndTime() != null) record.setDuration(Math.max(0, Duration.between(record.getStartTime(), record.getEndTime()).getSeconds()));
            record.setInstanceName(instance.getStr("name")); if (!stopRequested) record.setCommandType(instance.getStr("commandType"));
            record.setMessage((stopRequested ? "已请求停止；" : "") + "DolphinScheduler 状态：" + state + "；实例：" + instanceId);
            if ("JOB".equals(record.getTriggerType()) && "IDLE".equals(record.getStatus())) record.setStatus("STOPPED");
            String observedJobStatus = record.getStatus();
            try {
                if (record.getId() == null) records.insert(record);
                else if (record.getDsProjectCode() != null) records.update(record, Wrappers.<IntegrationRecordDO>lambdaUpdate()
                        .eq(IntegrationRecordDO::getId, record.getId()).and(q -> q.notIn(IntegrationRecordDO::getStatus,
                                List.of("SUCCESS", "FAILURE", "STOPPED", "IDLE")).or().eq(IntegrationRecordDO::getStatus, observedJobStatus)));
                else records.updateById(record);
            } catch (org.springframework.dao.DuplicateKeyException concurrentRefresh) { continue; }
        }
        var latest = records.selectList(Wrappers.lambdaQuery(IntegrationRecordDO.class).eq(IntegrationRecordDO::getTaskId, task.getId())
                .isNotNull(IntegrationRecordDO::getDsProjectCode).orderByDesc(IntegrationRecordDO::getStartTime, IntegrationRecordDO::getId).last("LIMIT 1"));
        if (!latest.isEmpty()) {
            var record = latest.getFirst(); task.setLastRunStatus(record.getStatus()); task.setLastRunTime(record.getStartTime()); task.setLastRunDuration(record.getDuration());
            tasks.update(null, Wrappers.lambdaUpdate(IntegrationTaskDO.class).eq(IntegrationTaskDO::getId, task.getId())
                    .set(IntegrationTaskDO::getLastRunStatus, record.getStatus()).set(IntegrationTaskDO::getLastRunTime, record.getStartTime())
                    .set(IntegrationTaskDO::getLastRunDuration, record.getDuration()));
        }
    }

    /** 发布结果以 DS 为准，恢复网络超时或本地乐观锁失败后的控制面状态。 */
    public void refreshRelease(IntegrationTaskDO task) {
        JSONObject result;
        try {
            result = client.workflowDefinition().queryWorkflowDefinitionByCode(task.getDsProjectCode(), task.getDsDefinitionCode());
        } catch (DolphinSchedulerException failure) {
            if (missingDefinition(failure.getCode())) throw new MissingWorkflowDefinitionException();
            throw failure;
        }
        if (result != null && missingDefinition(result.getInt("code"))) throw new MissingWorkflowDefinitionException();
        var data = checked(result).getJSONObject("data");
        var definition = data == null ? null : data.getJSONObject("workflowDefinition");
        if (definition == null) throw new IllegalStateException("DS 工作流定义缺失，暂不能确认上线状态");
        String state = definition.getStr("releaseState");
        if (!"ONLINE".equals(state) && !"OFFLINE".equals(state)) throw new IllegalStateException("DS 工作流发布状态无法识别");
        int online = "ONLINE".equals(state) ? 1 : 0;
        if (!Objects.equals(task.getStatus(), online)) {
            int changed = tasks.update(null, Wrappers.lambdaUpdate(IntegrationTaskDO.class).eq(IntegrationTaskDO::getId, task.getId())
                    .eq(IntegrationTaskDO::getRevision, task.getRevision()).eq(IntegrationTaskDO::getDsDefinitionCode, task.getDsDefinitionCode())
                    .set(IntegrationTaskDO::getStatus, online).set(IntegrationTaskDO::getRevision, task.getRevision() + 1));
            if (changed == 1) { task.setStatus(online); task.setRevision(task.getRevision() + 1); }
        }
    }

    private static boolean missingDefinition(Integer code) {
        // DS 3.4.2：项目未找到、项目不存在、工作流定义不存在。
        return code != null && Set.of(10018, 10190, 50003).contains(code);
    }

    /** 只在首次读取定义时确认缺失，保证降级前尚未修改任务或实例状态。 */
    public static final class MissingWorkflowDefinitionException extends IllegalStateException {
        MissingWorkflowDefinitionException() {
            super("DolphinScheduler 项目或工作流不存在，当前运行状态不可用，请恢复调度元数据后重试");
        }
    }

    /** DS 是周期实际状态的权威，GET 对账也能找回创建响应丢失的调度编号。 */
    public void refreshSchedule(IntegrationTaskDO task) {
        var data = checked(client.schedule().queryScheduleListPaging(task.getDsProjectCode(), task.getDsDefinitionCode(), null, 1, 100)).getJSONObject("data");
        var list = data == null ? null : data.getJSONArray("totalList");
        if (list == null) throw new IllegalStateException("DS 调度查询缺少分页数据");
        if (list.size() > 1) throw new IllegalStateException("工作流存在多个 DS 调度，请管理员核对后再操作");
        Integer id = list.isEmpty() ? null : list.getJSONObject(0).getInt("id");
        int enabled = !list.isEmpty() && "ONLINE".equals(list.getJSONObject(0).getStr("releaseState")) ? 1 : 0;
        if (!Objects.equals(task.getDsScheduleId(), id) || !Objects.equals(task.getScheduleStatus(), enabled)) {
            int changed = tasks.update(null, Wrappers.lambdaUpdate(IntegrationTaskDO.class).eq(IntegrationTaskDO::getId, task.getId())
                    .eq(IntegrationTaskDO::getRevision, task.getRevision()).eq(IntegrationTaskDO::getDsDefinitionCode, task.getDsDefinitionCode())
                    .set(IntegrationTaskDO::getDsScheduleId, id).set(IntegrationTaskDO::getScheduleStatus, enabled)
                    .set(IntegrationTaskDO::getRevision, task.getRevision() + 1));
            if (changed == 1) { task.setDsScheduleId(id); task.setScheduleStatus(enabled); task.setRevision(task.getRevision() + 1); }
        }
    }

    private IntegrationRecordDO reconcile(IntegrationTaskDO task, JSONObject instance) {
        var globals = instance.getJSONArray("globalParams");
        if (globals == null) return null;
        for (Object value : globals) {
            var param = new JSONObject(value);
            if ("integration_run_id".equals(param.getStr("prop"))) {
                Long id;
                try { id = Long.valueOf(param.getStr("value")); } catch (RuntimeException invalid) { return null; }
                var record = records.selectById(id);
                return record != null && Objects.equals(record.getTaskId(), task.getId()) && record.getDsInstanceId() == null ? record : null;
            }
        }
        return null;
    }

    public void requireEnvironment(IntegrationTaskDO task) {
        if (properties.getDsProjectCode() == null || properties.getDsProjectCode() <= 0
                || properties.getTenantCode() == null || properties.getTenantCode().isBlank()
                || !properties.getWorkerGroups().contains(task.getWorkerGroup())
                || (task.getDsProjectCode() != null && !Objects.equals(task.getDsProjectCode(), properties.getDsProjectCode()))) {
            throw new IllegalArgumentException("请配置集成任务的 DS 项目、执行用户和 Worker 分组");
        }
    }

    /** 参数或受管环境变化后，原工作流必须重新保存发布。 */
    public String fingerprint(IntegrationTaskDO task, List<IntegrationFlowCompiler.Step> steps) {
        var payload = new JSONObject().set("steps", steps).set("project", properties.getDsProjectCode())
                .set("tenant", properties.getTenantCode()).set("environment", environmentCode(task))
                .set("worker", task.getWorkerGroup()).set("priority", task.getPriority()).set("retry", task.getRetryCount())
                .set("sparkMaster", properties.getSparkMaster()).set("flinkMode", properties.getFlinkDeployMode()).set("flinkJobManager", properties.getFlinkJobManager())
                .set("interval", task.getRetryInterval()).set("delay", task.getDelayMinutes()).set("strategy", task.getExecutionStrategy());
        return cn.hutool.crypto.digest.DigestUtil.sha256Hex(cn.hutool.json.JSONUtil.toJsonStr(canonical(cn.hutool.json.JSONUtil.parse(payload.toString()))));
    }

    /** 对象键递归排序、数组保序，使重启和不同 Map 实现不会改变相同配置的绑定摘要。 */
    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> object) {
            var sorted = new TreeMap<String, Object>();
            object.forEach((key, item) -> sorted.put(String.valueOf(key), canonical(item)));
            return sorted;
        }
        if (value instanceof Collection<?> array) return array.stream().map(IntegrationDsRuntime::canonical).toList();
        return value;
    }

    public Long environmentCode(IntegrationTaskDO task) { return properties.getEngineEnvironmentCodes().getOrDefault(task.getEngine(), properties.getEnvironmentCode()); }

    public Long projectCode() { return properties.getDsProjectCode(); }

    private static JSONObject checked(JSONObject result) {
        if (result == null || result.getInt("code", -1) != 0 || Boolean.FALSE.equals(result.getBool("success"))) {
            throw new IllegalStateException("DolphinScheduler 请求失败，请检查服务连接及权限");
        }
        return result;
    }

    static String status(String state) {
        return switch (state) {
            case "SUCCESS", "7" -> "SUCCESS";
            case "FAILURE", "6" -> "FAILURE";
            case "STOP", "PAUSE", "CANCEL", "3", "5", "9" -> "IDLE";
            default -> "RUNNING";
        };
    }

    private static LocalDateTime time(Object value) {
        if (value == null || value instanceof cn.hutool.json.JSONNull) return null;
        if (value instanceof Number epoch) return LocalDateTime.ofInstant(Instant.ofEpochMilli(epoch.longValue()), ZoneId.of("Asia/Shanghai"));
        try { return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME); }
        catch (RuntimeException invalid) { throw new IllegalStateException("DS 返回无法识别的时间格式"); }
    }
}

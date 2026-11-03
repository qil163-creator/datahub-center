package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerClient;
import cn.iocoder.yudao.framework.dolphinscheduler.request.*;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.*;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevScheduleMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/** DS 管理真正的周期；维护器只同步外部绑定并导入已发生的触发实例，不计算业务触发时间。 */
@Service
public class DevScheduleCoordinator {

    private static final DateTimeFormatter DS_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int PAGE_SIZE = 100;

    private static final LocalDateTime SCHEDULE_END = LocalDateTime.of(2099, 12, 31, 23, 59, 59);

    @Resource
    private DevScheduleMapper schedules;

    @Resource
    private DevDsSubmitter submitter;

    @Resource
    private DolphinSchedulerClient client;

    @Autowired
    private ObjectProvider<DevTaskService> tasks;

    @Value("${dataforge.dev.schedule.timezone-id:Asia/Shanghai}")
    private String timezoneId;

    @Value("${dataforge.dev.schedule.api-timezone-id:Asia/Shanghai}")
    private String apiTimezoneId;

    @Value("${dataforge.dev.schedule.worker-group:${dataforge.dev.worker-group:}}")
    private String triggerWorkerGroup;

    @Value("${dataforge.dev.schedule.tenant-code:${dataforge.dev.tenant-code:}}")
    private String triggerTenantCode;

    /**
     * 获取开发调度的时区。
     *
     * @return 时区编号
     */
    public ZoneId zone() {
        return ZoneId.of(timezoneId);
    }

    /** 配置读取与冻结目标验证在写事务外完成，不联网、不存储秘密。 */
    public void validateTarget(DevTaskSnapshot snapshot) {
        submitter.executionProject(snapshot);
        target(snapshot);
        zone();
        ZoneId.of(apiTimezoneId);
        String cron = snapshot.getTask().getCronExpression();
        if (!cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinCronSchedule.isValidExpression(cron)) {
            throw exception(DEV_TASK_CRON_INVALID);
        }
        var next = cn.iocoder.yudao.framework.dolphinscheduler.core.DolphinCronSchedule.parse(cron)
                .next(LocalDateTime.now(zone()), zone());
        if (next == null || next.isAfter(SCHEDULE_END)) {
            throw exception(DEV_TASK_CRON_INVALID);
        }
    }

    /**
     * 获取开发调度的目标。
     *
     * @param snapshot 快照
     * @return 开发调度
     */
    private String[] target(DevTaskSnapshot snapshot) {
        String group = StrUtil.blankToDefault(triggerWorkerGroup, snapshot.getWorkerGroup());
        String tenant = StrUtil.blankToDefault(triggerTenantCode, snapshot.getTenantCode());
        if (StrUtil.isBlank(group) || "default".equalsIgnoreCase(group)
                || StrUtil.isBlank(tenant) || "root".equalsIgnoreCase(tenant)) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID, "DS 周期触发需要专用 Worker 组和非 root 执行租户");
        }
        return new String[]{group, tenant};
    }

    /** 与任务 CAS 保存共用调用方事务；外部变更通过持久意图恢复。 */
    public void saveIntent(DevTaskDO task, DevTaskSnapshot snapshot) {
        retire(task.getId());
        if (!DevTaskDO.SCHEDULE_ON.equals(task.getScheduleStatus())) {
            return;
        }
        String[] target = target(snapshot);
        var binding = new DevScheduleDO();
        binding.setTaskId(task.getId());
        binding.setVersionId(task.getCurrentVersionId());
        binding.setGeneration(task.getRevision());
        binding.setCronExpression(task.getCronExpression());
        binding.setTimezoneId(timezoneId);
        binding.setDsProjectCode(submitter.executionProject(snapshot));
        binding.setWorkerGroup(target[0]);
        binding.setTenantCode(target[1]);
        binding.setDesiredEnabled(true);
        binding.setSyncedEnabled(false);
        binding.setSyncStatus("PENDING");
        binding.setAttempts(0);
        binding.setLastDsInstanceId(0L);
        binding.setPollHighWaterId(0L);
        binding.setPollPageNo(1);
        binding.setScheduleStartTime(LocalDateTime.now(zone()));
        schedules.insert(binding);
    }

    /**
     * 退役开发调度。
     *
     * @param taskId 任务编号
     */
    public void retire(Long taskId) {
        schedules.update(null, Wrappers.<DevScheduleDO>lambdaUpdate().set(DevScheduleDO::getDesiredEnabled, false)
                .set(DevScheduleDO::getSyncStatus, "PENDING").set(DevScheduleDO::getAttempts, 0)
                .set(DevScheduleDO::getLastError, null)
                .eq(DevScheduleDO::getTaskId, taskId)
                .and(q -> q.eq(DevScheduleDO::getDesiredEnabled, true).or()
                        .ne(DevScheduleDO::getSyncStatus, "SYNCED")));
    }

    public record SyncState(String status, String reason) {

    }

    /**
     * 读取开发调度当前状态。
     *
     * @param taskId 任务编号
     * @return 同步状态
     */
    public SyncState state(Long taskId) {
        var bindings = schedules.selectList(Wrappers.<DevScheduleDO>lambdaQuery().eq(DevScheduleDO::getTaskId, taskId)
                .and(q -> q.eq(DevScheduleDO::getDesiredEnabled, true).or()
                        .ne(DevScheduleDO::getSyncStatus, "SYNCED")));
        for (var binding : bindings) {
            if ("ERROR".equals(binding.getSyncStatus())) {
                return new SyncState("ERROR", binding.getLastError());
            }
        }
        for (var binding : bindings) {
            if (!"SYNCED".equals(binding.getSyncStatus())) {
                return new SyncState("PENDING", "等待 DS 调度登记或停用确认");
            }
        }
        return new SyncState(bindings.isEmpty() ? "NOT_CONFIGURED" : "SYNCED", null);
    }

    /**
     * 维护开发调度。
     */
    public void maintain() {
        for (var binding : schedules.selectDueMaintenance(LocalDateTime.now())) {
            process(binding.getId());
        }
    }

    /**
     * 处理开发调度。
     *
     * @param id 记录编号
     */
    void process(Long id) {
        String owner = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        if (schedules.update(null, Wrappers.<DevScheduleDO>lambdaUpdate().set(DevScheduleDO::getLeaseOwner, owner)
                .set(DevScheduleDO::getLeaseUntil, now.plusMinutes(5)).set(DevScheduleDO::getUpdateTime, now)
                .eq(DevScheduleDO::getId, id)
                .and(q -> q.isNull(DevScheduleDO::getLeaseUntil).or().lt(DevScheduleDO::getLeaseUntil, now))) != 1) {
            return;
        }
        var binding = schedules.selectById(id);
        try {
            synchronize(binding, owner);
            binding = schedules.selectById(id);
            if (Boolean.TRUE.equals(binding.getDesiredEnabled()) && Boolean.TRUE.equals(binding.getSyncedEnabled())) {
                importPage(binding, owner);
            }
            fenced(id, owner).set(DevScheduleDO::getAttempts, 0).set(DevScheduleDO::getLastError, null)
                    .set(DevScheduleDO::getLeaseOwner, null).set(DevScheduleDO::getLeaseUntil, null).update();
        } catch (RuntimeException failure) {
            // 不记录远端原文、URL、脚本或凭据；未知远端写结果通过精确名称和绑定重新对账。
            fenced(id, owner).set(DevScheduleDO::getSyncStatus, "ERROR")
                    .set(DevScheduleDO::getAttempts, binding.getAttempts() + 1)
                    .set(DevScheduleDO::getLastError,
                            "DS 调度同步或触发导入失败；检查受管目标、授权和 DS 服务后，重新保存任务或调整调度重试")
                    .set(DevScheduleDO::getLeaseOwner, null).set(DevScheduleDO::getLeaseUntil, null).update();
        }
    }

    /**
     * 同步开发调度。
     *
     * @param binding 绑定
     * @param owner 所有者
     */
    private void synchronize(DevScheduleDO binding, String owner) {
        boolean enabled = Boolean.TRUE.equals(binding.getDesiredEnabled());
        if (enabled && schedules.selectCount(
                Wrappers.<DevScheduleDO>lambdaQuery().eq(DevScheduleDO::getTaskId, binding.getTaskId())
                        .ne(DevScheduleDO::getId, binding.getId()).eq(DevScheduleDO::getDesiredEnabled, false)
                        .and(q -> q.eq(DevScheduleDO::getSyncedEnabled, true).or()
                                .ne(DevScheduleDO::getSyncStatus, "SYNCED"))) > 0) {
            // 旧代尚未确认停用时只保持待同步，防止两个周期同时触发，也不消耗远端失败重试次数。
            fenced(binding.getId(), owner).set(DevScheduleDO::getSyncStatus, "PENDING").update();
            return;
        }
        if (!enabled && binding.getDsWorkflowCode() == null) {
            Long existing = findWorkflow(binding, owner);
            if (existing == null) {
                markSynchronized(binding, owner, false);
                return;
            }
            binding.setDsWorkflowCode(existing);
            fenced(binding.getId(), owner).set(DevScheduleDO::getDsWorkflowCode, existing).update();
        }
        if (binding.getDsWorkflowCode() == null) {
            Long code = findWorkflow(binding, owner);
            if (code == null) {
                heartbeat(binding.getId(), owner);
                var generated = checked(
                        client.taskDefinition().genTaskCodeList(binding.getDsProjectCode(), 1)).getJSONArray("data");
                if (generated == null || generated.isEmpty()) {
                    throw invalid();
                }
                long taskCode = generated.getLong(0);
                var node = new JSONObject().set("code", taskCode).set("version", 1).set("name", "周期触发标记")
                        .set("taskType", "SHELL").set("taskParams", new JSONObject().set("rawScript", "exit 0")
                                .set("localParams", new JSONArray()).set("resourceList", new JSONArray()))
                        .set("flag", "YES").set("taskPriority", "MEDIUM").set("workerGroup", binding.getWorkerGroup())
                        .set("failRetryTimes", 0).set("failRetryInterval", 1).set("timeoutFlag", "CLOSE")
                        .set("timeout", 0)
                        .set("delayTime", 0).set("environmentCode", -1).set("taskExecuteType", "BATCH");
                var request = new CreateWorkflowDefinitionRequest().setName(name(binding))
                        .setDescription("平台受管周期触发标记；业务程序由授权运行意图投递")
                        .setGlobalParams(markers(binding).toString()).setLocations("[]").setTimeout(0)
                        .setExecutionType("SERIAL_WAIT")
                        .setTaskDefinitionJson(new JSONArray().put(node).toString())
                        .setTaskRelationJson(
                                new JSONArray().put(new JSONObject().set("preTaskCode", 0).set("preTaskVersion", 0)
                                        .set("postTaskCode", taskCode).set("postTaskVersion", 1)
                                        .set("conditionType", "NONE")
                                        .set("conditionParams", new JSONObject())).toString());
                heartbeat(binding.getId(), owner);
                code = checked(client.workflowDefinition()
                        .createWorkflowDefinition(binding.getDsProjectCode(), request)).getJSONObject("data")
                        .getLong("code");
                if (code == null) {
                    throw invalid();
                }
            }
            binding.setDsWorkflowCode(code);
            fenced(binding.getId(), owner).set(DevScheduleDO::getDsWorkflowCode, code).update();
        }
        verifyWorkflow(binding, owner);
        var existing = findSchedule(binding, owner);
        if (existing == null && !enabled) {
            markSynchronized(binding, owner, false);
            return;
        }
        if (existing == null) {
            heartbeat(binding.getId(), owner);
            checked(client.workflowDefinition()
                    .releaseWorkflowDefinition(binding.getDsProjectCode(), binding.getDsWorkflowCode(), "ONLINE",
                            name(binding)));
            heartbeat(binding.getId(), owner);
            var request = new CreateScheduleRequest().setWorkflowDefinitionCode(binding.getDsWorkflowCode())
                    .setSchedule(schedule(binding))
                    .setWarningType("NONE").setFailureStrategy("END").setWorkerGroup(binding.getWorkerGroup())
                    .setTenantCode(binding.getTenantCode())
                    .setWorkflowInstancePriority("MEDIUM");
            checked(client.schedule().createSchedule(binding.getDsProjectCode(), request));
            existing = findSchedule(binding, owner);
            if (existing == null) {
                throw invalid();
            }
        }
        Integer scheduleId = existing.getInt("id");
        if (scheduleId == null) {
            throw invalid();
        }
        binding.setDsScheduleId(scheduleId);
        fenced(binding.getId(), owner).set(DevScheduleDO::getDsScheduleId, scheduleId).update();
        // 保存过程中期望状态可能变化；读取最新意图，避免迟到成功把已取消的周期重新置为有效。
        enabled = Boolean.TRUE.equals(schedules.selectById(binding.getId()).getDesiredEnabled());
        String expected = enabled ? "ONLINE" : "OFFLINE";
        if (!expected.equals(existing.getStr("releaseState"))) {
            heartbeat(binding.getId(), owner);
            checked(enabled ? client.schedule().publishScheduleOnline(binding.getDsProjectCode(), scheduleId)
                    : client.schedule().offlineSchedule(binding.getDsProjectCode(), scheduleId));
        }
        markSynchronized(binding, owner, enabled);
    }

    /**
     * 查找工作流。
     *
     * @param binding 绑定
     * @param owner 所有者
     * @return 查找工作流数值
     */
    private Long findWorkflow(DevScheduleDO binding, String owner) {
        Long code = null;
        // DS 名称搜索为子串查询；完整分页后才允许断言对象不存在，未知写结果不会重复创建。
        for (int page = 1; page <= 10; page++) {
            heartbeat(binding.getId(), owner);
            var response = checked(client.workflowDefinition()
                    .queryWorkflowDefinitionListPaging(binding.getDsProjectCode(), name(binding), null, null, page,
                            100));
            var listed = rows(response);
            for (var row : listed) {
                if (name(binding).equals(row.getStr("name"))) {
                    if (code != null || row.getLong("code") == null) {
                        throw invalid();
                    }
                    code = row.getLong("code");
                }
            }
            Integer total = response.getJSONObject("data").getInt("total");
            if ((total != null && total <= page * 100) || (total == null && listed.size() < 100)) {
                return code;
            }
            if (listed.isEmpty()) {
                throw invalid();
            }
        }
        throw invalid();
    }

    /**
     * 核对工作流。
     *
     * @param binding 绑定
     * @param owner 所有者
     */
    private void verifyWorkflow(DevScheduleDO binding, String owner) {
        heartbeat(binding.getId(), owner);
        var data = checked(client.workflowDefinition()
                .queryWorkflowDefinitionByCode(binding.getDsProjectCode(), binding.getDsWorkflowCode())).getJSONObject(
                "data");
        if (data == null) {
            throw invalid();
        }
        var definition = data.getJSONObject("processDefinition");
        if (definition == null) {
            definition = data.getJSONObject("workflowDefinition");
        }
        if (definition == null) {
            definition = data;
        }
        if (!name(binding).equals(definition.getStr("name")) || !Objects.equals(binding.getDsWorkflowCode(),
                definition.getLong("code"))) {
            throw invalid();
        }
        if (definition.getInt("version", 0) != 1 || !Objects.equals(definition.getLong("projectCode"),
                binding.getDsProjectCode())) {
            throw invalid();
        }
        verifyMarkers(binding, definition.get("globalParams"));
        var nodes = data.getJSONArray("taskDefinitionList");
        if (nodes == null || nodes.size() != 1) {
            throw invalid();
        }
        var node = nodes.getJSONObject(0);
        Object parameters = node.get("taskParams");
        var params = parameters instanceof JSONObject json ? json : new JSONObject(String.valueOf(parameters));
        if (!"SHELL".equals(node.getStr("taskType")) || !"exit 0".equals(params.getStr("rawScript"))
                || !binding.getWorkerGroup().equals(node.getStr("workerGroup"))
                || node.getInt("version", 0) != 1 || !"YES".equals(node.getStr("flag"))
                || node.getLong("environmentCode", -1L) != -1L
                || params.getJSONArray("localParams") == null || !params.getJSONArray("localParams").isEmpty()
                || params.getJSONArray("resourceList") == null || !params.getJSONArray("resourceList").isEmpty()) {
            throw invalid();
        }
    }

    /**
     * 查找调度。
     *
     * @param binding 绑定
     * @param owner 所有者
     * @return JSON对象
     */
    private JSONObject findSchedule(DevScheduleDO binding, String owner) {
        heartbeat(binding.getId(), owner);
        var response = checked(client.schedule()
                .queryScheduleListPaging(binding.getDsProjectCode(), binding.getDsWorkflowCode(), null, 1, 100));
        var listed = rows(response);
        Integer total = response.getJSONObject("data").getInt("total");
        if ((total != null && total > listed.size()) || listed.size() > 1) {
            throw invalid();
        }
        JSONObject found = null;
        for (var row : listed) {
            Long workflow = row.getLong("workflowDefinitionCode", row.getLong("processDefinitionCode"));
            if (!Objects.equals(workflow, binding.getDsWorkflowCode())) {
                throw invalid();
            }
            if (found != null || !binding.getCronExpression().equals(row.getStr("crontab"))
                    || !binding.getTimezoneId().equals(row.getStr("timezoneId"))
                    || !binding.getWorkerGroup().equals(row.getStr("workerGroup")) || !binding.getTenantCode()
                    .equals(row.getStr("tenantCode"))) {
                throw invalid();
            }
            found = row;
        }
        return found;
    }

    /**
     * 分页导入开发调度。
     *
     * @param binding 绑定
     * @param owner 所有者
     */
    private void importPage(DevScheduleDO binding, String owner) {
        ZoneId zone = ZoneId.of(binding.getTimezoneId());
        if (binding.getPollEndTime() == null) {
            binding.setPollEndTime(LocalDateTime.now(zone));
            binding.setPollPageNo(1);
            binding.setPollHighWaterId(binding.getLastDsInstanceId());
            fenced(binding.getId(), owner).set(DevScheduleDO::getPollEndTime, binding.getPollEndTime())
                    .set(DevScheduleDO::getPollPageNo, 1)
                    .set(DevScheduleDO::getPollHighWaterId, binding.getPollHighWaterId()).update();
        }
        // 先查最新第一页，历史对账再查一个固定窗口分页；历史增长不会让新触发长期等待。
        var head = queryInstances(binding, owner, LocalDateTime.now(zone), 1);
        long highWater = importInstances(binding, owner, head, binding.getPollHighWaterId());
        var page = queryInstances(binding, owner, binding.getPollEndTime(), binding.getPollPageNo());
        highWater = importInstances(binding, owner, page, highWater);
        if (page.size() == PAGE_SIZE) {
            fenced(binding.getId(), owner).set(DevScheduleDO::getPollPageNo, binding.getPollPageNo() + 1)
                    .set(DevScheduleDO::getPollHighWaterId, highWater).update();
        } else {
            fenced(binding.getId(), owner).set(DevScheduleDO::getLastDsInstanceId, highWater)
                    .set(DevScheduleDO::getPollEndTime, null)
                    .set(DevScheduleDO::getPollPageNo, 1).set(DevScheduleDO::getPollHighWaterId, 0L)
                    .set(DevScheduleDO::getLastPollTime, LocalDateTime.now(zone)).update();
        }
    }

    /**
     * 获取开发调度的查询实例。
     *
     * @param binding 绑定
     * @param owner 所有者
     * @param end 结束位置或时间
     * @param pageNo 页码
     * @return 开发调度的查询实例结果集合
     */
    private List<JSONObject> queryInstances(DevScheduleDO binding, String owner, LocalDateTime end, int pageNo) {
        heartbeat(binding.getId(), owner);
        var apiZone = ZoneId.of(apiTimezoneId);
        var bindingZone = ZoneId.of(binding.getTimezoneId());
        var request = new QueryWorkflowInstanceListRequest().setWorkflowDefinitionCode(binding.getDsWorkflowCode())
                .setStartDate(
                        binding.getScheduleStartTime().atZone(bindingZone).withZoneSameInstant(apiZone).format(DS_TIME))
                .setEndDate(end.atZone(bindingZone).withZoneSameInstant(apiZone).format(DS_TIME)).setPageNo(pageNo)
                .setPageSize(PAGE_SIZE);
        return rows(checked(client.workflowInstance().queryWorkflowInstanceList(binding.getDsProjectCode(), request)));
    }

    /**
     * 导入实例。
     *
     * @param binding 绑定
     * @param owner 所有者
     * @param page 分页
     * @param highWater 高位水位
     * @return 导入实例数值
     */
    private long importInstances(DevScheduleDO binding, String owner, List<JSONObject> page, long highWater) {
        for (var summary : page) {
            Long instanceId = summary.getLong("id");
            if (instanceId == null || instanceId <= 0) {
                throw invalid();
            }
            // v1 列表按开始时间和可变结束时间排序，编号水位只供诊断，不能用于跳过迟到实例。
            heartbeat(binding.getId(), owner);
            var instance = checked(client.workflowInstance()
                    .queryWorkflowInstanceById(binding.getDsProjectCode(), Math.toIntExact(instanceId))).getJSONObject(
                    "data");
            if (instance == null || !Objects.equals(instanceId, instance.getLong("id"))) {
                throw invalid();
            }
            Long code = instance.getLong("workflowDefinitionCode", instance.getLong("processDefinitionCode"));
            if (!Objects.equals(code, binding.getDsWorkflowCode())) {
                throw invalid();
            }
            Long project = instance.getLong("projectCode");
            if (!Objects.equals(project, binding.getDsProjectCode())) {
                throw invalid();
            }
            if (!"SCHEDULER".equals(instance.getStr("commandType")) || instance.getInt("dryRun", 0) != 0) {
                highWater = Math.max(highWater, instanceId);
                continue;
            }
            if (instance.getInt("workflowDefinitionVersion", 0) != 1 || !binding.getWorkerGroup()
                    .equals(instance.getStr("workerGroup"))
                    || !binding.getTenantCode().equals(instance.getStr("tenantCode"))) {
                throw invalid();
            }
            verifyMarkers(binding, instance.get("globalParams"));
            String state = DevDsSubmitter.mapInstanceState(instance.getStr("state"));
            if (state == null || DevRecordDO.STATUS_RUNNING.equals(state)) {
                continue;
            }
            Object value = instance.get("scheduleTime");
            var zone = ZoneId.of(binding.getTimezoneId());
            LocalDateTime time = scheduledTime(value, zone);
            if (value instanceof String text) {
                try {
                    OffsetDateTime.parse(text);
                } catch (java.time.format.DateTimeParseException withoutOffset) {
                    time = LocalDateTime.parse(text.replace(' ', 'T')).atZone(ZoneId.of(apiTimezoneId))
                            .withZoneSameInstant(zone).toLocalDateTime();
                }
            }
            heartbeat(binding.getId(), owner);
            if (DevRecordDO.STATUS_SUCCESS.equals(state)) {
                tasks.getObject().runTaskScheduled(binding, instanceId, time);
            } else {
                tasks.getObject().recordTaskScheduledFailure(binding, instanceId, time, state);
            }
            highWater = Math.max(highWater, instanceId);
        }
        return highWater;
    }

    /**
     * 读取计划的时间。
     *
     * @param value 待处理的值
     * @param zone 时区
     * @return 本地日期时间
     */
    static LocalDateTime scheduledTime(Object value, ZoneId zone) {
        if (value instanceof Number number) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(number.longValue()), zone);
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalid();
        }
        try {
            return OffsetDateTime.parse(text).atZoneSameInstant(zone).toLocalDateTime();
        } catch (java.time.format.DateTimeParseException ignored) {
            return LocalDateTime.parse(text.replace(' ', 'T'));
        }
    }

    /**
     * 安排开发调度。
     *
     * @param binding 绑定
     * @return 安排开发调度文本
     */
    private String schedule(DevScheduleDO binding) {
        return new JSONObject().set("startTime",
                        binding.getScheduleStartTime().atZone(ZoneId.of(binding.getTimezoneId())).toInstant().toEpochMilli())
                .set("endTime", SCHEDULE_END.atZone(ZoneId.of(binding.getTimezoneId())).toInstant().toEpochMilli())
                .set("crontab", binding.getCronExpression()).set("timezoneId", binding.getTimezoneId()).toString();
    }

    /**
     * 读取开发调度名称。
     *
     * @param binding 绑定
     * @return 开发调度名称文本
     */
    private String name(DevScheduleDO binding) {
        return "dh_dev_tick_" + binding.getTaskId() + "_" + binding.getGeneration();
    }

    /**
     * 获取开发调度的标记。
     *
     * @param binding 绑定
     * @return JSON数组
     */
    private JSONArray markers(DevScheduleDO binding) {
        return new JSONArray().put(marker("task", binding.getTaskId()))
                .put(marker("version", binding.getVersionId())).put(marker("generation", binding.getGeneration()));
    }

    /**
     * 获取开发调度的标记。
     *
     * @param key 标识键
     * @param value 待处理的值
     * @return JSON对象
     */
    private JSONObject marker(String key, Object value) {
        return new JSONObject().set("prop", "dh_" + key).set("type", "VARCHAR").set("direct", "IN")
                .set("value", String.valueOf(value));
    }

    /**
     * 核对标记。
     *
     * @param binding 绑定
     * @param payload 载荷
     */
    private void verifyMarkers(DevScheduleDO binding, Object payload) {
        var actual = payload instanceof JSONArray array ? array : new JSONArray(String.valueOf(payload));
        var values = new HashMap<String, String>();
        for (var item : actual.toList(JSONObject.class)) {
            if (values.put(item.getStr("prop"), item.getStr("value")) != null) {
                throw invalid();
            }
        }
        for (var expected : markers(binding).toList(JSONObject.class)) {
            if (!Objects.equals(expected.getStr("value"), values.get(expected.getStr("prop")))) {
                throw invalid();
            }
        }
    }

    /**
     * 提取开发调度行数据。
     *
     * @param response 响应对象
     * @return 提取开发调度行数据结果集合
     */
    private List<JSONObject> rows(JSONObject response) {
        var data = response.getJSONObject("data");
        if (data == null || data.getJSONArray("totalList") == null) {
            throw invalid();
        }
        return data.getJSONArray("totalList").toList(JSONObject.class);
    }

    /**
     * 获取开发调度的已校验。
     *
     * @param response 响应对象
     * @return JSON对象
     */
    private static JSONObject checked(JSONObject response) {
        if (response == null || response.getInt("code", -1) != 0 || Boolean.FALSE.equals(response.getBool("success"))) {
            throw invalid();
        }
        return response;
    }

    /**
     * 标记已同步。
     *
     * @param binding 绑定
     * @param owner 所有者
     * @param enabled 启用状态
     */
    private void markSynchronized(DevScheduleDO binding, String owner, boolean enabled) {
        fenced(binding.getId(), owner).eq(DevScheduleDO::getDesiredEnabled, enabled)
                .set(DevScheduleDO::getSyncedEnabled, enabled)
                .set(DevScheduleDO::getSyncStatus, "SYNCED").update();
    }

    /**
     * 续约开发调度。
     *
     * @param id 记录编号
     * @param owner 所有者
     */
    private void heartbeat(Long id, String owner) {
        if (!fenced(id, owner).set(DevScheduleDO::getLeaseUntil, LocalDateTime.now().plusMinutes(5)).update()) {
            throw invalid();
        }
    }

    /**
     * 构造绑定租约持有者和有效期的更新条件，隔离已失去执行权的实例。
     *
     * @param id 记录编号
     * @param owner 所有者
     * @return Lambda更新处理链条件包装器开发调度
     */
    private com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper<DevScheduleDO> fenced(Long id,
            String owner) {
        return new com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper<>(schedules)
                .eq(DevScheduleDO::getId, id).eq(DevScheduleDO::getLeaseOwner, owner)
                .gt(DevScheduleDO::getLeaseUntil, LocalDateTime.now())
                .set(DevScheduleDO::getUpdateTime, LocalDateTime.now());
    }

    /**
     * 构造当前配置或输入不合法时使用的异常。
     *
     * @return 运行时异常
     */
    private static RuntimeException invalid() {
        return exception(DEV_TASK_DEPENDENCY_INVALID, "DS 调度绑定或触发载荷无法核验");
    }
}

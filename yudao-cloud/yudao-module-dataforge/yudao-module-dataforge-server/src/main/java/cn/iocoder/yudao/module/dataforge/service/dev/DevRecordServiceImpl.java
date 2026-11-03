package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevRecordPageReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevRecordDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.dev.DevRecordMapper;
import cn.iocoder.yudao.framework.dolphinscheduler.DolphinSchedulerClient;
import cn.iocoder.yudao.framework.dolphinscheduler.request.QueryTaskListPagingRequest;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevTaskLogPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevTaskLogPageRespVO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.dataforge.enums.ErrorCodeConstants.*;

/**
 * 数据开发-任务执行实例 Service 实现类
 *
 * @author Li
 */
@Service
@Validated
public class DevRecordServiceImpl implements DevRecordService {

    // 开发日志按完整行隐藏连接身份、SQL 参数和结果回显。
    private static final String LOG_STAMP = "\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?(?:\\s+[+-]\\d{2}:\\d{2})?";

    private static final String LOG_SEVERITY = "(?:INFO|WARN|ERROR|DEBUG|TRACE)";

    private static final String LOG_HEADER = "(?:\\[" + LOG_SEVERITY + "\\]\\s+" + LOG_STAMP + "\\s+-\\s*|"
            + "\\[?" + LOG_STAMP + "\\]?\\s+\\[?" + LOG_SEVERITY + "\\]?\\s*(?:\\[[^\\r\\n\\]]{0,100}\\]\\s*)*[-:]?\\s*)";

    private static final java.util.regex.Pattern EMBEDDED_LOG_HEADER = java.util.regex.Pattern.compile(
            "(?im)^" + LOG_HEADER);

    private static final java.util.regex.Pattern PARAMETER_LINE_BREAK = java.util.regex.Pattern.compile("\\R");

    private static final java.util.regex.Pattern SENSITIVE_LOG_LINE = java.util.regex.Pattern.compile(
            "(?im)^.*(?:password|passwd|\\bpwd\\b|token|secret|access[-_ ]?key|private[-_ ]?key|"
                    + "authorization|credential|keytab|jdbc:|user[-_ ]?name|\\buser\\s*[=:]).*$");

    private static final java.util.regex.Pattern SQL_VALUE_LOG_LINE = java.util.regex.Pattern.compile(
            "(?im)^.*(?:\\brow\\s+\\d+\\s*:|execute sql result|Sql Params are|sql parameters|"
                    + "prepare statement replace sql|Full sql parameters|Initialize sql task parameter|localParams|"
                    + "Failing row contains|\\bDetail:|\\bDETAIL:).*$");

    private static final java.util.regex.Pattern LOG_CONTINUATION = java.util.regex.Pattern.compile(
            "(?im)^(?!" + LOG_HEADER + ").+$");

    private static final java.util.regex.Pattern MULTILINE_PARAMETER_LOG = java.util.regex.Pattern.compile(
            "(?im)^(" + LOG_HEADER + ").*$");

    @Resource
    private DevNativeRuntimeSupport nativeRuntimeSupport;

    @Resource
    private DevRecordMapper recordMapper;

    @Resource
    private DevDsSubmitter dsSubmitter;

    @Resource
    private DolphinSchedulerClient dsClient;

    /** 查询权限由 Controller 执行，当前平台工作区按对象授权读取。 */
    @Override
    public DevTaskLogPageRespVO getTaskLog(DevTaskLogPageReqVO req) {
        if (req == null || req.getId() == null || req.getId() <= 0 || req.getSkipLineNum() == null
                || req.getSkipLineNum() < 0 || req.getLimit() == null || req.getLimit() < 1 || req.getLimit() > 1000
                || req.getSkipLineNum() > Integer.MAX_VALUE - 1000) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID, "日志游标或分页大小无效");
        }
        if (SecurityFrameworkUtils.getLoginUserId() == null) {
            throw exception(DEV_EXECUTION_IDENTITY_REQUIRED);
        }
        DevRecordDO record = getRecord(req.getId());

        DevTaskLogPageRespVO response = new DevTaskLogPageRespVO();
        response.setSource("DOLPHINSCHEDULER");
        response.setMessage("");
        response.setReadLines(0);
        response.setNextSkipLineNum(req.getSkipLineNum());
        response.setTruncated(false);
        response.setAvailable(false);
        response.setMayHaveMore(DevRecordDO.ACTIVE_STATUSES.contains(record.getStatus()));
        if (record.getRunKey() == null) {
            response.setStatusMessage("历史实例原始日志请通过受控运维渠道查阅");
            return response;
        }
        if (record.getDsProjectCode() == null || record.getDsProjectCode() <= 0
                || record.getDsInstanceId() == null || record.getDsInstanceId() <= 0) {
            response.setStatusMessage("调度器尚未确认运行实例，稍后重试");
            return response;
        }
        try {
            // Current runs own one DS node. An ambiguous/multi-node result is not silently
            // resolved to an arbitrary node; a future node-selection API must retain this scope check.
            var result = dsClient.taskInstance().queryTaskListPaging(record.getDsProjectCode(),
                    new QueryTaskListPagingRequest().setWorkflowInstanceId(record.getDsInstanceId())
                            .setTaskExecuteType("BATCH").setPageNo(1).setPageSize(2));
            if (result == null || result.getInt("code", -1) != 0
                    || Boolean.FALSE.equals(result.getBool("success")) || Boolean.TRUE.equals(
                    result.getBool("failed"))) {
                response.setStatusMessage("任务日志暂不可读取，请稍后重试");
                return response;
            }
            var data = result.getJSONObject("data");
            var tasks = data == null ? null : data.getJSONArray("totalList");
            if (tasks == null || tasks.isEmpty()) {
                response.setStatusMessage("调度器尚未生成任务节点，稍后重试");
                return response;
            }
            if (tasks.size() != 1 || !Integer.valueOf(1).equals(data.getInt("total"))) {
                response.setStatusMessage("运行包含多个任务节点，需通过受控节点选择查看日志");
                return response;
            }
            var task = tasks.getJSONObject(0);
            Integer taskId = task.getInt("id");
            if (taskId == null || taskId <= 0 || !record.getDsProjectCode().equals(task.getLong("projectCode"))
                    || !record.getDsInstanceId().equals(task.getInt("workflowInstanceId"))) {
                response.setStatusMessage("调度器节点与平台运行关联不一致，请联系运维核查");
                return response;
            }
            var page = dsClient.log().queryLogPage(taskId, req.getSkipLineNum(), req.getLimit());
            String raw = page.message();
            if (record.getSnapshotJson() != null && DevNativeRuntimeSupport.isNative(
                    DevTaskSnapshot.read(record.getSnapshotJson()).getTask())) {
                // 原生插件会回显脚本、argv 和原始结果；只提供可核验的时间/级别标题。
                raw = maskMultilineParameterLog(raw);
            }
            if (hasMultilineInput(record)) {
                raw = maskMultilineParameterLog(raw);
            }
            response.setMessage(maskTaskLog(raw));
            response.setReadLines(page.readLines());
            response.setNextSkipLineNum(page.nextSkipLineNum());
            response.setMayHaveMore(page.mayHaveMore() || DevRecordDO.ACTIVE_STATUSES.contains(record.getStatus()));
            response.setTruncated(page.truncated());
            response.setAvailable(true);
            response.setStatusMessage(page.readLines() == 0 ? "当前没有新增日志" : null);
        } catch (RuntimeException ex) {
            // DS/driver exceptions can contain credentials and raw response bodies.
            // Never expose or persist them through this read-only endpoint.
            response.setStatusMessage("任务日志暂不可读取，请稍后重试或联系运维核查");
        }
        return response;
    }

    /**
     * 隐藏开发日志中的敏感内容及无法核验的续行。
     *
     * @param raw 原始日志
     * @return 脱敏日志
     */
    private static String maskTaskLog(String raw) {
        String safe = SENSITIVE_LOG_LINE.matcher(raw).replaceAll("*** [敏感日志已隐藏]");
        safe = SQL_VALUE_LOG_LINE.matcher(safe).replaceAll("*** [SQL 结果或参数回显已隐藏]");
        return LOG_CONTINUATION.matcher(safe).replaceAll("*** [日志续行已隐藏]");
    }

    /**
     * 隐藏多行输入任务的日志正文，仅保留可核验的记录头。
     *
     * @param raw 原始日志
     * @return 脱敏日志
     */
    private static String maskMultilineParameterLog(String raw) {
        return MULTILINE_PARAMETER_LOG.matcher(raw).replaceAll("$1*** [多行输入任务日志正文已隐藏]");
    }

    /**
     * 判断是否存在多行输入条件。
     *
     * @param record 运行记录
     * @return 判断结果
     */
    private boolean hasMultilineInput(DevRecordDO record) {
        try {
            var snapshot = DevTaskSnapshot.read(record.getSnapshotJson());
            String content = snapshot.getTask().getContent();
            // 调度器会回显完整 SQL。原脚本里若存在可伪造记录头的行，仅提供时间与级别；
            // 复用日志识别边界，普通多行 SQL 仍可展示运维标题。
            if (content != null && EMBEDDED_LOG_HEADER.matcher(content).find()) {
                return true;
            }
            return DevTaskDefinition.readExecutionConfig(snapshot.getTask().getExecutionConfigJson()).getLocalParams()
                    .stream()
                    .anyMatch(parameter -> parameter.getValue() != null
                            && PARAMETER_LINE_BREAK.matcher(parameter.getValue()).find());
        } catch (RuntimeException unverifiable) {
            // 无法核验参数来源的历史/损坏快照，仅提供记录头，不猜测原文是否安全。
            return true;
        }
    }

    /**
     * 获得任务实例分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 任务实例分页结果
     */
    @Override
    public PageResult<DevRecordDO> getRecordPage(DevRecordPageReqVO pageReqVO) {
        PageResult<DevRecordDO> page = recordMapper.selectPage(pageReqVO);
        page.getList().forEach(this::hideLegacyLog);
        return page;
    }

    /**
     * 读取最新记录。
     *
     * @param taskIds 任务编号集合
     * @return 处理后的键值映射
     */
    @Override
    public java.util.Map<Long, DevRecordDO> getLatestRecords(java.util.Collection<Long> taskIds) {
        var latest = recordMapper.selectLatestByTaskIds(taskIds);
        latest.forEach(this::hideLegacyLog);
        return cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertMap(latest,
                DevRecordDO::getTaskId);
    }

    /**
     * 获得当前平台任务执行实例统计。
     *
     * @param taskId 任务编号
     * @return 开发记录统计响应数据
     */
    @Override
    public cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevRecordStatisticsRespVO getStatistics(
            Long taskId) {
        var result = new cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevRecordStatisticsRespVO();
        result.setTotal(countRecords(taskId, null));
        result.setRunning(recordMapper.selectCount(
                new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<DevRecordDO>()
                        .eqIfPresent(DevRecordDO::getTaskId, taskId)
                        .in(DevRecordDO::getStatus, DevRecordDO.ACTIVE_STATUSES)));
        result.setSuccess(countRecords(taskId, DevRecordDO.STATUS_SUCCESS));
        result.setFailed(countRecords(taskId, DevRecordDO.STATUS_FAILED));
        result.setRefreshedAt(java.time.LocalDateTime.now());
        return result;
    }

    /**
     * 统计开发记录的记录数量。
     *
     * @param taskId 任务编号
     * @param status 目标状态
     * @return 符合条件或受影响的记录数量
     */
    private Long countRecords(Long taskId, String status) {
        return recordMapper.selectCount(
                new cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX<DevRecordDO>()
                        .eqIfPresent(DevRecordDO::getTaskId, taskId).eqIfPresent(DevRecordDO::getStatus, status));
    }

    /** 有界导出沿用原始日志的关联与脱敏检查，不提供客户端指定 DS 节点的入口。 */
    @Override
    public String downloadTaskLog(Long id) {
        DevRecordDO record = getRecord(id);
        if (record.getRunKey() == null) {
            return record.getLog();
        }
        StringBuilder content = new StringBuilder();
        int cursor = 0;
        int exportedBytes = 0;
        for (int page = 0; page < 20; page++) {
            var req = new DevTaskLogPageReqVO();
            req.setId(id);
            req.setSkipLineNum(cursor);
            req.setLimit(1000);
            var result = getTaskLog(req);
            if (!Boolean.TRUE.equals(result.getAvailable())) {
                content.append(result.getStatusMessage());
                break;
            }
            String message = result.getMessage() == null ? "" : result.getMessage();
            int messageBytes = message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (exportedBytes + messageBytes + 1 > 1_000_000) {
                content.append("\n[导出达到 1 MB 文本上限；更多内容请使用分页日志]\n");
                break;
            }
            content.append(message);
            if (!message.endsWith("\n")) {
                content.append('\n');
            }
            exportedBytes += messageBytes + 1;
            if (result.getReadLines() == null || result.getReadLines() == 0
                    || result.getNextSkipLineNum() == null || result.getNextSkipLineNum() <= cursor
                    || !Boolean.TRUE.equals(result.getMayHaveMore())) {
                break;
            }
            cursor = result.getNextSkipLineNum();
            if (page == 19) {
                content.append("\n[已导出前 20000 行；更多内容请使用分页日志]\n");
            }
        }
        return content.toString();
    }

    /**
     * 获得任务实例（含日志，供运行中轮询）。
     *
     * @param id 记录编号
     * @return 开发记录
     */
    @Override
    public DevRecordDO getRecord(Long id) {
        DevRecordDO record = recordMapper.selectById(id);
        if (record == null) {
            throw exception(DEV_RECORD_NOT_EXISTS);
        }
        if (record.getSnapshotJson() != null && DevNativeRuntimeSupport.isNative(
                DevTaskSnapshot.read(record.getSnapshotJson()).getTask())) {
            nativeRuntimeSupport.requireRecordAccess(record);
        }
        hideLegacyLog(record);
        if (Boolean.TRUE.equals(record.getNativeProgram())) {
            safeNativeDiagnostics(record);
        }
        return record;
    }

    /**
     * 隐藏原生任务与历史运行中的原始日志和错误正文，只保留受控查阅提示。
     *
     * @param record 运行记录
     */
    private void hideLegacyLog(DevRecordDO record) {
        if (record.getSnapshotJson() != null && DevNativeRuntimeSupport.isNative(
                DevTaskSnapshot.read(record.getSnapshotJson()).getTask())) {
            record.setTaskType(DevTaskSnapshot.read(record.getSnapshotJson()).getTask().getTaskType());
            record.setNativeProgram(true);
            record.setLog("原生插件日志需原生执行权限，并仅提供脱敏记录标题");
            record.setLastError(record.getLastError() == null ? null : "原生运行失败，请查看受控脱敏日志");
        }
        if (record.getRunKey() == null) {
            record.setLog("历史原始日志保留在审计存储中，请通过受控运维渠道查阅");
            record.setLastError(record.getLastError() == null ? null : "历史错误详情请通过受控运维渠道查阅");
        }
    }

    /**
     * 根据持久运行状态生成脱敏诊断说明，隐藏原生脚本、参数和结果正文。
     *
     * @param record 运行记录
     */
    private void safeNativeDiagnostics(DevRecordDO record) {
        String status = java.util.Set.of("WAITING", "SUBMITTING", "UNKNOWN", "CANCEL_REQUESTED", "RUNNING", "SUCCESS",
                        "FAILED", "STOPPED")
                .contains(record.getStatus()) ? record.getStatus() : "UNKNOWN";
        String stage = "PREPARE".equals(record.getSubmissionStage()) ? "尚未发送执行请求"
                : "TRIGGER_SENT".equals(
                record.getSubmissionStage()) ? "已保存执行请求发送意图，正在核对调度器实例" : "正在核对持久化运行状态";
        String cancellation = Boolean.TRUE.equals(
                record.getCancelRequested()) ? "已请求取消；只在引擎终态确认后释放执行槽位" : "未请求取消";
        record.setLog("原生运行状态：" + status + "\n" + stage + "\n" + cancellation
                + "\n原生脚本、参数和结果回显正文已隐藏；调度器日志提供脱敏时间与级别");
        if ("UNKNOWN".equals(status)) {
            record.setLastError("调度器或原生引擎终态尚未确认；请等待恢复或核实集群终态后人工收敛");
        }
    }

    /**
     * 停止运行中的实例
     *
     * @param id 记录编号
     */
    @Override
    public void stopRecord(Long id) {
        DevRecordDO record = getRecord(id);
        if (!DevRecordDO.ACTIVE_STATUSES.contains(record.getStatus())) {
            throw exception(DEV_RECORD_NOT_RUNNING);
        }
        dsSubmitter.stop(id);
    }

    /**
     * 删除任务实例。
     *
     * @param id 记录编号
     */
    @Override
    public void deleteRecord(Long id) {
        DevRecordDO record = getRecord(id);
        if (DevRecordDO.ACTIVE_STATUSES.contains(record.getStatus())) {
            throw exception(DEV_TASK_RUNNING);
        }
        throw exception(DEV_TASK_DEPENDENCY_INVALID, "生产实例用于审计和依赖判定，请通过统一保留策略归档");
    }

    /**
     * 记录未知实例的人工核查结论。
     *
     * @param req 请求参数
     */
    @Override
    public void resolveUnknown(
            cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevRecordResolveReqVO req) {
        if (!Boolean.TRUE.equals(req.getConfirmedTerminated())
                || !DevRecordDO.STATUS_UNKNOWN.equals(req.getExpectedStatus())
                || !java.util.Set.of("SUCCESS", "FAILED", "STOPPED").contains(req.getStatus())
                || cn.hutool.core.util.StrUtil.isBlank(req.getReason())) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID, "仅可在人工核查后收敛未知实例，必须填写核查原因");
        }
        DevRecordDO record = getRecord(req.getId());
        Long actor = cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId();
        if (actor == null) {
            throw exception(DEV_EXECUTION_IDENTITY_REQUIRED);
        }
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        int changed = recordMapper.update(null,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate(DevRecordDO.class)
                        .set(DevRecordDO::getStatus, req.getStatus()).set(DevRecordDO::getEndTime, now)
                        .set(DevRecordDO::getResolvedBy, actor).set(DevRecordDO::getResolvedAt, now)
                        .set(DevRecordDO::getResolutionReason, req.getReason()).set(DevRecordDO::getLastError, null)
                        .set(DevRecordDO::getLeaseOwner, null).set(DevRecordDO::getLeaseUntil, null)
                        .eq(DevRecordDO::getId, record.getId()).eq(DevRecordDO::getStatus, req.getExpectedStatus())
                        .and(q -> q.isNull(DevRecordDO::getLeaseUntil).or().lt(DevRecordDO::getLeaseUntil, now)));
        if (changed != 1) {
            throw exception(DEV_TASK_REVISION_CONFLICT);
        }
    }

    /**
     * 核实原质检已结束，将未知质量保守收敛为失败。
     *
     * @param req 请求参数
     */
    @Override
    public void resolveQuality(
            cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevQualityResolveReqVO req) {
        if (!Boolean.TRUE.equals(req.getConfirmedTerminated()) || !"UNKNOWN".equals(req.getExpectedStatus())
                || cn.hutool.core.util.StrUtil.isBlank(req.getReason())) {
            throw exception(DEV_TASK_DEPENDENCY_INVALID, "必须核实质检原执行已结束并填写原因");
        }
        DevRecordDO record = getRecord(req.getId());
        Long actor = cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId();
        if (actor == null) {
            throw exception(DEV_EXECUTION_IDENTITY_REQUIRED);
        }
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        int changed = recordMapper.update(null,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaUpdate(DevRecordDO.class)
                        .set(DevRecordDO::getQualityStatus, "FAILED")
                        .set(DevRecordDO::getResolvedBy, actor).set(DevRecordDO::getResolvedAt, now)
                        .set(DevRecordDO::getResolutionReason,
                                cn.hutool.core.util.StrUtil.sub("质量异常核查：" + req.getReason(), 0, 1000))
                        .set(DevRecordDO::getLastError, "质量结果不可确认，已按失败收敛；须重新运行并通过质检")
                        .set(DevRecordDO::getLeaseOwner, null).set(DevRecordDO::getLeaseUntil, null)
                        .eq(DevRecordDO::getId, record.getId()).eq(DevRecordDO::getStatus, DevRecordDO.STATUS_SUCCESS)
                        .eq(DevRecordDO::getQualityStatus, req.getExpectedStatus())
                        .and(q -> q.isNull(DevRecordDO::getLeaseUntil).or().lt(DevRecordDO::getLeaseUntil, now)));
        if (changed != 1) {
            throw exception(DEV_TASK_REVISION_CONFLICT);
        }
    }

}

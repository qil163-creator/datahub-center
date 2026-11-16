package cn.iocoder.yudao.module.dataforge.service.runalert;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.runalert.RunAlertCandidate;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.runalert.RunAlertDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.runalert.RunAlertMapper;
import cn.iocoder.yudao.module.dataforge.framework.rpc.RunAlertSystemClients;
import cn.iocoder.yudao.module.system.api.mail.dto.MailSendSingleToUserReqDTO;
import cn.iocoder.yudao.module.system.api.notify.dto.NotifySendSingleToUserReqDTO;
import cn.iocoder.yudao.module.system.api.sms.dto.send.SmsSendSingleToUserReqDTO;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 任务运行失败通知：扫描平台失败运行 → 持久登记 → 租约认领后发送。
 *
 * <p>失败事实来自已提交的运行记录本身，扫描按“运行实例 + 事件”去重，各执行器无需改动，重复或并发扫描只会命中唯一约束。
 * 远程发送始终在数据库事务之外：站内信带稳定幂等键（{@value #IDEMPOTENCY_PREFIX}记录编号），结果未确认时可安全重发；
 * 短信、邮件没有去重契约，结果未确认即转为“结果未知”，等待人工处置，绝不自动重发。通知只告知任务失败，不触发重跑。</p>
 */
@Slf4j
@Service
public class RunAlertService {

    static final String TEMPLATE_NOTIFY = "dataforge_run_failed";
    static final String TEMPLATE_SMS = "dataforge_run_failed_sms";
    static final String TEMPLATE_MAIL = "dataforge_run_failed_mail";
    static final String IDEMPOTENCY_PREFIX = "dataforge:run-alert:";

    /** 发送租约秒数，须明显长于 Feign 连接加读取超时（15 秒） */
    static final int LEASE_SECONDS = 120;
    /** 确定未发出时的退避基数（秒），第 n 次失败后等待 n 倍 */
    static final int BACKOFF_SECONDS = 60;
    /** 单轮执行时间预算，避免与下一分钟的调度重叠过久 */
    static final long ROUND_BUDGET_SECONDS = 40;
    static final int CLEAN_LIMIT = 500;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Map<String, String> KIND_NAMES = Map.of(
            "DEVELOPMENT", "数据开发",
            "DATA_ACCESS", "数据同步",
            "ETL", "可视化ETL",
            "INTEGRATION", "数据集成",
            "JOB", "作业编排");

    @Resource
    private RunAlertMapper alerts;

    @Resource
    private RunAlertProperties properties;

    @Resource
    private AdminUserApi adminUserApi;

    @Resource
    private RunAlertSystemClients.NotifyClient notifyClient;

    @Resource
    private RunAlertSystemClients.SmsClient smsClient;

    @Resource
    private RunAlertSystemClients.MailClient mailClient;

    @Resource
    private PlatformTransactionManager transactionManager;

    /** 一轮执行结果 */
    public record RoundResult(boolean enabled, int registered, int delivered, int failed, int cleaned, long attention) {

        /**
         * 生成调度日志摘要。
         *
         * @return 摘要文本
         */
        public String summary() {
            if (!enabled) {
                return "任务失败通知已关闭";
            }
            return "登记失败运行=" + registered + "，送达=" + delivered + "，未送达=" + failed
                    + "，清理=" + cleaned + "，待人工处置=" + attention;
        }
    }

    /**
     * 执行一轮：登记新的失败运行、发送到期通知、清理过期记录。
     *
     * @return 本轮结果
     */
    public RoundResult runOnce() {
        if (!properties.isEnabled()) {
            return new RoundResult(false, 0, 0, 0, 0, 0);
        }
        properties.validate();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ROUND_BUDGET_SECONDS);
        int registered = registerFailedRuns(LocalDateTime.now());
        int delivered = 0;
        int failed = 0;
        for (RunAlertDO alert : alerts.selectDue(properties.getDispatchLimit())) {
            if (System.nanoTime() >= deadline) {
                break;
            }
            switch (deliver(alert.getId())) {
                case DELIVERED -> delivered++;
                case NOT_DELIVERED -> failed++;
                case SKIPPED -> { }
            }
        }
        int cleaned = alerts.deleteHandled(properties.getRetentionDays(), CLEAN_LIMIT);
        return new RoundResult(true, registered, delivered, failed, cleaned, alerts.countAttention());
    }

    /**
     * 登记扫描窗口内尚未登记的失败运行；每个运行的全部通知行在同一事务写入。
     *
     * <p>接收人查询失败时整轮抛出、不写任何登记，下一轮重新扫描，不会把运行误标为“已通知”。</p>
     *
     * @param now 当前时间（与运行记录同一 JVM 时钟）
     * @return 本轮登记的运行数
     */
    int registerFailedRuns(LocalDateTime now) {
        List<RunAlertCandidate> runs = alerts.selectFailedRuns(now.minusHours(properties.getLookbackHours()),
                properties.getScanLimit());
        if (runs.isEmpty()) {
            return 0;
        }
        Map<Long, AdminUserRespDTO> users = loadEnabledUsers(runs);
        int registered = 0;
        for (RunAlertCandidate run : runs) {
            List<RunAlertDO> rows = buildAlerts(run, users);
            Integer inserted = transaction().execute(status -> {
                int count = 0;
                for (RunAlertDO row : rows) {
                    count += alerts.insertIgnore(row);
                }
                return count;
            });
            if (inserted != null && inserted > 0) {
                registered++;
            }
        }
        return registered;
    }

    /** 查询候选接收人中启用的用户 */
    private Map<Long, AdminUserRespDTO> loadEnabledUsers(List<RunAlertCandidate> runs) {
        Set<Long> ids = new LinkedHashSet<>();
        runs.forEach(run -> ids.addAll(candidateReceivers(run)));
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<AdminUserRespDTO> list = adminUserApi.getUserList(ids).getCheckedData();
        Map<Long, AdminUserRespDTO> enabled = new HashMap<>();
        if (list != null) {
            for (AdminUserRespDTO user : list) {
                if (user != null && user.getId() != null
                        && Objects.equals(user.getStatus(), CommonStatusEnum.ENABLE.getStatus())) {
                    enabled.put(user.getId(), user);
                }
            }
        }
        return enabled;
    }

    /** 候选接收人按优先级：负责人、发布人、执行身份、创建人；去重并剔除非法编号 */
    static List<Long> candidateReceivers(RunAlertCandidate run) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Long id : Arrays.asList(run.getOwnerId(), run.getReleaseUserId(), run.getExecutionUserId(),
                run.getCreatorId())) {
            if (id != null && id > 0) {
                ids.add(id);
            }
        }
        return new ArrayList<>(ids);
    }

    /**
     * 构造一个失败运行的全部通知行：每个启用接收人一条站内信，附加渠道仅在用户留有手机号或邮箱时登记；
     * 没有启用接收人时登记一条死信，保证失败不会静默消失。
     */
    List<RunAlertDO> buildAlerts(RunAlertCandidate run, Map<Long, AdminUserRespDTO> users) {
        String params = JsonUtils.toJsonString(templateParams(run));
        List<Long> receivers = candidateReceivers(run).stream().filter(users::containsKey)
                .limit(properties.getMaxReceivers()).toList();
        List<RunAlertDO> rows = new ArrayList<>();
        if (receivers.isEmpty()) {
            RunAlertDO dead = row(run, RunAlertDO.NO_RECEIVER, RunAlertDO.CHANNEL_NOTIFY, params);
            dead.setStatus(RunAlertDO.STATUS_DEAD);
            dead.setLastError("NO_RECEIVER");
            rows.add(dead);
            return rows;
        }
        Set<String> extra = properties.getExtraChannels();
        for (Long receiver : receivers) {
            AdminUserRespDTO user = users.get(receiver);
            rows.add(row(run, receiver, RunAlertDO.CHANNEL_NOTIFY, params));
            if (extra.contains(RunAlertProperties.CHANNEL_SMS) && StrUtil.isNotBlank(user.getMobile())) {
                rows.add(row(run, receiver, RunAlertDO.CHANNEL_SMS, params));
            }
            if (extra.contains(RunAlertProperties.CHANNEL_MAIL) && StrUtil.isNotBlank(user.getEmail())) {
                rows.add(row(run, receiver, RunAlertDO.CHANNEL_MAIL, params));
            }
        }
        return rows;
    }

    private static RunAlertDO row(RunAlertCandidate run, Long receiver, int channel, String params) {
        return RunAlertDO.builder()
                .sourceKind(run.getSourceKind())
                .runId(run.getRunId())
                .taskId(run.getTaskId())
                .taskName(truncate(displayTaskName(run), 200))
                .runName(truncate(StrUtil.blankToDefault(run.getRunName(), String.valueOf(run.getRunId())), 512))
                .eventType(RunAlertDO.EVENT_FAILURE)
                .occurredTime(run.getOccurredTime())
                .receiverUserId(receiver)
                .channel(channel)
                .templateParams(params)
                .status(RunAlertDO.STATUS_PENDING)
                .build();
    }

    /** 模板参数：只含类型、任务名、实例名和失败时间，不含错误详情与日志 */
    static Map<String, Object> templateParams(RunAlertCandidate run) {
        Map<String, Object> params = new LinkedHashMap<>();
        String kindName = KIND_NAMES.getOrDefault(run.getSourceKind(), "数据研发");
        if ("ETL".equals(run.getSourceKind()) && StrUtil.isNotBlank(run.getIntegrationName())) {
            kindName = KIND_NAMES.get("INTEGRATION");
        }
        params.put("kindName", kindName);
        params.put("taskName", truncate(displayTaskName(run), 100));
        params.put("runName", truncate(StrUtil.blankToDefault(run.getRunName(), String.valueOf(run.getRunId())), 150));
        params.put("failTime", run.getOccurredTime() == null ? "-" : TIME_FORMAT.format(run.getOccurredTime()));
        return params;
    }

    private static String displayTaskName(RunAlertCandidate run) {
        if ("ETL".equals(run.getSourceKind()) && StrUtil.isNotBlank(run.getIntegrationName())) {
            return run.getIntegrationName();
        }
        return StrUtil.blankToDefault(run.getTaskName(), "任务 #" + run.getTaskId());
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    /** 单条通知的发送结果 */
    enum Outcome {
        /** 已送达 */
        DELIVERED,
        /** 本次未送达（将重试、死信或结果未知） */
        NOT_DELIVERED,
        /** 未认领到（已被其他节点处理、或刚转为死信/结果未知） */
        SKIPPED
    }

    /**
     * 认领并发送一条通知。
     *
     * @param id 通知记录编号
     * @return 发送结果
     */
    Outcome deliver(Long id) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("通知发送不能处于数据库事务中");
        }
        String token = IdUtil.fastSimpleUUID();
        int maxAttempts = properties.getMaxAttempts();
        RunAlertDO row = transaction().execute(status -> {
            if (alerts.expireUnconfirmed(id) == 1 || alerts.exhaust(id, maxAttempts) == 1) {
                return null;
            }
            return alerts.claim(id, token, LEASE_SECONDS, maxAttempts) == 1 ? alerts.selectById(id) : null;
        });
        if (row == null) {
            return Outcome.SKIPPED;
        }
        boolean idempotent = Objects.equals(row.getChannel(), RunAlertDO.CHANNEL_NOTIFY);
        CommonResult<Long> response;
        try {
            response = send(row);
        } catch (Exception ex) {
            log.warn("[deliver][失败通知({}) 渠道({}) 发送结果未确认：{}]", id, row.getChannel(), ex.getClass().getSimpleName());
            String error = "DELIVERY_UNCONFIRMED";
            transaction().executeWithoutResult(status -> {
                if (idempotent) {
                    alerts.retryLater(id, token, error, maxAttempts, BACKOFF_SECONDS);
                } else {
                    alerts.settle(id, token, RunAlertDO.STATUS_UNKNOWN, error);
                }
            });
            return Outcome.NOT_DELIVERED;
        }
        if (response != null && response.isSuccess() && response.getData() != null && response.getData() > 0) {
            Long receipt = response.getData();
            transaction().executeWithoutResult(status -> alerts.delivered(id, token, receipt));
            return Outcome.DELIVERED;
        }
        if (response != null && response.isSuccess()) {
            // 站内信模板停用时 system 返回空编号且不创建消息；重试无益，转死信待管理员启用模板后人工重发
            transaction().executeWithoutResult(status ->
                    alerts.settle(id, token, RunAlertDO.STATUS_DEAD, "TEMPLATE_DISABLED"));
            return Outcome.NOT_DELIVERED;
        }
        // system 明确返回业务错误：消息未创建，可按次数退避重试
        log.warn("[deliver][失败通知({}) 渠道({}) 被拒绝：{}]", id, row.getChannel(),
                response == null ? "EMPTY_RESPONSE" : response.getCode());
        String error = response == null ? "EMPTY_RESPONSE" : "REMOTE_REJECTED";
        transaction().executeWithoutResult(status -> {
            if (response == null && !idempotent) {
                alerts.settle(id, token, RunAlertDO.STATUS_UNKNOWN, error);
            } else {
                alerts.retryLater(id, token, error, maxAttempts, BACKOFF_SECONDS);
            }
        });
        return Outcome.NOT_DELIVERED;
    }

    private CommonResult<Long> send(RunAlertDO row) {
        Map<String, Object> params = JsonUtils.parseObject(row.getTemplateParams(),
                new TypeReference<Map<String, Object>>() { });
        int channel = row.getChannel();
        if (channel == RunAlertDO.CHANNEL_SMS) {
            SmsSendSingleToUserReqDTO request = new SmsSendSingleToUserReqDTO();
            request.setUserId(row.getReceiverUserId());
            request.setTemplateCode(TEMPLATE_SMS);
            request.setTemplateParams(params);
            return smsClient.sendSingleSmsToAdmin(request);
        }
        if (channel == RunAlertDO.CHANNEL_MAIL) {
            MailSendSingleToUserReqDTO request = new MailSendSingleToUserReqDTO();
            request.setUserId(row.getReceiverUserId());
            request.setTemplateCode(TEMPLATE_MAIL);
            request.setTemplateParams(params);
            return mailClient.sendSingleMailToAdmin(request);
        }
        NotifySendSingleToUserReqDTO request = new NotifySendSingleToUserReqDTO();
        request.setUserId(row.getReceiverUserId());
        request.setTemplateCode(TEMPLATE_NOTIFY);
        request.setTemplateParams(params);
        request.setIdempotencyKey(IDEMPOTENCY_PREFIX + row.getId());
        return notifyClient.sendSingleMessageToAdmin(request);
    }

    /**
     * 人工处置死信或结果未知的通知，仅供受控的 XXL-Job 人工任务调用。
     *
     * <p>同一调度执行身份重复提交同一处置视为幂等；无接收人的记录只能确认关闭。重新发送只是重发通知，不会重跑任务。</p>
     *
     * @param id 通知记录编号
     * @param action RETRY 或 ACKNOWLEDGE
     * @param reason 处置原因
     * @param command 调度执行身份（xxl-job:任务编号:日志编号）
     */
    public void dispose(Long id, String action, String reason, String command) {
        if (id == null || id <= 0 || !("RETRY".equals(action) || "ACKNOWLEDGE".equals(action))
                || StrUtil.isBlank(reason) || reason.length() > 500
                || command == null || !command.matches("xxl-job:[0-9]+:[0-9]+")) {
            throw new IllegalArgumentException("需要有效的通知编号、处置动作（RETRY/ACKNOWLEDGE）、原因（500 字以内）和调度执行身份");
        }
        transaction().executeWithoutResult(status -> {
            RunAlertDO row = alerts.selectForUpdate(id);
            if (row == null) {
                throw new IllegalArgumentException("通知记录不存在");
            }
            if (command.equals(row.getDispositionCommand())) {
                if (!action.equals(row.getDispositionAction()) || !reason.equals(row.getDispositionReason())) {
                    throw new IllegalStateException("同一调度执行已提交过不同的处置");
                }
                return;
            }
            int current = row.getStatus();
            if (current != RunAlertDO.STATUS_DEAD && current != RunAlertDO.STATUS_UNKNOWN) {
                throw new IllegalStateException("只有死信或结果未知的通知可以人工处置");
            }
            if ("RETRY".equals(action) && (row.getReceiverUserId() == null || row.getReceiverUserId() <= 0)) {
                throw new IllegalStateException("没有接收人的通知只能确认关闭");
            }
            if (alerts.dispose(id, action, reason, command) != 1) {
                throw new IllegalStateException("通知处置竞争，请稍后重试");
            }
        });
        log.info("[dispose][失败通知({}) 已人工处置：{}，执行身份 {}]", id, action, command);
    }

    private TransactionTemplate transaction() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setTimeout(10);
        return template;
    }

}

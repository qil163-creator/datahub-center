package cn.iocoder.yudao.module.governance.job.master;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.governance.dal.mysql.master.MasterDistributeAlertMapper;
import cn.iocoder.yudao.module.governance.service.master.MasterDistributeAlertService;
import cn.iocoder.yudao.module.governance.service.master.MasterDistributeRunCoordinator;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** 全局控制面有界扫描，逐条处理；不占用主数据派发心跳线程。 */
@Component
@Slf4j
public class MasterDistributeAlertJob {

    @Resource
    private MasterDistributeAlertMapper alerts;

    @Resource
    private MasterDistributeAlertService service;

    @Resource
    private MasterDistributeRunCoordinator coordinator;

    /**
     * 执行主数据分发告警任务处理流程，并维护相应运行结果。
     */
    @XxlJob("masterDistributeAlertRelayJob")
    public void execute() {
        int failed = 0;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        for (var run : alerts.missingTerminal()) {
            if (System.nanoTime() >= deadline) {
                break;
            }
            try {

                coordinator.reconcileAlert(run);
            } catch (Exception ex) {
                failed++;
                log.warn("主数据派发告警补账失败 runId={} type={}", run.getId(), ex.getClass().getSimpleName());
            }
        }
        int processed = 0;
        for (var alert : alerts.candidates()) {
            if (System.nanoTime() >= deadline) {
                break;
            }
            try {

                if (!service.deliver(alert.getId())) {
                    failed++;
                }
                processed++;
            } catch (Exception ex) {
                failed++;
                log.warn("主数据派发告警投递失败 alertId={} type={}", alert.getId(), ex.getClass().getSimpleName());
            }
        }
        long dead = alerts.deadCount();
        String summary = "处理告警=" + processed + "，失败=" + failed + "，待人工处置=" + dead;
        if (failed > 0 || dead > 0) {
            XxlJobHelper.handleFail(summary);
        } else {
            XxlJobHelper.handleSuccess(summary);
        }
    }

    /**
     * 按处置结论更新主数据分发告警任务记录并保存审计信息。
     */
    @XxlJob("masterDistributeAlertDispositionJob")
    public void dispose() {
        try {
            var request = JsonUtils.parseObject(XxlJobHelper.getJobParam(), Disposition.class);
            if (request == null) {
                throw new IllegalArgumentException("缺少参数");
            }

            String logFile = XxlJobHelper.getJobLogFileName();
            String name = logFile == null ? "" : Path.of(logFile).getFileName().toString();
            if (XxlJobHelper.getJobId() <= 0 || !name.matches("[0-9]+[.]log")) {
                throw new IllegalStateException("缺少受控调度执行身份");
            }
            String command = "xxl-job:" + XxlJobHelper.getJobId() + ":" + name.substring(0, name.length() - 4);
            service.dispose(request.getAlertId(), request.getAction(), request.getReason(), command);
            XxlJobHelper.handleSuccess("告警处置已记录；未改变业务执行状态");
        } catch (Exception ex) {
            XxlJobHelper.handleFail("告警处置失败，类型=" + ex.getClass().getSimpleName());
        }
    }

    /**
     * 清理超过保留期限的主数据分发告警记录。
     */
    @XxlJob("masterDistributeAlertCleanJob")
    public void clean() {
        int deleted = alerts.cleanDelivered();
        XxlJobHelper.handleSuccess("清理历史已确认且无原日志/处置审计的告警=" + deleted);
    }

    @Data
    public static class Disposition {

        private Long alertId;

        private String action;

        private String reason;
    }
}

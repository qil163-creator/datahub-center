package cn.iocoder.yudao.module.dataforge.job.push;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.DataPushAlertMapper;
import cn.iocoder.yudao.module.dataforge.service.push.DataPushAlertService;
import cn.iocoder.yudao.module.dataforge.service.push.DataPushRunCoordinator;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** 全局控制面有界扫描，逐条处理业务记录；不占用推送心跳线程。 */
@Component
@Slf4j
public class DataPushAlertJob {

    @Resource
    private DataPushAlertMapper alerts;

    @Resource
    private DataPushAlertService service;

    @Resource
    private DataPushRunCoordinator coordinator;

    /**
     * 执行数据推送告警任务处理。
     */
    @XxlJob("dataPushAlertRelayJob")

    public void execute() {
        int failed = 0;
        for (var run : alerts.missingUnknown()) {
            try {

                coordinator.reconcileAlert(run);
            } catch (Exception ex) {
                failed++;
                log.warn("推送告警补账失败 runId={} type={}", run.getId(), ex.getClass().getSimpleName());
            }
        }
        int processed = 0;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
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
                log.warn("推送告警投递失败 alertId={} type={}", alert.getId(), ex.getClass().getSimpleName());
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
     * 处置数据推送告警任务。
     */
    @XxlJob("dataPushAlertDispositionJob")
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
            XxlJobHelper.handleSuccess("告警处置已记录；未改变业务执行状态或水位");
        } catch (Exception ex) {
            XxlJobHelper.handleFail("告警处置失败，类型=" + ex.getClass().getSimpleName());
        }
    }

    /**
     * 清洗数据推送告警任务数据。
     */
    @XxlJob("dataPushAlertCleanJob")

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

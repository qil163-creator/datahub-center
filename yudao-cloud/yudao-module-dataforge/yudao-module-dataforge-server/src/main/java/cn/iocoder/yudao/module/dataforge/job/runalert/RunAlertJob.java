package cn.iocoder.yudao.module.dataforge.job.runalert;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.dataforge.service.runalert.RunAlertService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 任务运行失败通知。
 *
 * <p>{@code dataforgeRunAlertJob} 建议每分钟执行：单平台扫描数据开发、数据同步、ETL、数据集成、作业编排的失败运行并登记，
 * 再有界发送到期通知、清理过期记录。存在死信或结果未知的通知时本轮标记为失败，提醒运维到调度中心处置。
 * 多实例或重复触发只会命中登记唯一约束和发送租约，不会重复通知。</p>
 *
 * <p>{@code dataforgeRunAlertDispositionJob} 不配置 Cron，仅人工触发，参数示例：
 * {@code {"alertId":123,"action":"RETRY","reason":"已启用模板，重新发送"}}；action 取 RETRY（重新发送）或 ACKNOWLEDGE（确认关闭）。</p>
 */
@Component
public class RunAlertJob {

    @Resource
    private RunAlertService runAlertService;

    /**
     * 执行一轮失败通知登记、发送与清理。
     */
    @XxlJob("dataforgeRunAlertJob")
    public void execute() {
        RunAlertService.RoundResult result = runAlertService.runOnce();
        if (result.failed() > 0 || result.attention() > 0) {
            XxlJobHelper.handleFail(result.summary());
        } else {
            XxlJobHelper.handleSuccess(result.summary());
        }
    }

    /**
     * 人工处置一条死信或结果未知的失败通知；执行身份取自调度中心本次执行，不接受参数自报。
     */
    @XxlJob("dataforgeRunAlertDispositionJob")
    public void dispose() {
        try {
            Disposition request = JsonUtils.parseObject(XxlJobHelper.getJobParam(), Disposition.class);
            if (request == null) {
                throw new IllegalArgumentException("缺少参数");
            }
            String logFile = XxlJobHelper.getJobLogFileName();
            String name = logFile == null ? "" : Path.of(logFile).getFileName().toString();
            if (XxlJobHelper.getJobId() <= 0 || !name.matches("[0-9]+[.]log")) {
                throw new IllegalStateException("缺少受控调度执行身份");
            }
            String command = "xxl-job:" + XxlJobHelper.getJobId() + ":" + name.substring(0, name.length() - 4);
            runAlertService.dispose(request.getAlertId(), request.getAction(), request.getReason(), command);
            XxlJobHelper.handleSuccess("已处置失败通知 " + request.getAlertId() + "：" + request.getAction());
        } catch (RuntimeException ex) {
            XxlJobHelper.handleFail(ex.getMessage());
        }
    }

    /** 人工处置参数 */
    @Data
    public static class Disposition {

        /** 通知记录编号 */
        private Long alertId;

        /** RETRY 或 ACKNOWLEDGE */
        private String action;

        /** 处置原因 */
        private String reason;
    }

}

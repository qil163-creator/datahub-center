package cn.iocoder.yudao.module.dataforge.job.lineage;

import cn.iocoder.yudao.module.dataforge.service.lineage.LineageReportService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * 血缘上报任务：单平台扫描开发任务、数据同步、集成任务与 ETL 流程中修订号或解析器版本变化的对象，
 * 每类每轮最多 {@value LineageReportService#DEFAULT_BATCH} 个，串行处理。
 * <p>
 * 处理器保持薄、可重试：governance 侧按修订号整体替换，多实例或重复触发只会得到 NOOP/STALE。
 */
@Component
public class LineageReportJob {

    @Resource
    private LineageReportService reportService;

    /**
     * 执行一轮血缘上报。
     */
    @XxlJob("lineageReportJob")
    public void execute() {
        String parameter = XxlJobHelper.getJobParam();
        if (parameter == null || parameter.isBlank()) {
            XxlJobHelper.handleSuccess(reportService.reportPending(LineageReportService.DEFAULT_BATCH));
            return;
        }
        String[] target = parameter.trim().split(":", -1);
        if (target.length != 2 || !target[1].matches("[1-9][0-9]*")) {
            XxlJobHelper.handleFail("请使用 来源类型:正整数编号，例如 DEV_TASK:123");
            return;
        }
        try {
            XxlJobHelper.handleSuccess(reportService.reportSource(target[0], Long.parseLong(target[1])));
        } catch (IllegalArgumentException invalid) {
            XxlJobHelper.handleFail("无效的血缘来源类型或编号");
        }
    }
}

package cn.iocoder.yudao.module.dataforge.job.database;

import cn.iocoder.yudao.module.dataforge.service.database.DatabaseMonitorService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 单平台串行采样，按数据库逐项限制外库连接。 */
@Component
@RequiredArgsConstructor
public class DatabaseMonitorCollectJob {

    private final DatabaseMonitorService monitorService;

    /**
     * 执行数据源监控收集任务处理。
     */
    @XxlJob("databaseMonitorCollectJob")
    public void execute() {
        try {
            int collected = monitorService.collectPlatform();
            XxlJobHelper.handleSuccess("数据库观测采样完成，已处理：" + collected);
        } catch (Exception ignored) {
            // 不输出外库异常中的连接信息或凭据。
            XxlJobHelper.handleFail("数据库观测采样失败");
        }
    }
}

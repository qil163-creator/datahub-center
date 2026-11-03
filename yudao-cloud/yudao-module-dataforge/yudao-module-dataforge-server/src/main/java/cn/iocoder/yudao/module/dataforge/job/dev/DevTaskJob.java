package cn.iocoder.yudao.module.dataforge.job.dev;

import cn.iocoder.yudao.module.dataforge.service.dev.DevDsSubmitter;
import cn.iocoder.yudao.module.dataforge.service.dev.DevScheduleCoordinator;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/** 兼容既有维护任务入口；业务周期由 DS 调度，此任务只做绑定同步、触发实例导入和执行恢复。 */
@Component
public class DevTaskJob {

    @Resource
    private DevScheduleCoordinator schedules;

    @Resource
    private DevDsSubmitter submitter;

    /**
     * 执行开发任务任务处理。
     */
    @XxlJob("devTaskJob")
    public void execute() {
        schedules.maintain();
        submitter.processPending();
        XxlJobHelper.handleSuccess("DS 调度绑定与触发实例维护完成");
    }
}

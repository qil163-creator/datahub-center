package cn.iocoder.yudao.module.dataforge.job.jobmanagement;

import cn.iocoder.yudao.module.dataforge.service.job.JobQueueCoordinator;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/** 作业级持久周期、并发队列和真实父子恢复的薄控制面入口。 */
@Component
public class JobManagementCoordinatorJob {
    @Resource private JobQueueCoordinator coordinator;

    /** 每次有界维护；基础调用失败通过 XXL 回调明确报告，不依赖浏览器刷新。 */
    @XxlJob("jobManagementCoordinatorJob")
    public void execute() {
        int failures=coordinator.maintain();
        if(failures>0)XxlJobHelper.handleFail("作业协调完成，失败或待重试基础项 "+failures+"；已受理记录保留，不盲目重投");
        else XxlJobHelper.handleSuccess("作业持久周期、队列与真实父子实例维护完成；业务结果请查看各运行记录");
    }
}

package cn.iocoder.yudao.module.dataforge.job.push;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushTaskDO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.push.DataPushTaskLogDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.DataPushTaskMapper;
import cn.iocoder.yudao.module.dataforge.dal.mysql.push.DataPushTaskLogMapper;
import cn.iocoder.yudao.module.dataforge.service.push.DataPushExecutor;
import cn.iocoder.yudao.module.dataforge.service.push.DataPushRunCoordinator;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 推送持久意图派发及周期任务受理。全局只读扫描后逐条处理业务记录；每轮两组各最多 200 个。
 *
 * 建议在 XXL-Job 控制台按每分钟执行：0 * * * * ?
 *
 * @author Li
 */
@Slf4j
@Component
public class DataPushSyncJob {

    @Resource
    private DataPushTaskMapper taskMapper;

    @Resource
    private DataPushExecutor pushExecutor;

    @Resource
    private DataPushTaskLogMapper logMapper;

    @Resource
    private DataPushRunCoordinator runCoordinator;

    /**
     * 执行数据推送同步任务处理。
     */
    @XxlJob("dataPushSyncJob")

    public void execute() {
        List<DataPushTaskLogDO> pending = logMapper.selectDispatchCandidates();
        int dispatched = 0;
        int failed = 0;
        for (DataPushTaskLogDO run : pending) {
            try {

                if (pushExecutor.dispatch(run)) {
                    dispatched++;
                }
            } catch (Exception ex) {
                failed++;
                log.warn("[execute][推送运行({}) 派发失败，持久意图保留；类型={}]", run.getId(),
                        ex.getClass().getSimpleName());
            }
        }
        List<DataPushTaskDO> dueList = taskMapper.selectDueList(logMapper.databaseNow());
        int accepted = 0;
        for (DataPushTaskDO task : dueList) {
            try {

                DataPushTaskLogDO run = runCoordinator.enqueueScheduled(
                        task.getId(), task.getScheduleGeneration(), task.getNextRunTime());
                if (run != null) {
                    accepted++;
                    if (pushExecutor.dispatch(run)) {
                        dispatched++;
                    }
                }
            } catch (Exception ex) {
                failed++;
                log.warn("[execute][推送任务({}) 受理或派发失败，未受理排期不推进；类型={}]", task.getId(),
                        ex.getClass().getSimpleName());
            }
        }
        String summary = String.format("待派发 %s，到期 %s，新受理 %s，入队 %s，失败 %s",
                pending.size(), dueList.size(), accepted, dispatched, failed);
        if (failed > 0) {
            XxlJobHelper.handleFail(summary);
        } else {
            XxlJobHelper.handleSuccess(summary);
        }
    }

}

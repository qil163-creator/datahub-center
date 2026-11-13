package cn.iocoder.yudao.module.dataforge.job.fill;

import cn.iocoder.yudao.module.dataforge.dal.dataobject.fill.FillMergeBatchDO;
import cn.iocoder.yudao.module.dataforge.dal.mysql.fill.FillMergeBatchMapper;
import cn.iocoder.yudao.module.dataforge.service.fill.FillMergeService;
import cn.iocoder.yudao.module.dataforge.service.fill.FillMergeServiceImpl;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 在线填报-合并执行 Job
 *
 * 建议在 XXL-Job 控制台按每分钟执行配置。
 * 扫描待执行/到期重试的合并批次，按批次执行（executeBatch 内部做抢占 CAS）；
 * 审批通过的组织独立合并，不等待其他部门完成
 *
 * @author Li
 */
@Component
@Slf4j
public class FillMergeJob {

    private static final int SCAN_LIMIT = 20;

    @Resource
    private FillMergeBatchMapper mergeBatchMapper;

    @Resource
    private FillMergeService mergeService;

    /**
     * 执行填报合并任务处理。
     */
    @XxlJob("fillMergeJob")

    public void execute() {
        LocalDateTime now = LocalDateTime.now();
        // 含进程中断遗留的超时“执行中”批次；失败批次超过自动重试上限后不再拾取
        List<FillMergeBatchDO> batches = mergeBatchMapper.selectListToExecute(now,
                now.minusMinutes(FillMergeServiceImpl.STALE_RUNNING_MINUTES),
                FillMergeServiceImpl.MAX_AUTO_RETRY, SCAN_LIMIT);
        int executed = 0;
        for (FillMergeBatchDO batch : batches) {
            try {
                mergeService.executeBatch(batch.getId());
                executed++;
            } catch (Exception ex) {
                log.error("[execute][合并批次({}) 执行异常]", batch.getId(), ex);
            }
        }
        XxlJobHelper.handleSuccess(String.format("扫描到 %s 个待合并批次，处理 %s 个", batches.size(), executed));
    }

}

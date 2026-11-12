package cn.iocoder.yudao.module.governance.job.profile;

import cn.iocoder.yudao.module.governance.service.profile.ProfileRunService;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * 数据探查执行与失联恢复：按 CAS 领取待执行或租约过期的记录，建议每 30 秒~1 分钟调度一次。
 * 处理器保持薄，可重复调度；业务幂等由记录状态与租约保证。
 *
 * @author Li
 */
@Component
public class ProfileRunJob {

    @Resource
    private ProfileRunService profileRunService;

    /**
     * 执行一轮扫描。
     */
    @XxlJob("profileRunJob")
    public void execute() {
        profileRunService.processDueRuns();
    }

}

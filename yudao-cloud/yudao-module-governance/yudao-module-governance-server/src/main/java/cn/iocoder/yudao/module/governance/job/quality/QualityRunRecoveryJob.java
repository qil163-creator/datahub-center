package cn.iocoder.yudao.module.governance.job.quality;

import cn.iocoder.yudao.module.governance.service.quality.QualityRunService;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/** 恢复已持久化的待执行质检，建议每分钟调用。 */
@Component
public class QualityRunRecoveryJob {

    @Resource
    private QualityRunService service;

    /**
     * 执行质量运行恢复任务处理流程，并维护相应运行结果。
     */
    @XxlJob("qualityRunRecoveryJob")
    public void execute() {
        service.recover();
    }
}

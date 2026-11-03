package cn.iocoder.yudao.module.governance.job.quality;

import cn.iocoder.yudao.module.governance.service.quality.QualityWorkOrderService;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

@Component
public class QualityWorkOrderJob {

    @Resource
    private QualityWorkOrderService workOrderService;

    /**
     * 扫描待处理质量工单并评估已到达的复检结果。
     */
    @XxlJob("qualityWorkOrderRecheckJob")
    public void evaluateRechecks() {
        workOrderService.evaluateRechecks();
    }

    /**
     * 派发通知。
     */
    @XxlJob("qualityWorkOrderNotifyJob")
    public void dispatchNotifies() {
        workOrderService.dispatchDueNotifies();
    }

}

package cn.iocoder.yudao.module.governance.job.quality;

import cn.iocoder.yudao.module.governance.service.quality.QualityWorkOrderService;
import com.xxl.job.core.context.XxlJobHelper;
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
        String param = XxlJobHelper.getJobParam();
        if (param == null || param.isBlank()) {
            workOrderService.evaluateRechecks();
            return;
        }
        long workOrderId = Long.parseLong(param.trim());
        if (workOrderId <= 0) {
            throw new IllegalArgumentException("工单编号必须为正整数");
        }
        workOrderService.evaluateRechecks(workOrderId);
    }

    /**
     * 派发通知。
     */
    @XxlJob("qualityWorkOrderNotifyJob")
    public void dispatchNotifies() {
        workOrderService.dispatchDueNotifies();
    }

}

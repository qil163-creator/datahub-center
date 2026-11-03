package cn.iocoder.yudao.module.supply.job;

import cn.iocoder.yudao.module.supply.service.dataapi.DataApiOperationService;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/** 平台范围扫描；只把失联执行标记为未知，不重新执行目标库SQL。 */
@Component
public class SupplyApiOperationReconcileJob {

    @Resource
    private DataApiOperationService service;

    /**
     * 执行供应API操作对账任务处理。
     */
    @XxlJob("supplyApiOperationReconcileJob")

    public void execute() {
        service.reconcileExpired();
    }
}

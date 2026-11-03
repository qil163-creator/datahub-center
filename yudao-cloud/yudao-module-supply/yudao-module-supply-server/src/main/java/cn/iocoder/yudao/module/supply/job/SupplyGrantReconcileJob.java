package cn.iocoder.yudao.module.supply.job;

import cn.iocoder.yudao.module.supply.service.app.SupplyAppService;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * 授权幂等操作对账：平台范围扫描 UNKNOWN 记录。
 */
@Component
public class SupplyGrantReconcileJob {

    @Resource
    private SupplyAppService supplyAppService;

    /**
     * 根据持久意图和当前外部状态核对执行结果。
     */
    @XxlJob("supplyGrantReconcileJob")

    public void reconcile() {
        supplyAppService.reconcileUnknownGrantOps();
    }

}

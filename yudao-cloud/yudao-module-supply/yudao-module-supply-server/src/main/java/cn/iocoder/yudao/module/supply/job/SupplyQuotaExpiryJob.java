package cn.iocoder.yudao.module.supply.job;

import cn.iocoder.yudao.module.supply.service.app.SupplyQuotaService;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/** 平台范围释放过期读请求预占；旧工作者确认被永久业务键和状态阻断。 */
@Component
public class SupplyQuotaExpiryJob {

    @Resource
    private SupplyQuotaService service;

    /**
     * 执行供应额度到期时间任务处理。
     */
    @XxlJob("supplyQuotaExpiryJob")
    public void execute() {
        for (int i = 0; i < 10 && service.expire() == 100; i++) { /* 每次最多1000条 */ }
    }
}

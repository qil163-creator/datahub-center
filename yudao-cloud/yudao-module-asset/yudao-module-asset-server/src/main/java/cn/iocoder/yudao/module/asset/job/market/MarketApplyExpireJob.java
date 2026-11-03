package cn.iocoder.yudao.module.asset.job.market;

import cn.iocoder.yudao.module.asset.service.market.MarketApplyService;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 数据集市申请到期失效与交付重试。
 * XXL-Job 控制台登记 handler = marketApplyExpireJob，建议每小时。
 * 按平台执行一次；并发去重由业务状态和数据库约束保证。
 */
@Slf4j
@Component
public class MarketApplyExpireJob {

    @Resource
    private MarketApplyService marketApplyService;

    /**
     * 执行市场资产申请过期任务处理流程，并维护相应运行结果。
     */
    @XxlJob("marketApplyExpireJob")

    public void execute() {
        boolean failed = false;
        try {
            int expired = marketApplyService.expireDueApplies();
            log.info("[marketApplyExpireJob] phase={} count={}", "expireDueApplies", expired);
        } catch (RuntimeException exception) {
            failed = true;
            log.warn("[marketApplyExpireJob] phase={} exception={}",
                    "expireDueApplies", exception.getClass().getName());
        }

        // 到期处理失败不能阻断已有交付意图的重试；Error 保持原样向外传播。
        try {
            int retried = marketApplyService.retryDeliveries();
            log.info("[marketApplyExpireJob] phase={} count={}", "retryDeliveries", retried);
        } catch (RuntimeException exception) {
            failed = true;
            log.warn("[marketApplyExpireJob] phase={} exception={}",
                    "retryDeliveries", exception.getClass().getName());
        }

        if (failed) {
            // 调度器会输出异常链：只报告失败，不传递原始消息、cause 或 suppressed。
            throw new IllegalStateException("marketApplyExpireJob failed");
        }
    }

}

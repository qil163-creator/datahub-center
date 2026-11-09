package cn.iocoder.yudao.module.asset.job.monitor;

import cn.iocoder.yudao.module.asset.service.monitor.AssetOperationService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** 每日计算资产运营指标快照（近 30 天使用情况、热度与价值评估），同日重跑整体替换。 */
@Component
@RequiredArgsConstructor
public class AssetOperationMetricJob {

    private final AssetOperationService operationService;

    /**
     * 计算今天的资产运营指标。
     */
    @XxlJob("assetOperationMetricJob")
    public void execute() {
        int count = operationService.refresh(LocalDate.now());
        XxlJobHelper.handleSuccess("资产运营指标已计算，共 " + count + " 个资产");
    }
}

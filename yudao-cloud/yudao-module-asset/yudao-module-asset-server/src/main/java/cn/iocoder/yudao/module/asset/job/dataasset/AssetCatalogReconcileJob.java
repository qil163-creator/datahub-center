package cn.iocoder.yudao.module.asset.job.dataasset;

import cn.iocoder.yudao.module.asset.service.dataasset.AssetCatalogLinkService;
import cn.iocoder.yudao.module.asset.service.dataasset.AssetSupplyStopService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 按平台扫描；多实例由交付领取令牌和申请 CAS 去重，失败不阻断另一阶段。 */
@Component
@RequiredArgsConstructor
public class AssetCatalogReconcileJob {

    private final AssetCatalogLinkService links;

    private final AssetSupplyStopService stops;

    /**
     * 执行资产目录对账任务处理流程，并维护相应运行结果。
     */
    @XxlJob("assetCatalogReconcileJob")
    public void execute() {
        boolean failed = false;
        try {
            links.reconcileDue();
        } catch (RuntimeException failure) {
            failed = true;
        }
        try {
            stops.reconcileDue();
        } catch (RuntimeException failure) {
            failed = true;
        }
        if (failed) {
            throw new IllegalStateException("资产目录对账部分失败，待重试");
        }
    }
}

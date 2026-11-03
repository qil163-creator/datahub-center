package cn.iocoder.yudao.module.asset.service.monitor;

import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.DataAssetDO;

/**
 * 资产使用行为日志 Service 接口
 */
public interface AssetMonitorService {

    /**
     * 记录资产使用行为日志（浏览 / 申请 / 使用），失败不影响主流程
     *
     * @param asset  资产
     * @param action 行为类型，见 {@link cn.iocoder.yudao.module.asset.dal.dataobject.monitor.AssetUsageLogDO}
     */
    void recordUsageLog(DataAssetDO asset, int action);

}

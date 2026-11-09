package cn.iocoder.yudao.module.asset.service.monitor;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.monitor.vo.AssetInventoryRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.monitor.vo.AssetOperationPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.monitor.vo.AssetOperationRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.monitor.vo.AssetOperationTrendRespVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 资产运营：热度/使用分析、价值评估与盘点报告。
 */
public interface AssetOperationService {

    /** 统计窗口天数 */
    int WINDOW_DAYS = 30;

    /** 快照保留天数 */
    int RETENTION_DAYS = 400;

    /**
     * 计算并整体替换指定日期的运营指标快照；多节点并发时只有一个执行，其余直接报错。
     *
     * @param statDate 统计日期
     * @return 写入的资产数
     */
    int refresh(LocalDate statDate);

    /**
     * 运营指标分页（默认最近一次快照）。
     *
     * @param reqVO 分页与筛选
     * @return 分页结果
     */
    PageResult<AssetOperationRespVO> getPage(AssetOperationPageReqVO reqVO);

    /**
     * 按筛选条件导出全部运营指标。
     *
     * @param reqVO 筛选条件（分页参数忽略）
     * @return 指标列表
     */
    List<AssetOperationRespVO> getExportList(AssetOperationPageReqVO reqVO);

    /**
     * 单个资产的逐日使用与评分趋势。
     *
     * @param assetId 资产编号
     * @param days 天数（7-180）
     * @return 趋势
     */
    AssetOperationTrendRespVO getTrend(Long assetId, int days);

    /**
     * 资产盘点报告（默认最近一次快照）。
     *
     * @param statDate 统计日期，可空
     * @return 盘点报告
     */
    AssetInventoryRespVO getInventory(LocalDate statDate);

}

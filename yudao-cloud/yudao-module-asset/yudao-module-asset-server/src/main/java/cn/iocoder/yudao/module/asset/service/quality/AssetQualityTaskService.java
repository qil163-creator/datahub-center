package cn.iocoder.yudao.module.asset.service.quality;

import cn.iocoder.yudao.module.asset.controller.admin.quality.vo.AssetQualityTaskRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.quality.vo.AssetQualityTaskSaveReqVO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface AssetQualityTaskService {

    /**
     * 创建资产质量任务。
     */
    Long createTask(AssetQualityTaskSaveReqVO reqVO);

    /**
     * 更新资产质量任务。
     */
    void updateTask(AssetQualityTaskSaveReqVO reqVO);

    /**
     * 删除资产质量任务。
     */
    void deleteTask(Long id);

    /**
     * 获得资产质量任务列表。
     */
    List<AssetQualityTaskRespVO> getTaskList(Long assetId);

    /**
     * 读取任务数量。
     */
    Map<Long, Integer> getTaskCounts(Collection<Long> assetIds);

    /**
     * 按资产编号删除资产质量任务记录。
     */
    void deleteByAssetId(Long assetId);

    /**
     * 执行资产质量任务（复用治理质量引擎）
     *
     * @return 治理质量任务实例编号
     */
    Long executeTask(Long id);

}

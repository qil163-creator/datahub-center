package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task.QualityTaskPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task.QualityTaskRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.task.QualityTaskSaveReqVO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityAssetSyncReqDTO;
import cn.iocoder.yudao.module.governance.api.quality.dto.QualityTaskResultRespDTO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskDO;

import java.util.List;

/**
 * 数据治理-数据质量-质量任务 Service 接口
 *
 * @author Li
 */
public interface QualityTaskService {

    /**
     * 创建质量任务。
     */
    Long createTask(QualityTaskSaveReqVO createReqVO);

    /**
     * 更新质量任务。
     */
    void updateTask(QualityTaskSaveReqVO updateReqVO);

    /**
     * 更新任务状态（上线/下线）
     */
    void updateTaskStatus(Long id, Integer taskStatus);

    /**
     * 删除质量任务。
     */
    void deleteTask(Long id);

    /**
     * 获得质量任务详情。
     */
    QualityTaskDO getTask(Long id);

    /**
     * 任务详情（含关联对象/规则编号、最近运行情况）
     */
    QualityTaskRespVO getTaskDetail(Long id);

    /**
     * 获得质量任务分页。
     */
    PageResult<QualityTaskRespVO> getTaskPage(QualityTaskPageReqVO pageReqVO);

    /**
     * 获得质量任务精简列表。
     */
    List<QualityTaskDO> getTaskSimpleList();

    /**
     * 手动执行一次
     *
     * @return 任务实例编号
     */
    Long executeTask(Long id);

    /**
     * 调度触发执行（XXL-Job 扫描 cron 到期后调用）
     */
    void executeTaskBySchedule(Long id);

    /**
     * 资产质量任务同步为治理质量任务（编码 ASSET-{assetTaskId}）
     */
    Long syncFromAsset(QualityAssetSyncReqDTO reqDTO);

    /**
     * 最近一次执行结果；无实例时返回 null
     */
    QualityTaskResultRespDTO getLatestResult(Long taskId);

}

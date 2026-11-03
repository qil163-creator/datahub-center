package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.instance.QualityTaskInstancePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.instance.QualityTaskInstanceRespVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskInstanceDO;

/**
 * 数据治理-数据质量-任务实例 Service 接口（只读）
 *
 * @author Li
 */
public interface QualityTaskInstanceService {

    /**
     * 获得任务实例。
     */
    QualityTaskInstanceDO getInstance(Long id);

    /**
     * 获得任务实例详情，附带任务名称与 ETL 批次来源（仅 ETL 受控批次触发的实例有值）
     *
     * @param id 实例编号
     * @return 实例详情；实例不存在时抛出 {@code QUALITY_TASK_INSTANCE_NOT_EXISTS}
     */
    QualityTaskInstanceRespVO getInstanceDetail(Long id);

    /**
     * 获得任务实例分页。
     */
    PageResult<QualityTaskInstanceRespVO> getInstancePage(QualityTaskInstancePageReqVO pageReqVO);

}

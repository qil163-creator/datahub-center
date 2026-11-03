package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.repair.QualityRepairOrderPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.repair.QualityRepairOrderSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRepairOrderDO;

/**
 * 数据治理-数据质量-修复工单 Service 接口
 *
 * @author Li
 */
public interface QualityRepairOrderService {

    /**
     * 创建修复工单。
     */
    Long createRepairOrder(QualityRepairOrderSaveReqVO createReqVO);

    /**
     * 更新修复工单。
     */
    void updateRepairOrder(QualityRepairOrderSaveReqVO updateReqVO);

    /**
     * 删除修复工单。
     */
    void deleteRepairOrder(Long id);

    /**
     * 获得修复工单。
     */
    QualityRepairOrderDO getRepairOrder(Long id);

    /**
     * 获得修复工单分页。
     */
    PageResult<QualityRepairOrderDO> getRepairOrderPage(QualityRepairOrderPageReqVO pageReqVO);

    /**
     * 按修复配置生成 SQL 预览（不落库）
     */
    String previewRepairSql(QualityRepairOrderSaveReqVO reqVO);

    /**
     * 审批：通过/拒绝
     */
    void approveRepairOrder(Long id, String approveStatus);

    /** 内容或物理目标变更后，由编辑人员撤销旧批准并重新送审。 */
    void resubmitRepairOrder(Long id);

    /**
     * 执行修复：真实回源执行修复 SQL，回写执行状态与结果
     */
    void executeRepairOrder(Long id);

    /**
     * 验证：验证通过/验证未通过
     */
    void verifyRepairOrder(Long id, String verifyStatus);

}

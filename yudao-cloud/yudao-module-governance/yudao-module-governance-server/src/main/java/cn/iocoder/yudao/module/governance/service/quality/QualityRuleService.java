package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.rule.QualityRulePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.rule.QualityRuleSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleDO;

import java.util.List;

/**
 * 数据治理-数据质量-质量规则 Service 接口
 *
 * @author Li
 */
public interface QualityRuleService {

    /**
     * 创建质量规则。
     */
    Long createQualityRule(QualityRuleSaveReqVO createReqVO);

    /**
     * 更新质量规则。
     */
    void updateQualityRule(QualityRuleSaveReqVO updateReqVO);

    /**
     * 更新质量规则状态。
     */
    void updateQualityRuleStatus(Long id, Integer status);

    /**
     * 删除质量规则。
     */
    void deleteQualityRule(Long id);

    /**
     * 获得质量规则。
     */
    QualityRuleDO getQualityRule(Long id);

    /**
     * 获得质量规则分页。
     */
    PageResult<QualityRuleDO> getQualityRulePage(QualityRulePageReqVO pageReqVO);

    /**
     * 全部启用规则精简列表（规则集选择器用）
     */
    List<QualityRuleDO> getQualityRuleSimpleList();

    /**
     * 启用规则精简列表；传入标准数据元编号时只返回该标准物化的规则。
     */
    List<QualityRuleDO> getQualityRuleSimpleList(Long standardElementId);

}

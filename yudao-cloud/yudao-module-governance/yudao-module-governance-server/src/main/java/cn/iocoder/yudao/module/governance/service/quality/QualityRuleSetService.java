package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.ruleset.QualityRuleSetItemSaveReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.ruleset.QualityRuleSetPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.ruleset.QualityRuleSetRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.ruleset.QualityRuleSetSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleSetDO;

import java.util.List;

/**
 * 数据治理-数据质量-规则集 Service 接口
 *
 * @author Li
 */
public interface QualityRuleSetService {

    /**
     * 创建规则集。
     */
    Long createRuleSet(QualityRuleSetSaveReqVO createReqVO);

    /**
     * 更新规则集。
     */
    void updateRuleSet(QualityRuleSetSaveReqVO updateReqVO);

    /**
     * 更新规则集状态。
     */
    void updateRuleSetStatus(Long id, Integer status);

    /**
     * 删除规则集。
     */
    void deleteRuleSet(Long id);

    /**
     * 获得规则集。
     */
    QualityRuleSetDO getRuleSet(Long id);

    /**
     * 获得规则集分页。
     */
    PageResult<QualityRuleSetRespVO> getRuleSetPage(QualityRuleSetPageReqVO pageReqVO);

    /**
     * 获得规则集精简列表（启用状态）。
     */
    List<QualityRuleSetDO> getRuleSetSimpleList();

    /**
     * 规则集明细列表（带规则名称/类型/维度）
     */
    List<QualityRuleSetRespVO.Item> getRuleSetItems(Long ruleSetId);

    /**
     * 保存规则集规则（全量覆盖）
     */
    void saveRuleSetItems(QualityRuleSetItemSaveReqVO reqVO);

}

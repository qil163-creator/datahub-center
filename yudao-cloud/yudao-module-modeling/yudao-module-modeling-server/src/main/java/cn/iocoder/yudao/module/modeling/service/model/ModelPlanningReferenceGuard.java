package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.module.modeling.dal.mysql.model.*;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.*;
import cn.iocoder.yudao.module.modeling.dal.dataobject.conceptmodel.ConceptModelDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.indicator.IndicatorModelDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.conceptmodel.ConceptModelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.indicator.IndicatorModelMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.MODEL_REFERENCE_IN_USE;

/** 规划对象引用守卫；平台内全可见，查询保留未删除与可用状态检查。 */
@Component
public class ModelPlanningReferenceGuard {

    @Resource
    private cn.iocoder.yudao.module.modeling.dal.mysql.businesscondition.BusinessConditionMapper conditions;

    @Resource
    private cn.iocoder.yudao.module.modeling.dal.mysql.planning.BizCategoryDataDomainRelMapper categories;

    @Resource
    private LogicModelMapper logics;

    @Resource
    private ConceptModelMapper concepts;

    @Resource
    private IndicatorModelMapper indicators;

    @Resource
    private cn.iocoder.yudao.module.modeling.dal.mysql.businessentity.BusinessEntityMapper entities;

    /**
     * 校验分层未使用。
     *
     * @param id 记录编号
     */
    public void requireLayerUnused(Long id) {
        reject(logics.selectCount(LogicModelDO::getLayerId, id));
    }

    /**
     * 校验数据域未使用。
     *
     * @param id 记录编号
     */
    public void requireDataDomainUnused(Long id) {
        reject(entities.selectCount(
                cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity.BusinessEntityDO::getDataDomainId, id));
        reject(conditions.selectCount(
                cn.iocoder.yudao.module.modeling.dal.dataobject.businesscondition.BusinessConditionDO::getDataDomainId,
                id));
        reject(categories.selectCount(
                cn.iocoder.yudao.module.modeling.dal.dataobject.planning.BizCategoryDataDomainRelDO::getDataDomainId,
                id));
        reject(logics.selectCount(LogicModelDO::getDataDomainId, id));
        reject(concepts.selectCount(ConceptModelDO::getDataDomainId, id));
        reject(indicators.selectCount(IndicatorModelDO::getDataDomainId, id));
    }

    /**
     * 校验主题域未使用。
     *
     * @param id 记录编号
     */
    public void requireSubjectDomainUnused(Long id) {
        reject(entities.selectCount(
                cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity.BusinessEntityDO::getSubjectDomainId,
                id));
        reject(conditions.selectCount(
                cn.iocoder.yudao.module.modeling.dal.dataobject.businesscondition.BusinessConditionDO::getSubjectDomainId,
                id));
        reject(logics.selectCount(LogicModelDO::getSubjectDomainId, id));
        reject(concepts.selectCount(ConceptModelDO::getSubjectDomainId, id));
        reject(indicators.selectCount(IndicatorModelDO::getSubjectDomainId, id));
    }

    /**
     * 拒绝模型规划引用门禁。
     *
     * @param count 数量
     */
    private void reject(long count) {
        if (count > 0) {
            throw exception(MODEL_REFERENCE_IN_USE);
        }
    }
}

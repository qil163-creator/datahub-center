package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleSetItemDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据治理-数据质量-规则集明细 Mapper
 *
 * @author Li
 */
@Mapper
public interface QualityRuleSetItemMapper extends BaseMapperX<QualityRuleSetItemDO> {

    /**
     * 按规则集合编号查询质量规则集合明细列表。
     */
    default List<QualityRuleSetItemDO> selectListByRuleSetId(Long ruleSetId) {
        return selectList(new LambdaQueryWrapperX<QualityRuleSetItemDO>()
                .eq(QualityRuleSetItemDO::getRuleSetId, ruleSetId)
                .orderByAsc(QualityRuleSetItemDO::getId));
    }

    /**
     * 按规则集合编号查询质量规则集合明细列表。
     */
    default List<QualityRuleSetItemDO> selectListByRuleSetIds(java.util.Collection<Long> ruleSetIds) {
        if (ruleSetIds == null || ruleSetIds.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapperX<QualityRuleSetItemDO>()
                .in(QualityRuleSetItemDO::getRuleSetId, ruleSetIds));
    }

    /**
     * 按规则集合编号删除质量规则集合明细记录。
     */
    default void deleteByRuleSetId(Long ruleSetId) {
        delete(new LambdaQueryWrapperX<QualityRuleSetItemDO>()
                .eq(QualityRuleSetItemDO::getRuleSetId, ruleSetId));
    }

    /**
     * 按规则编号查询质量规则集合明细数量。
     */
    default Long selectCountByRuleId(Long ruleId) {
        return selectCount(new LambdaQueryWrapperX<QualityRuleSetItemDO>()
                .eq(QualityRuleSetItemDO::getRuleId, ruleId));
    }

}

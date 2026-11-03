package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.orgscore.QualityOrgScorePageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityOrgScoreDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据治理-数据质量-机构评分 Mapper
 *
 * @author Li
 */
@Mapper
public interface QualityOrgScoreMapper extends BaseMapperX<QualityOrgScoreDO> {

    /**
     * 查询质量组织分数分页。
     */
    default PageResult<QualityOrgScoreDO> selectPage(QualityOrgScorePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<QualityOrgScoreDO>()
                .eqIfPresent(QualityOrgScoreDO::getRuleSetId, reqVO.getRuleSetId())
                .likeIfPresent(QualityOrgScoreDO::getOrgName, reqVO.getOrgName())
                .geIfPresent(QualityOrgScoreDO::getPeriodStart, reqVO.getPeriodStart())
                .leIfPresent(QualityOrgScoreDO::getPeriodEnd, reqVO.getPeriodEnd())
                .orderByAsc(QualityOrgScoreDO::getRegionRank));
    }

    /**
     * 按规则集合与周期删除质量组织分数记录。
     */
    default void deleteByRuleSetAndPeriod(Long ruleSetId, String periodStart, String periodEnd) {
        delete(new LambdaQueryWrapperX<QualityOrgScoreDO>()
                .eq(QualityOrgScoreDO::getRuleSetId, ruleSetId)
                .eq(QualityOrgScoreDO::getPeriodStart, periodStart)
                .eq(QualityOrgScoreDO::getPeriodEnd, periodEnd));
    }

}

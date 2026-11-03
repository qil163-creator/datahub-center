package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.rule.QualityRulePageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据治理-数据质量-质量规则 Mapper
 *
 * @author Li
 */
@Mapper
public interface QualityRuleMapper extends BaseMapperX<QualityRuleDO> {

    /**
     * 查询质量规则分页。
     */
    default PageResult<QualityRuleDO> selectPage(QualityRulePageReqVO reqVO) {
        LambdaQueryWrapperX<QualityRuleDO> wrapper = new LambdaQueryWrapperX<>();
        wrapper.likeIfPresent(QualityRuleDO::getRuleName, reqVO.getRuleName())
                .eqIfPresent(QualityRuleDO::getRuleType, reqVO.getRuleType())
                .eqIfPresent(QualityRuleDO::getQualityDim, reqVO.getQualityDim())
                .eqIfPresent(QualityRuleDO::getSeverity, reqVO.getSeverity())
                .eqIfPresent(QualityRuleDO::getStatus, reqVO.getStatus())
                .eqIfPresent(QualityRuleDO::getCategoryId, reqVO.getCategoryId())
                .eqIfPresent(QualityRuleDO::getStandardElementId, reqVO.getStandardElementId());
        if (StrUtil.isNotBlank(reqVO.getRuleCode())) {
            wrapper.like(QualityRuleDO::getRuleCode, reqVO.getRuleCode());
        }
        wrapper.orderByAsc(QualityRuleDO::getId);
        return selectPage(reqVO, wrapper);
    }

    /**
     * 按名称查询质量规则。
     */
    default QualityRuleDO selectByName(String ruleName) {
        return selectOne(new LambdaQueryWrapperX<QualityRuleDO>().eq(QualityRuleDO::getRuleName, ruleName));
    }

    /**
     * 按编码查询质量规则。
     */
    default QualityRuleDO selectByCode(String ruleCode) {
        return selectOne(new LambdaQueryWrapperX<QualityRuleDO>().eq(QualityRuleDO::getRuleCode, ruleCode));
    }

    /**
     * 按标准数据元编号查询质量规则列表。
     */
    default java.util.List<QualityRuleDO> selectListByStandardElementId(Long standardElementId) {
        return selectList(new LambdaQueryWrapperX<QualityRuleDO>()
                .eq(QualityRuleDO::getStandardElementId, standardElementId)
                .orderByAsc(QualityRuleDO::getId));
    }

}

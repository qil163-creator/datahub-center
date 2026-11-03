package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.ruleset.QualityRuleSetPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleSetDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据治理-数据质量-规则集 Mapper
 *
 * @author Li
 */
@Mapper
public interface QualityRuleSetMapper extends BaseMapperX<QualityRuleSetDO> {

    /**
     * 查询质量规则集合分页。
     */
    default PageResult<QualityRuleSetDO> selectPage(QualityRuleSetPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<QualityRuleSetDO>()
                .likeIfPresent(QualityRuleSetDO::getSetName, reqVO.getSetName())
                .eqIfPresent(QualityRuleSetDO::getCategory, reqVO.getCategory())
                .eqIfPresent(QualityRuleSetDO::getAssessDim, reqVO.getAssessDim())
                .eqIfPresent(QualityRuleSetDO::getStatus, reqVO.getStatus())
                .orderByAsc(QualityRuleSetDO::getId));
    }

    /**
     * 按名称查询质量规则集合。
     */
    default QualityRuleSetDO selectByName(String setName) {
        return selectOne(new LambdaQueryWrapperX<QualityRuleSetDO>().eq(QualityRuleSetDO::getSetName, setName));
    }

}

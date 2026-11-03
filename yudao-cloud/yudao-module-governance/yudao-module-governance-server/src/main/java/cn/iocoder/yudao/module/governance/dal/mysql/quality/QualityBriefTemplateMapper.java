package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief.QualityBriefTemplatePageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityBriefTemplateDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据治理-数据质量-质检简报模板 Mapper
 *
 * @author Li
 */
@Mapper
public interface QualityBriefTemplateMapper extends BaseMapperX<QualityBriefTemplateDO> {

    /**
     * 查询质量简报模板分页。
     */
    default PageResult<QualityBriefTemplateDO> selectPage(QualityBriefTemplatePageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<QualityBriefTemplateDO>()
                .likeIfPresent(QualityBriefTemplateDO::getTemplateName, reqVO.getTemplateName())
                .eqIfPresent(QualityBriefTemplateDO::getAssessCycle, reqVO.getAssessCycle())
                .eqIfPresent(QualityBriefTemplateDO::getStatus, reqVO.getStatus())
                .orderByAsc(QualityBriefTemplateDO::getSortNo));
    }

    /**
     * 按名称查询质量简报模板。
     */
    default QualityBriefTemplateDO selectByName(String templateName) {
        return selectOne(new LambdaQueryWrapperX<QualityBriefTemplateDO>()
                .eq(QualityBriefTemplateDO::getTemplateName, templateName));
    }

}

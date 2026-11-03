package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief.QualityBriefPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityBriefDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据治理-数据质量-质检简报 Mapper
 *
 * @author Li
 */
@Mapper
public interface QualityBriefMapper extends BaseMapperX<QualityBriefDO> {

    /**
     * 查询质量简报分页。
     */
    default PageResult<QualityBriefDO> selectPage(QualityBriefPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<QualityBriefDO>()
                .likeIfPresent(QualityBriefDO::getBriefTitle, reqVO.getBriefTitle())
                .eqIfPresent(QualityBriefDO::getBriefStatus, reqVO.getBriefStatus())
                .eqIfPresent(QualityBriefDO::getTemplateId, reqVO.getTemplateId())
                .orderByDesc(QualityBriefDO::getId));
    }

}

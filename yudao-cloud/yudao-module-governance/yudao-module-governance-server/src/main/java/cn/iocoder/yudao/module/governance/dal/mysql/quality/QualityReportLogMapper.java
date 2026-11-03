package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief.QualityReportLogPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityReportLogDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据治理-数据质量-报告生成记录 Mapper
 *
 * @author Li
 */
@Mapper
public interface QualityReportLogMapper extends BaseMapperX<QualityReportLogDO> {

    /**
     * 查询质量报告日志分页。
     */
    default PageResult<QualityReportLogDO> selectPage(QualityReportLogPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<QualityReportLogDO>()
                .eqIfPresent(QualityReportLogDO::getGenerateStatus, reqVO.getGenerateStatus())
                .likeIfPresent(QualityReportLogDO::getGenerator, reqVO.getGenerator())
                .betweenIfPresent(QualityReportLogDO::getGenerateTime, reqVO.getGenerateTime())
                .orderByDesc(QualityReportLogDO::getId));
    }

}

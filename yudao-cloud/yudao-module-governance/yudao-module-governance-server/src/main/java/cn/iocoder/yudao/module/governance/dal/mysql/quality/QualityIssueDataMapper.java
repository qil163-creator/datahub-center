package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.issue.QualityIssueDataPageReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityIssueDataDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据治理-数据质量-问题数据 Mapper
 *
 * @author Li
 */
@Mapper
public interface QualityIssueDataMapper extends BaseMapperX<QualityIssueDataDO> {

    /**
     * 查询质量问题数据分页。
     */
    default PageResult<QualityIssueDataDO> selectPage(QualityIssueDataPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<QualityIssueDataDO>()
                .eqIfPresent(QualityIssueDataDO::getTaskId, reqVO.getTaskId())
                .eqIfPresent(QualityIssueDataDO::getInstanceId, reqVO.getInstanceId())
                .eqIfPresent(QualityIssueDataDO::getRuleId, reqVO.getRuleId())
                .likeIfPresent(QualityIssueDataDO::getTableName, reqVO.getTableName())
                .eqIfPresent(QualityIssueDataDO::getIssueStatus, reqVO.getIssueStatus())
                .eqIfPresent(QualityIssueDataDO::getStatScope, reqVO.getStatScope())
                .eqIfPresent(QualityIssueDataDO::getOrgId, reqVO.getOrgId())
                .betweenIfPresent(QualityIssueDataDO::getCheckTime, reqVO.getCheckTime())
                .orderByDesc(QualityIssueDataDO::getId));
    }

    /**
     * 查询待处理数量。
     */
    default Long selectUnprocessedCount() {
        return selectCount(new LambdaQueryWrapperX<QualityIssueDataDO>()
                .eq(QualityIssueDataDO::getIssueStatus, QualityIssueDataDO.STATUS_UNPROCESSED));
    }

}

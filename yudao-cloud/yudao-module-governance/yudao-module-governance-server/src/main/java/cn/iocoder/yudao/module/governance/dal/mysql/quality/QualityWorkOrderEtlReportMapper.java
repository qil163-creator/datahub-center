package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderEtlReportDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface QualityWorkOrderEtlReportMapper extends BaseMapperX<QualityWorkOrderEtlReportDO> {

    /**
     * 按报告编号查询质量工单ETL报告。
     */
    default QualityWorkOrderEtlReportDO selectByReportId(Long reportId) {
        return selectOne(new LambdaQueryWrapperX<QualityWorkOrderEtlReportDO>()
                .eq(QualityWorkOrderEtlReportDO::getReportId, reportId));
    }

    /**
     * 按工作工单编号查询质量工单ETL报告。
     */
    default QualityWorkOrderEtlReportDO selectByWorkOrderId(Long workOrderId) {
        return selectOne(new LambdaQueryWrapperX<QualityWorkOrderEtlReportDO>()
                .eq(QualityWorkOrderEtlReportDO::getWorkOrderId, workOrderId));
    }
}

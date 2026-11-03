package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderActionDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface QualityWorkOrderActionMapper extends BaseMapperX<QualityWorkOrderActionDO> {

    /**
     * 按请求查询质量工单操作。
     */
    default QualityWorkOrderActionDO selectByRequest(Long workOrderId, String requestId) {
        return selectOne(new LambdaQueryWrapperX<QualityWorkOrderActionDO>()
                .eq(QualityWorkOrderActionDO::getWorkOrderId, workOrderId)
                .eq(QualityWorkOrderActionDO::getRequestId, requestId)
                .last("LIMIT 1"));
    }

    /**
     * 按工作工单编号查询质量工单操作列表。
     */
    default List<QualityWorkOrderActionDO> selectListByWorkOrderId(Long workOrderId) {
        return selectList(new LambdaQueryWrapperX<QualityWorkOrderActionDO>()
                .eq(QualityWorkOrderActionDO::getWorkOrderId, workOrderId)
                .orderByAsc(QualityWorkOrderActionDO::getId));
    }

    /**
     * 统计按工作工单编号。
     */
    default long countByWorkOrderId(Long workOrderId) {
        return selectCount(new LambdaQueryWrapperX<QualityWorkOrderActionDO>()
                .eq(QualityWorkOrderActionDO::getWorkOrderId, workOrderId));
    }

}

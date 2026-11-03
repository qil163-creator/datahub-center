package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderIssueDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据治理-数据质量-工单问题关联 Mapper
 *
 * @author Li
 */
@Mapper
public interface QualityWorkOrderIssueMapper extends BaseMapperX<QualityWorkOrderIssueDO> {

    /**
     * 按工作工单编号查询质量工单问题列表。
     */
    default List<QualityWorkOrderIssueDO> selectListByWorkOrderId(Long workOrderId) {
        return selectList(new LambdaQueryWrapperX<QualityWorkOrderIssueDO>()
                .eq(QualityWorkOrderIssueDO::getWorkOrderId, workOrderId)
                .orderByAsc(QualityWorkOrderIssueDO::getId));
    }

    /**
     * 按工作工单编号删除质量工单问题记录。
     */
    default void deleteByWorkOrderId(Long workOrderId) {
        delete(new LambdaQueryWrapperX<QualityWorkOrderIssueDO>()
                .eq(QualityWorkOrderIssueDO::getWorkOrderId, workOrderId));
    }

}

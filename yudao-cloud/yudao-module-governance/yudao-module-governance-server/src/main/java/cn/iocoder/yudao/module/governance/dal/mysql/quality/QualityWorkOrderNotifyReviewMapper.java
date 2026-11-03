package cn.iocoder.yudao.module.governance.dal.mysql.quality;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityWorkOrderNotifyReviewDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface QualityWorkOrderNotifyReviewMapper extends BaseMapperX<QualityWorkOrderNotifyReviewDO> {

    /**
     * 按请求编号查询质量工单通知复核。
     */
    default QualityWorkOrderNotifyReviewDO selectByRequestId(String requestId) {
        return selectOne(new LambdaQueryWrapperX<QualityWorkOrderNotifyReviewDO>()
                .eq(QualityWorkOrderNotifyReviewDO::getRequestId, requestId).last("LIMIT 1"));
    }

    /**
     * 按通知编号查询质量工单通知复核。
     */
    default List<QualityWorkOrderNotifyReviewDO> selectByNotifyId(Long notifyId) {
        return selectList(new LambdaQueryWrapperX<QualityWorkOrderNotifyReviewDO>()
                .eq(QualityWorkOrderNotifyReviewDO::getNotifyId, notifyId)
                .orderByAsc(QualityWorkOrderNotifyReviewDO::getId));
    }

    /**
     * 按通知编号查询最新。
     */
    default List<QualityWorkOrderNotifyReviewDO> selectLatestByNotifyId(Long notifyId, int limit) {
        return selectList(new LambdaQueryWrapperX<QualityWorkOrderNotifyReviewDO>()
                .eq(QualityWorkOrderNotifyReviewDO::getNotifyId, notifyId)
                .orderByDesc(QualityWorkOrderNotifyReviewDO::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit, 51))));
    }

}

package cn.iocoder.yudao.module.asset.dal.mysql.market;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketCheckProcessDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface MarketCheckProcessMapper extends BaseMapperX<MarketCheckProcessDO> {

    /**
     * 按申请编号查询市场审批工单流程列表。
     */
    default List<MarketCheckProcessDO> selectListByApplyId(Long applyId) {
        return selectList(new LambdaQueryWrapperX<MarketCheckProcessDO>()
                .eq(MarketCheckProcessDO::getResourceApplyId, applyId)
                .orderByAsc(MarketCheckProcessDO::getId));
    }

}

package cn.iocoder.yudao.module.asset.dal.mysql.market;

import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDemandReplyDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface MarketDemandReplyMapper extends BaseMapperX<MarketDemandReplyDO> {

    /**
     * 按需求编号查询市场需求回复列表。
     */
    default List<MarketDemandReplyDO> selectListByDemandId(Long demandId) {
        return selectList(new LambdaQueryWrapperX<MarketDemandReplyDO>()
                .eq(MarketDemandReplyDO::getDemandId, demandId)
                .orderByAsc(MarketDemandReplyDO::getId));
    }

}

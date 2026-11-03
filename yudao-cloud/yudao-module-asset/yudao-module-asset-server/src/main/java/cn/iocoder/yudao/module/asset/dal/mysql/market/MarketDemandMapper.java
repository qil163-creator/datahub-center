package cn.iocoder.yudao.module.asset.dal.mysql.market;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketDemandPageReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.market.MarketDemandDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Mapper
public interface MarketDemandMapper extends BaseMapperX<MarketDemandDO> {

    /**
     * 读取市场需求的下一个需求无序列。
     */
    @Select("SELECT nextval('dh_market_demand_no_seq')")
    Long nextDemandNoSeq();

    /**
     * 查询市场需求分页。
     */
    default PageResult<MarketDemandDO> selectPage(MarketDemandPageReqVO reqVO, Long selfUserId,
            Collection<Integer> hallStatuses) {
        LambdaQueryWrapperX<MarketDemandDO> wrapper = new LambdaQueryWrapperX<MarketDemandDO>()
                .eqIfPresent(MarketDemandDO::getDemandType, reqVO.getDemandType())
                .eqIfPresent(MarketDemandDO::getUrgency, reqVO.getUrgency())
                .eqIfPresent(MarketDemandDO::getExpectedDelivery, reqVO.getExpectedDelivery())
                .eqIfPresent(MarketDemandDO::getApplyDeptId, reqVO.getApplyDeptId())
                .eqIfPresent(MarketDemandDO::getStatus, reqVO.getStatus())
                .eqIfPresent(MarketDemandDO::getApplyUserId, selfUserId)
                .likeIfPresent(MarketDemandDO::getDemandNo, reqVO.getDemandNo());
        if (hallStatuses != null && !hallStatuses.isEmpty()) {
            wrapper.in(MarketDemandDO::getStatus, hallStatuses);
        }
        if (StrUtil.isNotBlank(reqVO.getKeyword())) {
            wrapper.and(query -> query.like(MarketDemandDO::getTitle, reqVO.getKeyword())
                    .or().like(MarketDemandDO::getDescription, reqVO.getKeyword())
                    .or().like(MarketDemandDO::getPurpose, reqVO.getKeyword()));
        }
        wrapper.orderByDesc(MarketDemandDO::getUrgency)
                .orderByDesc(MarketDemandDO::getId);
        return selectPage(reqVO, wrapper);
    }

    /**
     * 按状态查询市场需求数量。
     */
    default long selectCountByStatus(Integer status) {
        return selectCount(MarketDemandDO::getStatus, status);
    }

    /**
     * 查询已受理数量。
     */
    default long selectAcceptedCount() {
        return selectCount(MarketDemandDO::getAcceptanceStatus, MarketDemandDO.ACCEPTANCE_DONE);
    }

    /**
     * 查询市场需求数量当日。
     */
    default long selectCountToday(LocalDateTime start, LocalDateTime end) {
        return selectCount(new LambdaQueryWrapperX<MarketDemandDO>()
                .ge(MarketDemandDO::getCreateTime, start)
                .lt(MarketDemandDO::getCreateTime, end));
    }

    /**
     * 查询近期已发布。
     */
    default List<MarketDemandDO> selectRecentPublished(int limit) {
        return selectList(new LambdaQueryWrapperX<MarketDemandDO>()
                .in(MarketDemandDO::getStatus,
                        MarketDemandDO.STATUS_PUBLISHED,
                        MarketDemandDO.STATUS_MATCHING)
                .orderByDesc(MarketDemandDO::getPublishTime)
                .last("LIMIT " + limit));
    }

}

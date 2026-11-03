package cn.iocoder.yudao.module.asset.dal.mysql.dataasset;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.mybatis.core.mapper.BaseMapperX;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.asset.controller.admin.dataasset.vo.AssetReviewTicketPageReqVO;
import cn.iocoder.yudao.module.asset.dal.dataobject.dataasset.AssetReviewTicketDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AssetReviewTicketMapper extends BaseMapperX<AssetReviewTicketDO> {

    /**
     * 查询资产复核工单分页。
     */
    default PageResult<AssetReviewTicketDO> selectPage(AssetReviewTicketPageReqVO reqVO) {
        return selectPage(reqVO, new LambdaQueryWrapperX<AssetReviewTicketDO>()
                .eqIfPresent(AssetReviewTicketDO::getAssetId, reqVO.getAssetId())
                .eqIfPresent(AssetReviewTicketDO::getStatus, reqVO.getStatus())
                .eqIfPresent(AssetReviewTicketDO::getIssueType, reqVO.getIssueType())
                .orderByDesc(AssetReviewTicketDO::getId));
    }

}

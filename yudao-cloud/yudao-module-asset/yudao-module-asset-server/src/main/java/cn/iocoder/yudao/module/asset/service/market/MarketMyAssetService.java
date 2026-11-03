package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketMyAssetDetailRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketMyAssetPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketMyAssetRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketMyAssetApiDocumentRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplyPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketApplyRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketMyApplicationRespVO;
import cn.iocoder.yudao.module.dataforge.api.database.dto.DatabasePreviewRespDTO;

public interface MarketMyAssetService {

    /** 仅查询当前登录人的本地申请。 */
    PageResult<MarketMyApplicationRespVO> getApplications(MarketApplyPageReqVO req);

    /** 仅查看当前登录人的本地申请详情。 */
    MarketApplyRespVO getApplication(Long id);

    /**
     * 按筛选条件分页查询本人获批资产。
     */
    PageResult<MarketMyAssetRespVO> getPage(MarketMyAssetPageReqVO req);

    /**
     * 本人的批准详情；读取历史不恢复消费授权。
     */
    MarketMyAssetDetailRespVO get(Long applyId);

    /**
     * 按本人指定批准实时核验并预览库表。
     */
    DatabasePreviewRespDTO preview(Long applyId, Integer limit);

    /**
     * 实时核对本人批准及固定 API 合同后查看文档。
     */
    MarketMyAssetApiDocumentRespVO apiDocument(Long applyId);
}

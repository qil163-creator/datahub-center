package cn.iocoder.yudao.module.asset.service.term;

import cn.iocoder.yudao.module.asset.controller.admin.term.vo.AssetTermBindReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.term.vo.AssetTermRespVO;

import java.util.List;
import java.util.Map;

public interface AssetTermService {

    /** 治理模块启用中的业务术语，供资产字段选择。 */
    List<AssetTermRespVO> getSimpleTermList(String keyword);

    /** termId 是治理业务术语编号；为空表示解绑。 */
    void bindColumn(AssetTermBindReqVO reqVO);

    /** 由资产详情既有读取权限保护的字段关联：metaColumnId -> 治理术语。 */
    Map<Long, AssetTermRespVO> getColumnTermMap(Long assetId);

    /**
     * 按资产编号删除资产术语记录。
     */
    void deleteByAssetId(Long assetId);
}

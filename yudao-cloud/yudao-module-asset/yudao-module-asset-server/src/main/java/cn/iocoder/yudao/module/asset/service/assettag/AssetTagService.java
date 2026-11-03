package cn.iocoder.yudao.module.asset.service.assettag;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.AssetTagBindReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.AssetTagPageReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.AssetTagRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.AssetTagSaveReqVO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface AssetTagService {

    /**
     * 创建资产标签。
     */
    Long createTag(AssetTagSaveReqVO reqVO);

    /**
     * 更新资产标签。
     */
    void updateTag(AssetTagSaveReqVO reqVO);

    /**
     * 启用/停用资产标签。
     */
    void updateTagStatus(Long id, Integer status, Integer revision);

    /**
     * 删除资产标签。
     */
    void deleteTag(Long id, Integer revision);

    /**
     * 删除标签。
     */
    void deleteTags(
            java.util.List<cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.AssetTagDeleteListReqVO.Item> items);

    /**
     * 分页读取指定标签已关联的资产。
     */
    PageResult<cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.AssetTagLinkedRespVO> linkedAssets(
            cn.iocoder.yudao.module.asset.controller.admin.assettag.vo.AssetTagLinkedPageReqVO req);

    /**
     * 维护范围内按修订号移除单条资产标签关联。
     */
    void unbind(Long relationId, Integer revision);

    /**
     * 获得资产标签详情。
     */
    AssetTagRespVO getTag(Long id);

    /**
     * 获得资产标签分页。
     */
    PageResult<AssetTagRespVO> getTagPage(AssetTagPageReqVO reqVO);

    /**
     * 获得资产标签列表（下拉选择用）。
     */
    List<AssetTagRespVO> getTagList();

    /**
     * 全量覆盖资产标签。
     */
    void bindTags(AssetTagBindReqVO reqVO);

    /**
     * 按资产编号查询标签。
     */
    List<AssetTagRespVO> getTagsByAssetId(Long assetId);

    /**
     * 按资产编号查询标签。
     */
    Map<Long, List<AssetTagRespVO>> getTagsByAssetIds(Collection<Long> assetIds);

}

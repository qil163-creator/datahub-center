package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCategoryListReqVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCategoryRespVO;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.MarketCategorySaveReqVO;

import java.util.List;

public interface MarketCategoryService {

    /**
     * 读取启用列表。
     */
    List<MarketCategoryRespVO> getEnabledList();

    /**
     * 新增集市类目。
     */
    Long create(MarketCategorySaveReqVO req);

    /**
     * 修改集市类目。
     */
    void update(MarketCategorySaveReqVO req);

    /**
     * 更新市场分类的状态。
     */
    void updateStatus(Long id, Short status);

    /**
     * 删除无引用的集市类目。
     */
    void delete(List<Long> ids);

    /**
     * 读取集市类目。
     */
    MarketCategoryRespVO get(Long id);

    /**
     * 查询平台集市类目树节点。
     */
    List<MarketCategoryRespVO> list(MarketCategoryListReqVO req);

    /**
     * 校验市场分类的启用用于发布，不满足约束时拒绝当前操作。
     */
    void requireEnabledForPublish(Long id);

    /**
     * 查询指定市场分类及其全部后代编号。
     */
    java.util.Set<Long> descendants(Long id);
}

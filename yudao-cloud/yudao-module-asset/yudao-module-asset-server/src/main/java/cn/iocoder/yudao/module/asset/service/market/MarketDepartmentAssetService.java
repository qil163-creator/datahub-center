package cn.iocoder.yudao.module.asset.service.market;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.asset.controller.admin.market.vo.*;

import java.util.List;

public interface MarketDepartmentAssetService {

    /**
     * 按当前部门范围分页读取本地及迁入申请，汇总部门可见的申请记录。
     */
    PageResult<MarketDepartmentAssetRespVO> getPage(MarketDepartmentAssetPageReqVO req);

    /**
     * 按指定申请编号读取同部门资产详情，查看不产生消费授权。
     */
    MarketDepartmentAssetDetailRespVO get(Long id);

    /**
     * 读取指定同部门申请的复制数据；原生实时预览仍校验本人真实批准。
     */
    MarketCheckDetailRespVO.Preview preview(Long id, Integer limit);

    /**
     * 读取指定申请的 API 文档；不执行来源 API，不转授同部门其他人的合同。
     */
    MarketCheckDetailRespVO.ApiDocument apiDocument(Long id);

    /**
     * 获取当前同部门申请资产实际关联的集市类目。
     */
    List<MarketDepartmentAssetCategoryRespVO> categories();
}

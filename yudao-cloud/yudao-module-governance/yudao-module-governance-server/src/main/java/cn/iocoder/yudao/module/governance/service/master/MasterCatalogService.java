package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterCatalogRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterCatalogSaveReqVO;

import java.util.List;

/**
 * 主数据目录 Service 接口
 *
 * @author Li
 */
public interface MasterCatalogService {

    /**
     * 创建主数据目录节点
     */
    Long createCatalog(MasterCatalogSaveReqVO createReqVO);

    /**
     * 更新主数据目录节点（父级与类型不可变更）
     */
    void updateCatalog(MasterCatalogSaveReqVO updateReqVO);

    /**
     * 删除主数据目录节点（存在子节点或已建模型时拒绝）
     */
    void deleteCatalog(Long id);

    /**
     * 获得主数据目录平铺列表（含主数据节点的模型状态），前端组树
     */
    List<MasterCatalogRespVO> getCatalogList();

}

package cn.iocoder.yudao.module.modeling.service.planning;

import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.dwlayer.DwLayerListReqVO;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.dwlayer.DwLayerPrefixPreviewRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.planning.vo.dwlayer.DwLayerSaveReqVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DwLayerDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 数仓分层 Service 接口
 *
 * @author Li
 */
public interface DwLayerService {

    /**
     * 创建数仓分层
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createDwLayer(@Valid DwLayerSaveReqVO createReqVO);

    /**
     * 更新数仓分层
     *
     * @param updateReqVO 更新信息
     */
    void updateDwLayer(DwLayerSaveReqVO updateReqVO);

    /**
     * 更新数仓分层状态
     *
     * @param id 编号
     * @param status 状态
     */
    void updateDwLayerStatus(Long id, Integer status);

    /**
     * 删除数仓分层
     *
     * @param id 编号
     */
    void deleteDwLayer(Long id);


    /**
     * 获得数仓分层
     *
     * @param id 编号
     * @return 数仓分层
     */
    DwLayerDO getDwLayer(Long id);

    /**
     * 获得数仓分层列表
     *
     * @param listReqVO 查询条件
     * @return 数仓分层列表
     */
    List<DwLayerDO> getDwLayerList(DwLayerListReqVO listReqVO);

    /**
     * 预览修改表前缀后分层下各物理模型的候选表名及差异
     *
     * 纯只读：不改名、不写库。候选前缀为空串表示去掉前缀；非空时按表英文名规则归一化并校验。
     *
     * @param id          分层编号
     * @param tablePrefix 候选表前缀，可为空
     * @return 预览结果
     */
    DwLayerPrefixPreviewRespVO previewTablePrefix(Long id, String tablePrefix);

}

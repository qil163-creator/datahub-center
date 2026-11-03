package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelOverviewRespVO;

/**
 * 数据建模总览统计 Service 接口
 *
 * @author Li
 */
public interface ModelOverviewService {

    /**
     * 获得数据建模总览统计（数据建模首页「数据概览」面板）
     *
     * @return 总览统计
     */
    ModelOverviewRespVO getModelOverview();

}

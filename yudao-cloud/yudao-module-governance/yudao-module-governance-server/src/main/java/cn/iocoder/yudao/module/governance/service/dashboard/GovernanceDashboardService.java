package cn.iocoder.yudao.module.governance.service.dashboard;

import cn.iocoder.yudao.module.governance.controller.admin.dashboard.vo.GovernanceDashboardRespVO;

/**
 * 首页看板（数据治理） Service 接口
 *
 * @author Li
 */
public interface GovernanceDashboardService {

    /**
     * 获得首页看板汇总：元数据概览、标准数据元统计、质量规则 / 检核 / 告警 / 工单汇总、主数据模型 / 派发任务数量
     *
     * @return 汇总
     */
    GovernanceDashboardRespVO getSummary();

}

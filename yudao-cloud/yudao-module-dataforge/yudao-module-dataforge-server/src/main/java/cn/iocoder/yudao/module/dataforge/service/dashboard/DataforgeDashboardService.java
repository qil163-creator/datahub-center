package cn.iocoder.yudao.module.dataforge.service.dashboard;

import cn.iocoder.yudao.module.dataforge.controller.admin.dashboard.vo.DataforgeDashboardRespVO;

/**
 * 首页看板（数据集成 / 数据研发） Service 接口
 *
 * @author Li
 */
public interface DataforgeDashboardService {

    /**
     * 获得首页看板汇总：数据源 / 数据同步任务 / 开发任务数量，以及今日执行情况
     *
     * @return 汇总
     */
    DataforgeDashboardRespVO getSummary();

}

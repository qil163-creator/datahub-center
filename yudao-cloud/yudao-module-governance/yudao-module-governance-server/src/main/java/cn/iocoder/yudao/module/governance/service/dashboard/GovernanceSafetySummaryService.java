package cn.iocoder.yudao.module.governance.service.dashboard;

import cn.iocoder.yudao.module.governance.controller.admin.dashboard.vo.GovernanceSafetySummaryRespVO;

/**
 * 治理总览与首页安全统计接口
 *
 * @author Li
 */
public interface GovernanceSafetySummaryService {

    /**
     * 获得治理总览所需的安全统计
     */
    GovernanceSafetySummaryRespVO getSummary();

}

package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.*;

/**
 * 主数据派发 Service 接口
 *
 * @author Li
 */
public interface MasterDistributeService {

    /**
     * 创建派发任务
     */
    Long createDistribute(MasterDistributeSaveReqVO createReqVO);

    /**
     * 更新派发任务（已发布不可修改）
     */
    void updateDistribute(MasterDistributeSaveReqVO updateReqVO);

    /**
     * 删除派发任务（已发布不可删除）
     */
    void deleteDistribute(Long id);

    /**
     * 获得派发任务
     */
    MasterDistributeRespVO getDistribute(Long id);

    /**
     * 获得派发任务分页
     */
    PageResult<MasterDistributeRespVO> getDistributePage(MasterDistributePageReqVO pageReqVO);

    /**
     * 发布：一次性任务立即执行一次，周期任务按 Cron 排期
     */
    void releaseDistribute(Long id);

    /**
     * 取消发布：停止后续排期，发送前取消，发送后保留未知结果。
     */
    void cancelDistribute(Long id);

    /**
     * 手动执行一次（无论是否发布）
     */
    void handRun(Long id);

    /**
     * 启用或停用 REST 推送（不发送数据）。
     */
    void updateStatus(Long id, Integer status);

    /**
     * 重试最近一次发送前失败的推送。
     */
    void retry(Long id);

    /** 停止指定运行，发布及周期排期保持不变。平台内拥有运行权限的管理员可操作。 */
    MasterDistributeLogRespVO stopRun(@jakarta.validation.Valid MasterDistributeStopReqVO request);

    /**
     * 获得执行记录分页
     */
    PageResult<MasterDistributeLogRespVO> getDistributeLogPage(MasterDistributeLogPageReqVO pageReqVO);

    /**
     * 获得执行记录详情
     */
    MasterDistributeLogRespVO getDistributeLog(Long id);

}

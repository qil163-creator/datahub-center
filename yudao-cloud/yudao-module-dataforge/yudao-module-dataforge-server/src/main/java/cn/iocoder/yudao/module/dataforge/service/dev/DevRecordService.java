package cn.iocoder.yudao.module.dataforge.service.dev;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevRecordPageReqVO;
import cn.iocoder.yudao.module.dataforge.dal.dataobject.dev.DevRecordDO;

/**
 * 数据开发-任务执行实例 Service 接口
 *
 * @author Li
 */
public interface DevRecordService {

    /**
     * 核实原质检已结束，将未知质量保守收敛为失败。
     *
     * @param req 请求参数
     */
    void resolveQuality(cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevQualityResolveReqVO req);

    /**
     * 记录未知实例的人工核查结论。
     *
     * @param req 请求参数
     */
    void resolveUnknown(cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevRecordResolveReqVO req);

    /**
     * 获得任务实例分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 任务实例分页结果
     */
    PageResult<DevRecordDO> getRecordPage(DevRecordPageReqVO pageReqVO);

    /**
     * 获得任务实例（含日志，供运行中轮询）。
     *
     * @param id 记录编号
     * @return 开发记录
     */
    DevRecordDO getRecord(Long id);

    /**
     * 读取最新记录。
     *
     * @param taskIds 任务编号集合
     * @return 处理后的键值映射
     */
    java.util.Map<Long, DevRecordDO> getLatestRecords(java.util.Collection<Long> taskIds);

    /**
     * 获得当前平台任务执行实例统计。
     *
     * @param taskId 任务编号
     * @return 开发记录统计响应数据
     */
    cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevRecordStatisticsRespVO getStatistics(
            Long taskId);

    /**
     * 下载当前平台任务实例的已脱敏日志（有界）。
     *
     * @param id 记录编号
     * @return 下载当前平台任务实例的已脱敏日志（有界）文本
     */
    String downloadTaskLog(Long id);

    /**
     * 分页读取当前平台任务实例的已脱敏调度器日志。
     *
     * @param req 请求参数
     * @return 开发任务日志分页响应数据
     */
    cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevTaskLogPageRespVO getTaskLog(
            cn.iocoder.yudao.module.dataforge.controller.admin.dev.vo.record.DevTaskLogPageReqVO req);

    /**
     * 停止运行中的实例
     */
    void stopRecord(Long id);

    /**
     * 删除任务实例。
     *
     * @param id 记录编号
     */
    void deleteRecord(Long id);

}

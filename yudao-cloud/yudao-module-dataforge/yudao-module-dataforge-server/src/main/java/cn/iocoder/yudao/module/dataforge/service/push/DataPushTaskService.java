package cn.iocoder.yudao.module.dataforge.service.push;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushColumnRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushPreviewReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushPreviewRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushTaskLogPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushTaskLogRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushTaskPageReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushTaskRespVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushTaskSaveReqVO;
import cn.iocoder.yudao.module.dataforge.controller.admin.push.vo.DataPushWatermarkRespVO;

import java.util.List;

public interface DataPushTaskService {

    /**
     * 创建推送任务。
     *
     * @param createReqVO 创建参数
     * @return 生成或保存的记录编号
     */
    Long createTask(DataPushTaskSaveReqVO createReqVO);

    /**
     * 更新推送任务。
     *
     * @param updateReqVO 更新参数
     */
    void updateTask(DataPushTaskSaveReqVO updateReqVO);

    /**
     * 删除推送任务。
     *
     * @param id 记录编号
     */
    void deleteTask(Long id);

    /**
     * 获得推送任务。
     *
     * @param id 记录编号
     * @return 数据推送任务响应数据
     */
    DataPushTaskRespVO getTask(Long id);

    /**
     * 获得推送任务分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 推送任务分页结果
     */
    PageResult<DataPushTaskRespVO> getTaskPage(DataPushTaskPageReqVO pageReqVO);

    /**
     * 发布任务。
     *
     * @param id 记录编号
     */
    void releaseTask(Long id);

    /**
     * 取消发布。
     *
     * @param id 记录编号
     */
    void cancelTask(Long id);

    /**
     * 手工提交一次数据推送任务运行。
     *
     * @param id 记录编号
     */
    void handRun(Long id);

    /**
     * 停止正在运行的任务。
     *
     * @param id 记录编号
     */
    void stopTask(Long id);

    /**
     * 获得执行记录分页。
     *
     * @param pageReqVO 分页查询条件
     * @return 执行记录分页结果
     */
    PageResult<DataPushTaskLogRespVO> getLogPage(DataPushTaskLogPageReqVO pageReqVO);

    /**
     * 获得执行记录详情。
     *
     * @param id 记录编号
     * @return 数据推送任务日志响应数据
     */
    DataPushTaskLogRespVO getLog(Long id);

    /**
     * 获得源表列清单。
     *
     * @param sourceDatabaseId 来源数据源编号
     * @param tableName 表名
     * @return 源表列清单结果集合
     */
    List<DataPushColumnRespVO> getTableColumns(Long sourceDatabaseId, String tableName);

    /**
     * 解析SQL列集合。
     *
     * @param sourceDatabaseId 来源数据源编号
     * @param sqlText SQL文本
     * @return 解析SQL列集合结果集合
     */
    List<DataPushColumnRespVO> parseSqlColumns(Long sourceDatabaseId, String sqlText);

    /**
     * 获得增量水位。
     *
     * @param id 记录编号
     * @return 数据推送水位响应数据
     */
    DataPushWatermarkRespVO getWatermark(Long id);

    /**
     * 重置增量水位。
     *
     * @param id 记录编号
     */
    void resetWatermark(Long id);

    /**
     * 试跑预览前 N 行（只读源端，不推送）。
     *
     * @param reqVO 请求参数
     * @return 数据推送预览响应数据
     */
    DataPushPreviewRespVO preview(DataPushPreviewReqVO reqVO);

    /**
     * 复制推送任务。
     *
     * @param id 记录编号
     * @return 复制任务数值
     */
    Long copyTask(Long id);

}

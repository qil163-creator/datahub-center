package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.dataforge.controller.admin.access.vo.*;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 数据同步任务 Service 接口
 *
 * @author Li
 */
public interface DataAccessService {

    /**
     * 创建数据同步任务
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createDataAccess(@Valid DataAccessSaveReqVO createReqVO);

    /**
     * 更新数据同步任务（已发布的任务需先取消发布）
     *
     * @param updateReqVO 更新信息
     */
    void updateDataAccess(@Valid DataAccessSaveReqVO updateReqVO);

    /**
     * 删除数据同步任务，级联删除执行记录、表级结果与增量日志
     *
     * @param id 编号
     */
    void deleteDataAccess(Long id);

    /**
     * 获得数据同步任务（含同步配置回显）
     *
     * @param id 编号
     * @return 数据同步任务
     */
    DataAccessRespVO getDataAccess(Long id);

    /**
     * 获得数据同步任务分页
     *
     * @param pageReqVO 分页查询
     * @return 数据同步任务分页
     */
    PageResult<DataAccessRespVO> getDataAccessPage(DataAccessPageReqVO pageReqVO);

    /**
     * 预览表名映射
     *
     * @param reqVO 预览参数
     * @return 源表名 → 目标表名 映射列表
     */
    List<PreviewNameMapperRespVO> previewTableMap(@Valid DataAccessPreviewReqVO reqVO);

    /**
     * 预览字段名映射
     *
     * @param reqVO 预览参数（需要 previewTableName 或 sourceSql）
     * @return 源字段 → 目标字段 映射列表
     */
    List<PreviewNameMapperRespVO> previewColumnMap(@Valid DataAccessPreviewReqVO reqVO);

    /** 已授权表源的跨产品列映射；只读元数据，全部有损列默认不接受。 */
    AccessTypeMappingPlanRespVO previewTypeMap(@Valid AccessTypeMappingPreviewReqVO reqVO);

    /**
     * 发布任务：周期任务计算下次执行时间等待调度，一次性任务立即执行一次
     *
     * @param id 编号
     */
    void releaseDataAccess(Long id);

    /**
     * 取消发布
     *
     * @param id 编号
     */
    void cancelDataAccess(Long id);

    /**
     * 手动执行一次
     *
     * @param id 编号
     */
    void runDataAccess(Long id);

    /**
     * 停止正在运行的任务
     *
     * @param id 编号
     */
    void stopDataAccess(Long id);

    /**
     * 获得执行记录分页
     */
    PageResult<DataAccessTaskRespVO> getTaskPage(DataAccessTaskPageReqVO pageReqVO);

    /**
     * 获得执行记录（含实时日志，可轮询）
     */
    DataAccessTaskRespVO getTask(Long id);

    /**
     * 批量删除执行记录（级联删除表级结果）
     */
    void deleteTaskList(List<Long> ids);

    /**
     * 获得表级同步结果分页
     */
    PageResult<DataAccessTaskDetailRespVO> getTaskDetailPage(DataAccessTaskDetailPageReqVO pageReqVO);

}

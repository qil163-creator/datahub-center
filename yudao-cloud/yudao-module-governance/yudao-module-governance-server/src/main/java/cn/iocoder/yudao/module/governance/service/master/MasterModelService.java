package cn.iocoder.yudao.module.governance.service.master;

import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterColumnVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.master.MasterModelDO;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelPageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelStatisticsRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.master.vo.MasterModelDataCountRespVO;

import java.util.List;

/**
 * 主数据模型 Service 接口
 *
 * @author Li
 */
public interface MasterModelService {

    /**
     * 创建主数据模型草稿，仅保存平台定义，字段与主键可在发布前完善。
     */
    Long createModel(MasterModelSaveReqVO createReqVO);

    /**
     * 保存模型修改并保持原发布状态；曾发布模型只能修改业务信息，结构仍冻结。
     */
    void updateModel(MasterModelSaveReqVO updateReqVO);

    /**
     * 删除主数据模型（已发布或存在派发任务时拒绝）
     */
    void deleteModel(Long id);

    /**
     * 批量删除主数据草稿或已下线模型（保留物理表）。
     */
    void deleteModelList(List<Long> ids);

    /**
     * 分页查询主数据模型。
     */
    PageResult<MasterModelRespVO> getModelPage(MasterModelPageReqVO req);

    /** 维护页面在平台内共享，仅允许读取已发布且结构结果明确的模型。 */
    PageResult<MasterModelRespVO> getMaintenanceModelPage(MasterModelPageReqVO req);

    /**
     * 读取维护模型。
     */
    MasterModelRespVO getMaintenanceModel(Long id);

    /**
     * 读取维护数据数量。
     */
    MasterModelDataCountRespVO getMaintenanceDataCount(Long id);

    /** 与主数据查询一样在平台内共享；仅按需读取已绑定物理表，不接受客户端表名。 */
    MasterModelDataCountRespVO getDataCount(Long id);

    /**
     * 统计主数据模型状态。
     */
    MasterModelStatisticsRespVO getStatistics(Long domainId);

    /**
     * 查询主数据模型精简列表。
     */
    List<MasterModelRespVO> getSimpleList();

    /**
     * 核查不确定发布结果并确认物理表。
     */
    void verifyRelease(Long id);

    /**
     * 获得主数据模型（含字段）
     */
    MasterModelRespVO getModel(Long id);

    /**
     * 获得目录节点下的主数据模型（含字段），未建模时返回 null
     */
    MasterModelRespVO getModelByCatalog(Long catalogId);

    /**
     * 发布模型：在绑定数据源中创建物理表
     */
    void releaseModel(Long id);

    /**
     * 下线模型并保留物理表（存在派发任务时拒绝）
     */
    void cancelReleaseModel(Long id);

    /**
     * 获得已发布模型精简列表（派发任务选择用）
     */
    List<MasterModelRespVO> getReleasedModelList();

    /**
     * 从数据源已有表提取字段定义（建模时拾取字段用）
     */
    List<MasterColumnVO> getTableColumns(Long databaseId, String tableName);

    /**
     * 校验模型存在且已发布（数据维护、派发执行用）
     */
    MasterModelDO validateModelReleased(Long id);

}

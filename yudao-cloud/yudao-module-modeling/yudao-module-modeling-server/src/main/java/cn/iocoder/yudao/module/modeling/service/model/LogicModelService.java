package cn.iocoder.yudao.module.modeling.service.model;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.logic.*;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.LogicModelDO;

import java.util.List;

/**
 * 数据建模-逻辑模型 Service 接口
 *
 * @author Li
 */
public interface LogicModelService {

    /**
     * 创建逻辑模型（正向：手工字段；逆向：从数据源表导入字段）
     *
     * @return 编号
     */
    Long createModel(LogicModelCreateReqVO createReqVO);

    /**
     * 修改逻辑模型基础信息
     */
    void updateModel(LogicModelCreateReqVO updateReqVO);

    /** 只切换启用/禁用，不改设计内容和生命周期状态。 */
    void updateEnabled(Long id, Integer enabled, Long expectedRevision);

    /**
     * 原子保存统一模型草稿、字段与业务口径。
     *
     * @param reqVO 请求参数
     */
    void saveDraft(ModelDraftSaveReqVO reqVO);

    /**
     * 删除逻辑模型
     */
    void deleteModel(Long id, Long expectedRevision);

    /**
     * 获得逻辑模型
     */
    LogicModelDO getModel(Long id);

    /**
     * 逻辑模型分页（主题过滤含子主题）
     */
    PageResult<LogicModelDO> getModelPage(LogicModelPageReqVO pageReqVO);

    /** 获得当前设计及同一读取快照的修订标记。 */
    ModelDesignRespVO getDesign(Long modelId);

    /** 发布当前设计，精确校验预期修订标记。 */
    void publishModel(Long modelId, Long expectedRevision);

    /** 在调用方已持有引用事务锁时，验证当前设计依赖，供发布与新部署共用。 */
    void validateCurrentDependencies(LogicModelDO model,
            List<cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem> fields);

    /**
     * 废弃逻辑模型
     */
    void discardModel(Long modelId);

    // ==================== ER 图 ====================

    /**
     * 获得主题 ER 图（主题及子主题下模型 + 当前字段 + 关系）
     */
    LogicErDiagramRespVO getErDiagram(Long layerId);

    /**
     * 新增/修改逻辑模型关系（校验字段存在于两端模型当前设计）
     *
     * @return 关系编号
     */
    Long saveRelation(LogicRelationSaveReqVO reqVO);

    /**
     * 删除逻辑模型关系
     */
    void deleteRelation(Long id, Long expectedRevision);

    /**
     * 批量保存模型 ER 画布坐标
     */
    void saveErLayout(LogicErLayoutSaveReqVO reqVO);

    /**
     * 影响分析：设计关系、部署目标
     */
    LogicImpactRespVO impactAnalysis(Long id);

}

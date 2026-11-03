package cn.iocoder.yudao.module.modeling.service.indicator;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.*;

import java.util.List;

public interface IndicatorModelService {

    /**
     * 创建指标定义。
     *
     * @param request 请求参数
     * @return 生成或保存的记录编号
     */
    Long create(IndicatorModelSaveReqVO request);

    /**
     * 更新草稿指标定义。
     *
     * @param request 请求参数
     */
    void update(IndicatorModelSaveReqVO request);

    /**
     * 删除未被引用的草稿指标。
     *
     * @param id 记录编号
     * @param expectedRevision 预期修订号
     */
    void delete(Long id, Long expectedRevision);

    /**
     * 启用或停用指标定义。
     *
     * @param request 请求参数
     */
    void updateStatus(IndicatorModelStatusReqVO request);

    /**
     * 获得指标定义详情。
     *
     * @param id 记录编号
     * @return 指标模型响应数据
     */
    IndicatorModelRespVO get(Long id);

    /**
     * 分页查询指标定义。
     *
     * @param request 请求参数
     * @return 分页查询指标定义结果
     */
    PageResult<IndicatorModelRespVO> page(IndicatorModelPageReqVO request);

    /**
     * 获得可引用的启用原子指标。
     *
     * @return 可引用的启用原子指标结果集合
     */
    List<IndicatorModelRespVO> atomicOptions();


    /**
     * 获得来源已发布当前设计的稳定字段。
     *
     * @param modelId 模型编号
     * @return 来源已发布当前设计的稳定字段结果集合
     */
    List<IndicatorSourceFieldRespVO> sourceFields(Long modelId);

    /**
     * 校验来源。
     *
     * @param modelId 模型编号
     * @param modelId 模型编号
     */
    void validateSource(Long modelId);

    /** 校验业务实体与来源已发布当前设计的相同归属，返回业务实体名称。 */
    String validateScopedSource(Long modelId, Long domainId, Long subjectId, Long entityId,
            String timeliness);
    /** 当前原子指标来源仍满足归属、字段与聚合类型约束时才可用于新生成。 */
    boolean isAtomicSourceUsable(cn.iocoder.yudao.module.modeling.dal.dataobject.indicator.IndicatorModelDO indicator);

}


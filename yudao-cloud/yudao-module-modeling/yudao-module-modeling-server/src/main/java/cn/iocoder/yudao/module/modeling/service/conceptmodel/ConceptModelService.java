package cn.iocoder.yudao.module.modeling.service.conceptmodel;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.modeling.controller.admin.conceptmodel.vo.*;
import jakarta.validation.Valid;

public interface ConceptModelService {

    /**
     * 创建概念模型草稿。
     *
     * @param request 请求参数
     * @return 生成或保存的记录编号
     */
    Long create(@Valid ConceptModelSaveReqVO request);

    /**
     * 修改概念模型及实体关系设计。
     *
     * @param request 请求参数
     */
    void update(@Valid ConceptModelUpdateReqVO request);

    /**
     * 启用或停用概念模型。
     *
     * @param request 请求参数
     */
    void updateStatus(@Valid ConceptModelStatusReqVO request);

    /**
     * 删除概念模型草稿。
     *
     * @param id 记录编号
     * @param expectedRevision 预期修订号
     */
    void delete(Long id, Long expectedRevision);

    /**
     * 获得概念模型及完整实体关系设计。
     *
     * @param id 记录编号
     * @return 概念模型响应数据
     */
    ConceptModelRespVO get(Long id);

    /**
     * 获得概念模型分页，不返回设计正文。
     *
     * @param request 请求参数
     * @return 概念模型分页，不返回设计正文结果
     */
    PageResult<ConceptModelRespVO> page(@Valid ConceptModelPageReqVO request);
}

package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.tasktemplate.QualityTaskTemplatePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.tasktemplate.QualityTaskTemplateRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.tasktemplate.QualityTaskTemplateSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskTemplateDO;

/**
 * 数据治理-数据质量-任务模板 Service 接口
 *
 * @author Li
 */
public interface QualityTaskTemplateService {

    /**
     * 创建任务模板。
     */
    Long createTemplate(QualityTaskTemplateSaveReqVO createReqVO);

    /**
     * 更新任务模板。
     */
    void updateTemplate(QualityTaskTemplateSaveReqVO updateReqVO);

    /**
     * 更新任务模板状态。
     */
    void updateTemplateStatus(Long id, Integer status);

    /**
     * 删除任务模板。
     */
    void deleteTemplate(Long id);

    /**
     * 获得任务模板。
     */
    QualityTaskTemplateDO getTemplate(Long id);

    /**
     * 获得任务模板分页。
     */
    PageResult<QualityTaskTemplateRespVO> getTemplatePage(QualityTaskTemplatePageReqVO pageReqVO);

    /**
     * 同步物化：按模板的「规则 + 表范围」批量生成实体检测任务
     *
     * @return 本次新生成的任务数
     */
    int materializeTemplate(Long id);

}

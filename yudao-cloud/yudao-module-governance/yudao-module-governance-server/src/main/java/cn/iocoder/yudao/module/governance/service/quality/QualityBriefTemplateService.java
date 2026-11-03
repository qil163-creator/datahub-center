package cn.iocoder.yudao.module.governance.service.quality;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief.QualityBriefTemplatePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief.QualityBriefTemplateRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief.QualityBriefTemplateSaveReqVO;

import java.util.List;

/**
 * 数据治理-数据质量-质检简报模板 Service 接口
 *
 * @author Li
 */
public interface QualityBriefTemplateService {

    /**
     * 创建简报模板（含段落 + 附列配置）。
     */
    Long createTemplate(QualityBriefTemplateSaveReqVO createReqVO);

    /**
     * 更新简报模板（段落全量覆盖）。
     */
    void updateTemplate(QualityBriefTemplateSaveReqVO updateReqVO);

    /**
     * 删除简报模板。
     */
    void deleteTemplate(Long id);

    /**
     * 模板详情（含段落 + 附列配置）
     */
    QualityBriefTemplateRespVO getTemplate(Long id);

    /**
     * 获得简报模板分页。
     */
    PageResult<QualityBriefTemplateRespVO> getTemplatePage(QualityBriefTemplatePageReqVO pageReqVO);

    /**
     * 获得简报模板精简列表（启用状态）。
     */
    List<QualityBriefTemplateRespVO> getTemplateSimpleList();

}

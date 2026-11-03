package cn.iocoder.yudao.module.governance.service.quality;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief.QualityBriefTemplatePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief.QualityBriefTemplateRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.brief.QualityBriefTemplateSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityBriefColumnDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityBriefParagraphDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityBriefTemplateDO;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityBriefColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityBriefParagraphMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityBriefTemplateMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertList;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_BRIEF_TEMPLATE_NAME_DUPLICATE;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.QUALITY_BRIEF_TEMPLATE_NOT_EXISTS;

/**
 * 数据治理-数据质量-质检简报模板 Service 实现
 *
 * 模板 = 基础信息 + 正文段落（变量插值）+ 附表配置（列定义），段落保存采用全量覆盖
 *
 * @author Li
 */
@Service
@Validated
public class QualityBriefTemplateServiceImpl implements QualityBriefTemplateService {

    @Resource
    private QualityBriefTemplateMapper templateMapper;

    @Resource
    private QualityBriefParagraphMapper paragraphMapper;

    @Resource
    private QualityBriefColumnMapper columnMapper;

    /**
     * 创建简报模板（含段落 + 附列配置）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createTemplate(QualityBriefTemplateSaveReqVO createReqVO) {
        validateNameUnique(null, createReqVO.getTemplateName());
        QualityBriefTemplateDO template = BeanUtils.toBean(createReqVO, QualityBriefTemplateDO.class);
        if (template.getStatus() == null) {
            template.setStatus(QualityBriefTemplateDO.STATUS_ENABLE);
        }
        if (template.getSortNo() == null) {
            template.setSortNo(0);
        }
        templateMapper.insert(template);
        saveParagraphs(template.getId(), createReqVO.getParagraphs());
        return template.getId();
    }

    /**
     * 更新简报模板（段落全量覆盖）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTemplate(QualityBriefTemplateSaveReqVO updateReqVO) {
        validateExists(updateReqVO.getId());
        validateNameUnique(updateReqVO.getId(), updateReqVO.getTemplateName());
        templateMapper.updateById(BeanUtils.toBean(updateReqVO, QualityBriefTemplateDO.class));
        deleteParagraphs(updateReqVO.getId());
        saveParagraphs(updateReqVO.getId(), updateReqVO.getParagraphs());
    }

    /**
     * 删除简报模板。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTemplate(Long id) {
        validateExists(id);
        templateMapper.deleteById(id);
        deleteParagraphs(id);
    }

    /**
     * 模板详情（含段落 + 附列配置）
     */
    @Override
    public QualityBriefTemplateRespVO getTemplate(Long id) {
        QualityBriefTemplateDO template = validateExists(id);
        QualityBriefTemplateRespVO respVO = BeanUtils.toBean(template, QualityBriefTemplateRespVO.class);
        List<QualityBriefParagraphDO> paragraphs = paragraphMapper.selectListByTemplateId(id);
        Map<Long, List<QualityBriefColumnDO>> columnMap = columnMapper.selectListByParagraphIds(
                        convertList(paragraphs, QualityBriefParagraphDO::getId)).stream()
                .collect(Collectors.groupingBy(QualityBriefColumnDO::getParagraphId));
        respVO.setParagraphs(paragraphs.stream().map(paragraph -> {
            QualityBriefTemplateRespVO.Paragraph vo =
                    BeanUtils.toBean(paragraph, QualityBriefTemplateRespVO.Paragraph.class);
            vo.setColumns(BeanUtils.toBean(columnMap.getOrDefault(paragraph.getId(), List.of()),
                    QualityBriefTemplateRespVO.Column.class));
            return vo;
        }).toList());
        return respVO;
    }

    /**
     * 获得简报模板分页。
     */
    @Override
    public PageResult<QualityBriefTemplateRespVO> getTemplatePage(QualityBriefTemplatePageReqVO pageReqVO) {
        return BeanUtils.toBean(templateMapper.selectPage(pageReqVO), QualityBriefTemplateRespVO.class);
    }

    /**
     * 获得简报模板精简列表（启用状态）。
     */
    @Override
    public List<QualityBriefTemplateRespVO> getTemplateSimpleList() {
        return BeanUtils.toBean(templateMapper.selectList(new LambdaQueryWrapperX<QualityBriefTemplateDO>()
                        .eq(QualityBriefTemplateDO::getStatus, QualityBriefTemplateDO.STATUS_ENABLE)
                        .orderByAsc(QualityBriefTemplateDO::getSortNo)),
                QualityBriefTemplateRespVO.class);
    }

    /**
     * 保存质量简报模板的段落。
     */
    private void saveParagraphs(Long templateId, List<QualityBriefTemplateSaveReqVO.Paragraph> paragraphs) {
        if (CollUtil.isEmpty(paragraphs)) {
            return;
        }
        for (QualityBriefTemplateSaveReqVO.Paragraph item : paragraphs) {
            QualityBriefParagraphDO paragraph = QualityBriefParagraphDO.builder()
                    .templateId(templateId)
                    .paraType(item.getParaType() == null
                            ? QualityBriefParagraphDO.TYPE_TEXT : item.getParaType())
                    .sortNo(item.getSortNo() == null ? 0 : item.getSortNo())
                    .title(item.getTitle())
                    .contentText(item.getContentText())
                    .ruleSetId(item.getRuleSetId())
                    .build();
            paragraphMapper.insert(paragraph);
            if (CollUtil.isNotEmpty(item.getColumns())) {
                columnMapper.insertBatch(item.getColumns().stream()
                        .map(column -> QualityBriefColumnDO.builder()
                                .paragraphId(paragraph.getId())
                                .fieldName(column.getFieldName())
                                .colTitle(column.getColTitle())
                                .dataField(column.getDataField())
                                .format(column.getFormat())
                                .sortNo(column.getSortNo() == null ? 0 : column.getSortNo())
                                .build())
                        .toList());
            }
        }
    }

    /**
     * 删除段落。
     */
    private void deleteParagraphs(Long templateId) {
        List<Long> paragraphIds = convertList(paragraphMapper.selectListByTemplateId(templateId),
                QualityBriefParagraphDO::getId);
        paragraphMapper.deleteByTemplateId(templateId);
        columnMapper.deleteByParagraphIds(paragraphIds);
    }

    /**
     * 校验质量简报模板名称在指定范围内唯一。
     */
    private void validateNameUnique(Long id, String templateName) {
        QualityBriefTemplateDO existing = templateMapper.selectByName(templateName);
        if (existing != null && !Objects.equals(existing.getId(), id)) {
            throw exception(QUALITY_BRIEF_TEMPLATE_NAME_DUPLICATE);
        }
    }

    /**
     * 读取并校验质量简报模板记录存在，不存在时抛出业务异常。
     */
    private QualityBriefTemplateDO validateExists(Long id) {
        QualityBriefTemplateDO template = id == null ? null : templateMapper.selectById(id);
        if (template == null) {
            throw exception(QUALITY_BRIEF_TEMPLATE_NOT_EXISTS);
        }
        return template;
    }

}

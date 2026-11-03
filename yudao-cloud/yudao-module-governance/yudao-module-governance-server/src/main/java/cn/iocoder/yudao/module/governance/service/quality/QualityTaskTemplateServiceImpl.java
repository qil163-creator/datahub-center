package cn.iocoder.yudao.module.governance.service.quality;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.mybatis.core.query.LambdaQueryWrapperX;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.tasktemplate.QualityTaskTemplatePageReqVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.tasktemplate.QualityTaskTemplateRespVO;
import cn.iocoder.yudao.module.governance.controller.admin.quality.vo.tasktemplate.QualityTaskTemplateSaveReqVO;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaTableDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.*;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.*;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Objects;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.*;

/**
 * 数据治理-数据质量-任务模板 Service 实现
 *
 * <p>模板物化：把「规则集/指定规则 + 表范围 + 字段选择策略」批量生成实体检测任务，
 * 生成的任务本身是手动调度（避免海量任务各自注册定时器并发打满引擎），调度挂在模板上
 *
 * @author Li
 */
@Service
@Validated
public class QualityTaskTemplateServiceImpl implements QualityTaskTemplateService {

    /** 物化任务的编码前缀：TPL-{模板ID}-{表ID} */
    private static final String TASK_CODE_PREFIX = "TPL-";

    @Resource
    private QualityTaskTemplateMapper templateMapper;

    @Resource
    private QualityTaskMapper taskMapper;

    @Resource
    private QualityTaskObjectMapper taskObjectMapper;

    @Resource
    private QualityTaskRuleMapper taskRuleMapper;

    @Resource
    private QualityCheckObjectMapper checkObjectMapper;

    @Resource
    private MetaTableMapper metaTableMapper;

    /**
     * 创建任务模板。
     */
    @Override
    public Long createTemplate(QualityTaskTemplateSaveReqVO createReqVO) {
        validateNameUnique(null, createReqVO.getTemplateName());
        QualityTaskTemplateDO template = BeanUtils.toBean(createReqVO, QualityTaskTemplateDO.class);
        applyDefaults(template);
        templateMapper.insert(template);
        return template.getId();
    }

    /**
     * 更新任务模板。
     */
    @Override
    public void updateTemplate(QualityTaskTemplateSaveReqVO updateReqVO) {
        validateExists(updateReqVO.getId());
        validateNameUnique(updateReqVO.getId(), updateReqVO.getTemplateName());
        templateMapper.updateById(BeanUtils.toBean(updateReqVO, QualityTaskTemplateDO.class));
    }

    /**
     * 更新任务模板状态。
     */
    @Override
    public void updateTemplateStatus(Long id, Integer status) {
        validateExists(id);
        templateMapper.updateById(QualityTaskTemplateDO.builder().id(id).status(status).build());
    }

    /**
     * 删除任务模板。
     */
    @Override
    public void deleteTemplate(Long id) {
        validateExists(id);
        templateMapper.deleteById(id);
    }

    /**
     * 获得任务模板。
     */
    @Override
    public QualityTaskTemplateDO getTemplate(Long id) {
        return templateMapper.selectById(id);
    }

    /**
     * 获得任务模板分页。
     */
    @Override
    public PageResult<QualityTaskTemplateRespVO> getTemplatePage(QualityTaskTemplatePageReqVO pageReqVO) {
        PageResult<QualityTaskTemplateDO> pageResult = templateMapper.selectPage(pageReqVO);
        PageResult<QualityTaskTemplateRespVO> result = BeanUtils.toBean(pageResult, QualityTaskTemplateRespVO.class);
        for (QualityTaskTemplateRespVO vo : result.getList()) {
            vo.setGeneratedTaskCount(taskMapper.selectCount(new LambdaQueryWrapperX<QualityTaskDO>()
                    .likeRight(QualityTaskDO::getTaskCode, TASK_CODE_PREFIX + vo.getId() + "-")));
        }
        return result;
    }

    /**
     * 同步物化：按模板的「规则 + 表范围」批量生成实体检测任务
     *
     *  @return 本次新生成的任务数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int materializeTemplate(Long id) {
        QualityTaskTemplateDO template = validateExists(id);
        List<MetaTableDO> tables = resolveTableScope(template);
        List<Long> ruleIds = resolveRuleIds(template);
        int limit = template.getTaskLimit() == null ? 5000 : template.getTaskLimit();
        int generated = 0;
        for (MetaTableDO table : tables) {
            if (generated >= limit) {
                break;
            }
            String taskCode = TASK_CODE_PREFIX + template.getId() + "-" + table.getId();
            // 幂等：已物化过的表跳过（全量同步只补新表）
            if (taskMapper.selectCount(new LambdaQueryWrapperX<QualityTaskDO>()
                    .eq(QualityTaskDO::getTaskCode, taskCode)) > 0) {
                continue;
            }
            Long objectId = findOrCreateCheckObject(template, table);
            QualityTaskDO task = QualityTaskDO.builder()
                    .taskName(template.getTemplateName() + "-" + table.getTableName())
                    .taskCode(taskCode)
                    .taskCategory("模板物化")
                    .executeStrategy(QualityTaskDO.STRATEGY_SERIAL)
                    // 生成的任务为手动调度，统一由模板调度触发，防止海量定时器并发
                    .scheduleType(QualityTaskDO.SCHEDULE_MANUAL)
                    .taskStatus(QualityTaskDO.STATUS_ONLINE)
                    .priority("MID")
                    .retryCount(0)
                    .ruleSetId(QualityTaskTemplateDO.RULE_SOURCE_RULE_SET.equals(template.getRuleSourceType())
                            ? template.getRuleSetId() : null)
                    .orgField(template.getOrgField())
                    .groupField(template.getGroupField())
                    .description("由任务模板「" + template.getTemplateName() + "」物化生成")
                    .build();
            taskMapper.insert(task);
            taskObjectMapper.insert(QualityTaskObjectDO.builder()
                    .taskId(task.getId()).objectId(objectId).build());
            if (CollUtil.isNotEmpty(ruleIds)) {
                taskRuleMapper.insertBatch(ruleIds.stream()
                        .map(ruleId -> QualityTaskRuleDO.builder().taskId(task.getId()).ruleId(ruleId).build())
                        .toList());
            }
            generated++;
        }
        return generated;
    }

    /**
     * 按模板表范围取元数据表清单
     */
    private List<MetaTableDO> resolveTableScope(QualityTaskTemplateDO template) {
        LambdaQueryWrapperX<MetaTableDO> wrapper = new LambdaQueryWrapperX<>();
        wrapper.eq(MetaTableDO::getDatasourceId, template.getDatasourceConnId());
        String scopeType = StrUtil.blankToDefault(template.getTableScopeType(),
                QualityTaskTemplateDO.SCOPE_DATASOURCE);
        switch (scopeType) {
            case QualityTaskTemplateDO.SCOPE_TABLE_MATCH ->
                    wrapper.like(MetaTableDO::getTableName, template.getTableMatch());
            case QualityTaskTemplateDO.SCOPE_SPECIFY_TABLE -> {
                List<Long> tableIds = parseIds(template.getTableIds());
                if (tableIds.isEmpty()) {
                    return List.of();
                }
                wrapper.in(MetaTableDO::getId, tableIds);
            }
            case QualityTaskTemplateDO.SCOPE_LAYER -> wrapper.eq(MetaTableDO::getLayer, template.getLayer());
            default -> { /* DATASOURCE：整个数据源，不加额外过滤 */ }
        }
        wrapper.orderByAsc(MetaTableDO::getId);
        return metaTableMapper.selectList(wrapper);
    }

    /**
     * 规则来源：两者都填以规则集为准（与 DataQ 语义一致）
     */
    private List<Long> resolveRuleIds(QualityTaskTemplateDO template) {
        if (QualityTaskTemplateDO.RULE_SOURCE_RULE_SET.equals(template.getRuleSourceType())
                && template.getRuleSetId() != null) {
            // 任务执行时会自动回退取规则集下的规则，这里无需展开
            return List.of();
        }
        return parseIds(template.getRuleIds());
    }

    /**
     * 查找或创建检查对象。
     */
    private Long findOrCreateCheckObject(QualityTaskTemplateDO template, MetaTableDO table) {
        QualityCheckObjectDO existing = checkObjectMapper.selectOne(
                new LambdaQueryWrapperX<QualityCheckObjectDO>()
                        .eq(QualityCheckObjectDO::getDatasourceConnId, template.getDatasourceConnId())
                        .eq(QualityCheckObjectDO::getTableName, table.getTableName())
                        .last("LIMIT 1"));
        if (existing != null) {
            return existing.getId();
        }
        QualityCheckObjectDO checkObject = QualityCheckObjectDO.builder()
                .objectName(template.getTemplateName() + "-" + table.getTableName())
                .datasourceConnId(template.getDatasourceConnId())
                .tableName(table.getTableName())
                .tableId(table.getId())
                .remark("由任务模板物化自动创建")
                .build();
        checkObjectMapper.insert(checkObject);
        return checkObject.getId();
    }

    /**
     * 解析质量任务模板的编号。
     */
    private List<Long> parseIds(String idsText) {
        if (StrUtil.isBlank(idsText)) {
            return List.of();
        }
        return StrUtil.split(idsText, ',').stream()
                .map(String::trim).filter(StrUtil::isNotBlank)
                .map(Long::valueOf).distinct().toList();
    }

    /**
     * 应用默认值。
     */
    private void applyDefaults(QualityTaskTemplateDO template) {
        if (StrUtil.isBlank(template.getRuleSourceType())) {
            template.setRuleSourceType(QualityTaskTemplateDO.RULE_SOURCE_RULE_SET);
        }
        if (StrUtil.isBlank(template.getTableScopeType())) {
            template.setTableScopeType(QualityTaskTemplateDO.SCOPE_DATASOURCE);
        }
        if (StrUtil.isBlank(template.getScheduleType())) {
            template.setScheduleType(QualityTaskDO.SCHEDULE_MANUAL);
        }
        if (StrUtil.isBlank(template.getSyncMode())) {
            template.setSyncMode("FULL");
        }
        if (template.getTaskLimit() == null) {
            template.setTaskLimit(5000);
        }
        if (template.getStatus() == null) {
            template.setStatus(QualityTaskTemplateDO.STATUS_ENABLE);
        }
    }

    /**
     * 校验质量任务模板名称在指定范围内唯一。
     */
    private void validateNameUnique(Long id, String templateName) {
        QualityTaskTemplateDO existing = templateMapper.selectByName(templateName);
        if (existing != null && !Objects.equals(existing.getId(), id)) {
            throw exception(QUALITY_TASK_TEMPLATE_NAME_DUPLICATE);
        }
    }

    /**
     * 读取并校验质量任务模板记录存在，不存在时抛出业务异常。
     */
    private QualityTaskTemplateDO validateExists(Long id) {
        QualityTaskTemplateDO template = id == null ? null : templateMapper.selectById(id);
        if (template == null) {
            throw exception(QUALITY_TASK_TEMPLATE_NOT_EXISTS);
        }
        return template;
    }

}

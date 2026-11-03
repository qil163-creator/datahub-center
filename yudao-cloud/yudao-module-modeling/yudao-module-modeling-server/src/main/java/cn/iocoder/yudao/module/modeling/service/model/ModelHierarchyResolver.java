package cn.iocoder.yudao.module.modeling.service.model;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelHierarchyRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelIntegrityCheckRespVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DataDomainDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.BizCategoryDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.BizCategoryDataDomainRelDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.BizCategoryMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.BizCategoryDataDomainRelMapper;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.DwLayerDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.planning.SubjectDomainDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.DataDomainMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.DwLayerMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.SubjectDomainMapper;
import cn.iocoder.yudao.module.modeling.enums.ModelClassificationStatusEnum;
import cn.iocoder.yudao.module.modeling.enums.ModelTableTypeEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cn.iocoder.yudao.framework.common.exception.ServiceException;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertMap;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertSet;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;

/**
 * 模型规范归属解析器。
 *
 * <p>业务归属与技术分层是正交轴；以下为本项目的用途规则：
 * <ul>
 *   <li>逻辑/物理模型发布要求有效分层、表用途和数据域；主题域为可选分析分组</li>
 *   <li>classificationStatus 是保存时归属评估；实时归属检查不替代字段、标准和部署门禁</li>
 *   <li>建模阶段可暂缺；完整性检测只读列缺项，不定主域、不自动改模型</li>
 * </ul>
 */
@Component
public class ModelHierarchyResolver {

    public static final String LAYER_CODE_NONE = "NONE";

    public static final String LAYER_CODE_ODS = "ODS";

    @Resource
    private DwLayerMapper dwLayerMapper;

    @Resource
    private SubjectDomainMapper subjectDomainMapper;

    @Resource
    private DataDomainMapper dataDomainMapper;

    @Resource
    private BizCategoryMapper bizCategoryMapper;

    @Resource
    private BizCategoryDataDomainRelMapper bizCategoryDataDomainRelMapper;

    /**
     * 校验分层。
     *
     * @param layerId 分层编号
     * @param required 必需
     * @param existingId 已有编号
     * @return 数仓分层
     */
    public DwLayerDO validateLayer(Long layerId, boolean required, Long existingId) {
        if (layerId == null) {
            if (required) {
                throw exception(MODEL_LAYER_REQUIRED);
            }
            return null;
        }
        DwLayerDO layer = dwLayerMapper.selectById(layerId);
        if (layer == null) {
            throw exception(MODEL_LAYER_NOT_EXISTS);
        }
        if (!DwLayerDO.NODE_TYPE_LAYER.equals(layer.getNodeType())) {
            throw exception(MODEL_LAYER_NOT_LEAF);
        }
        boolean changing = existingId == null || !existingId.equals(layerId);
        if (changing && isDisabled(layer)) {
            throw exception(MODEL_LAYER_DISABLED);
        }
        return layer;
    }

    /**
     * 判断是否满足禁用状态条件。
     *
     * @param layer 分层
     * @return 判断结果
     */
    private boolean isDisabled(DwLayerDO layer) {
        return layer.getStatus() != null
                && CommonStatusEnum.isDisable(layer.getStatus().intValue());
    }

    /**
     * 查找分层。
     *
     * @param layerId 分层编号
     * @return 数仓分层
     */
    public DwLayerDO findLayer(Long layerId) {
        return layerId == null ? null : dwLayerMapper.selectById(layerId);
    }

    /**
     * 校验主题域。
     *
     * @param subjectDomainId 主题域编号
     * @param existingId 已有编号
     * @param dataDomainId 数据域编号
     */
    public void validateSubjectDomain(Long subjectDomainId, Long existingId, Long dataDomainId) {
        if (subjectDomainId == null) {
            return;
        }
        SubjectDomainDO domain = subjectDomainMapper.selectById(subjectDomainId);
        if (domain == null) {
            throw exception(MODEL_SUBJECT_DOMAIN_NOT_EXISTS);
        }
        if (!java.util.Objects.equals(domain.getDataDomainId(), dataDomainId)) {
            throw exception(MODEL_SUBJECT_DATA_DOMAIN_MISMATCH);
        }
        boolean changing = existingId == null || !existingId.equals(subjectDomainId);
        if (changing && CommonStatusEnum.isDisable(domain.getStatus())) {
            throw exception(MODEL_SUBJECT_DOMAIN_DISABLED);
        }
    }

    /**
     * 校验模型直接选择的数据域存在且可用。
     */
    public DataDomainDO validateModelDataDomain(Long dataDomainId) {
        if (dataDomainId == null) {
            return null;
        }
        DataDomainDO domain = dataDomainMapper.selectById(dataDomainId);
        if (domain == null) {
            throw exception(MODEL_DATA_DOMAIN_NOT_EXISTS);
        }
        if (CommonStatusEnum.isDisable(domain.getStatus())) {
            throw exception(MODEL_DATA_DOMAIN_DISABLED);
        }
        return domain;
    }

    /**
     * 解析表类型。
     *
     * @param tableType 表类型
     * @param layer 分层
     * @return 解析表类型文本
     */
    public String resolveTableType(String tableType, DwLayerDO layer) {
        if (StrUtil.isNotBlank(tableType)) {
            if (!ModelTableTypeEnum.isValid(tableType)) {
                throw exception(MODEL_TABLE_TYPE_INVALID, tableType);
            }
            return tableType;
        }
        if (layer == null || !ModelTableTypeEnum.isValid(layer.getDefaultTableType())) {
            return null;
        }
        return layer.getDefaultTableType();
    }

    /**
     * 解析分层编码。
     *
     * @param layer 分层
     * @return 解析分层编码文本
     */
    public String resolveLayerCode(DwLayerDO layer) {
        return layer == null || StrUtil.isBlank(layer.getLayerCode()) ? LAYER_CODE_NONE : layer.getLayerCode();
    }

    /**
     * 判断是否满足ODS分层条件。
     *
     * @param layer 分层
     * @return 判断结果
     */
    public boolean isOdsLayer(DwLayerDO layer) {
        return layer != null && LAYER_CODE_ODS.equalsIgnoreCase(StrUtil.blankToDefault(layer.getLayerCode(), ""));
    }

    /**
     * 业务归属是否完备：按当层 B 规则判定（不再用「仅分层+表类型」）。
     */
    public String resolveClassificationStatus(Long layerId, Long subjectDomainId,
            Long dataDomainId,
            String tableType) {
        return resolveClassificationStatus(layerId, subjectDomainId, dataDomainId, tableType, null);
    }

    /**
     * 业务归属是否完备：按当层 B 规则判定（不再用「仅分层+表类型」）。
     *
     * @param layerId 分层编号
     * @param subjectDomainId 主题域编号
     * @param dataDomainId 数据域编号
     * @param tableType 表类型
     * @param bizCategoryId 业务分类编号
     * @return 业务归属是否完备：按当层 B 规则判定（不再用「仅分层+表类型」）文本
     */
    public String resolveClassificationStatus(Long layerId, Long subjectDomainId,
            Long dataDomainId,
            String tableType, Long bizCategoryId) {
        List<String> gaps = listIntegrityGaps(layerId, subjectDomainId, dataDomainId, tableType, bizCategoryId);
        return gaps.isEmpty() ? ModelClassificationStatusEnum.CONFIRMED.getStatus()
                : ModelClassificationStatusEnum.UNCLASSIFIED.getStatus();
    }

    /**
     * 只读完整性清单：列缺项，不改模型、不定主域。
     */
    public List<String> listIntegrityGaps(Long layerId, Long subjectDomainId,
            Long dataDomainId,
            String tableType) {
        return listIntegrityGaps(layerId, subjectDomainId, dataDomainId, tableType, null);
    }

    /**
     * 只读完整性清单：列缺项，不改模型、不定主域。
     *
     * @param layerId 分层编号
     * @param subjectDomainId 主题域编号
     * @param dataDomainId 数据域编号
     * @param tableType 表类型
     * @param bizCategoryId 业务分类编号
     * @return 只读完整性清单：列缺项，不改模型、不定主域结果集合
     */
    public List<String> listIntegrityGaps(Long layerId, Long subjectDomainId,
            Long dataDomainId,
            String tableType, Long bizCategoryId) {
        return evaluateOwnership(layerId, subjectDomainId, dataDomainId, tableType, bizCategoryId)
                .stream().map(ModelIntegrityCheckRespVO.Issue::getRuleCode).toList();
    }

    /** 同一归属评估用于展示和发布。 */
    public List<ModelIntegrityCheckRespVO.Issue> evaluateOwnership(Long layerId,
            Long subjectDomainId, Long dataDomainId, String tableType, Long bizCategoryId) {
        List<ModelIntegrityCheckRespVO.Issue> issues = new ArrayList<>();
        check(issues, "LAYER", "layerId", () -> validateLayer(layerId, true, null));
        check(issues, "TABLE_TYPE", "tableType", () -> {
            if (StrUtil.isBlank(tableType)) {
                throw exception(MODEL_TABLE_TYPE_REQUIRED);
            }
            resolveTableType(tableType, null);
        });
        check(issues, "SUBJECT_DOMAIN", "subjectDomainId",
                () -> validateSubjectDomain(subjectDomainId, null, dataDomainId));
        check(issues, "DATA_DOMAIN", "dataDomainId", () -> {
            if (dataDomainId == null) {
                throw exception(MODEL_DATA_DOMAIN_REQUIRED);
            }
            validateModelDataDomain(dataDomainId);
        });
        String tt = StrUtil.trim(tableType);
        boolean source = "SOURCE".equalsIgnoreCase(tt);
        boolean application = "APPLICATION".equalsIgnoreCase(tt);
        // SOURCE：不强制业务分类。APPLICATION：走主题域，不强制业务分类，必填主题域。
        // 其它非 SOURCE：必填启用业务分类；有 0136 关联时校验域一致性。
        if (application) {
            check(issues, "SUBJECT_DOMAIN", "subjectDomainId", () -> {
                if (subjectDomainId == null) {
                    throw exception(MODEL_SUBJECT_DOMAIN_REQUIRED);
                }
                validateSubjectDomain(subjectDomainId, null, dataDomainId);
            });
        } else if (!source) {
            check(issues, "BIZ_CATEGORY", "bizCategoryId", () -> validateBizCategory(bizCategoryId, true));
            if (bizCategoryId != null && dataDomainId != null) {
                check(issues, "BIZ_CATEGORY", "dataDomainId", () ->
                        validateBizCategoryDataDomainConsistency(bizCategoryId, dataDomainId));
            }
        }
        return issues;
    }

    /** 兼容旧调用：不传业务分类（非 SOURCE 会记 BIZ_CATEGORY 缺口） */
    public List<ModelIntegrityCheckRespVO.Issue> evaluateOwnership(Long layerId,
            Long subjectDomainId, Long dataDomainId, String tableType) {
        return evaluateOwnership(layerId, subjectDomainId, dataDomainId, tableType, null);
    }

    /**
     * 校验业务分类。
     *
     * @param bizCategoryId 业务分类编号
     * @param required 必需
     */
    public void validateBizCategory(Long bizCategoryId, boolean required) {
        if (bizCategoryId == null) {
            if (required) {
                throw exception(MODEL_BIZ_CATEGORY_REQUIRED);
            }
            return;
        }
        BizCategoryDO category = bizCategoryMapper.selectById(bizCategoryId);
        if (category == null) {
            throw exception(MODEL_BIZ_CATEGORY_NOT_EXISTS);
        }
        if (category.getStatus() != null && category.getStatus() == 1) {
            // CommonStatusEnum DISABLE=1 in yudao
            throw exception(MODEL_BIZ_CATEGORY_DISABLED);
        }
    }

    /**
     * 校验业务分类数据域一致性。
     *
     * @param bizCategoryId 业务分类编号
     * @param dataDomainId 数据域编号
     */
    public void validateBizCategoryDataDomainConsistency(Long bizCategoryId, Long dataDomainId) {
        List<BizCategoryDataDomainRelDO> rels =
                bizCategoryDataDomainRelMapper.selectListByBizCategoryId(bizCategoryId);
        if (CollUtil.isEmpty(rels)) {
            // 分类未绑任何域：不强制一致性（仅有绑定时校验）
            return;
        }
        boolean ok = rels.stream().anyMatch(r -> dataDomainId.equals(r.getDataDomainId()));
        if (!ok) {
            throw exception(MODEL_BIZ_CATEGORY_DATA_DOMAIN_MISMATCH);
        }
    }


    /**
     * 检查模型层级。
     *
     * @param issues 问题
     * @param code 编码
     * @param field 字段
     * @param rule 规则
     */
    private void check(List<ModelIntegrityCheckRespVO.Issue> issues, String code, String field, Runnable rule) {
        try {
            rule.run();
        } catch (ServiceException ex) {
            ModelIntegrityCheckRespVO.Issue issue = new ModelIntegrityCheckRespVO.Issue();
            issue.setRuleCode(code);
            issue.setField(field);
            issue.setMessage(ex.getMessage());
            issues.add(issue);
        }
    }

    /**
     * 构造完整性检查。
     *
     * @param layerId 分层编号
     * @param subjectDomainId 主题域编号
     * @param dataDomainId 数据域编号
     * @param tableType 表类型
     * @return 模型完整性检查响应数据
     */
    public ModelIntegrityCheckRespVO buildIntegrityCheck(Long layerId, Long subjectDomainId,
            Long dataDomainId, String tableType) {
        return buildIntegrityCheck(layerId, subjectDomainId, dataDomainId, tableType, null);
    }

    /**
     * 构造完整性检查。
     *
     * @param layerId 分层编号
     * @param subjectDomainId 主题域编号
     * @param dataDomainId 数据域编号
     * @param tableType 表类型
     * @param bizCategoryId 业务分类编号
     * @return 模型完整性检查响应数据
     */
    public ModelIntegrityCheckRespVO buildIntegrityCheck(Long layerId, Long subjectDomainId,
            Long dataDomainId, String tableType, Long bizCategoryId) {
        List<ModelIntegrityCheckRespVO.Issue> issues =
                evaluateOwnership(layerId, subjectDomainId, dataDomainId, tableType, bizCategoryId);
        List<String> gaps = issues.stream().map(ModelIntegrityCheckRespVO.Issue::getRuleCode).toList();
        ModelIntegrityCheckRespVO resp = new ModelIntegrityCheckRespVO();
        resp.setIssues(issues);
        resp.setComplete(gaps.isEmpty());
        resp.setMissingItems(gaps);
        resp.setClassificationStatus(gaps.isEmpty()
                ? ModelClassificationStatusEnum.CONFIRMED.getStatus()
                : ModelClassificationStatusEnum.UNCLASSIFIED.getStatus());
        DwLayerDO layer = layerId == null ? null : dwLayerMapper.selectById(layerId);
        resp.setLayerCode(resolveLayerCode(layer));
        resp.setOdsRule(isOdsLayer(layer));
        return resp;
    }

    /**
     * 校验层级事件发布。
     *
     * @param layerId 分层编号
     * @param tableType 表类型
     */
    public void validateHierarchyOnPublish(Long layerId, String tableType) {
        if (layerId == null) {
            throw exception(MODEL_LAYER_REQUIRED);
        }
        if (StrUtil.isBlank(tableType)) {
            throw exception(MODEL_TABLE_TYPE_REQUIRED);
        }
    }

    /**
     * 校验层级事件发布。
     *
     * @param layerId 分层编号
     * @param subjectDomainId 主题域编号
     * @param dataDomainId 数据域编号
     * @param tableType 表类型
     */
    public void validateHierarchyOnPublish(Long layerId, Long subjectDomainId,
            Long dataDomainId,
            String tableType) {
        validateOwnershipOnPublish(layerId, subjectDomainId, dataDomainId, tableType, null);
    }

    /**
     * 校验层级事件发布。
     *
     * @param layerId 分层编号
     * @param subjectDomainId 主题域编号
     * @param dataDomainId 数据域编号
     * @param tableType 表类型
     * @param bizCategoryId 业务分类编号
     */
    public void validateHierarchyOnPublish(Long layerId, Long subjectDomainId,
            Long dataDomainId,
            String tableType, Long bizCategoryId) {
        validateOwnershipOnPublish(layerId, subjectDomainId, dataDomainId, tableType, bizCategoryId);
    }

    /**
     * 校验归属事件发布。
     *
     * @param layerId 分层编号
     * @param subjectDomainId 主题域编号
     * @param dataDomainId 数据域编号
     * @param tableType 表类型
     */
    public void validateOwnershipOnPublish(Long layerId, Long subjectDomainId,
            Long dataDomainId, String tableType) {
        validateOwnershipOnPublish(layerId, subjectDomainId, dataDomainId, tableType, null);
    }

    /**
     * 校验归属事件发布。
     *
     * @param layerId 分层编号
     * @param subjectDomainId 主题域编号
     * @param dataDomainId 数据域编号
     * @param tableType 表类型
     * @param bizCategoryId 业务分类编号
     */
    public void validateOwnershipOnPublish(Long layerId, Long subjectDomainId,
            Long dataDomainId, String tableType, Long bizCategoryId) {
        List<ModelIntegrityCheckRespVO.Issue> issues =
                evaluateOwnership(layerId, subjectDomainId, dataDomainId, tableType, bizCategoryId);
        if (!issues.isEmpty()) {
            throw exception(MODEL_OWNERSHIP_INCOMPLETE, issues.stream()
                    .map(ModelIntegrityCheckRespVO.Issue::getMessage)
                    .collect(java.util.stream.Collectors.joining("；")));
        }
    }

    /**
     * 补充层级。
     *
     * @param list 待处理列表
     */
    public void fillHierarchy(Collection<? extends ModelHierarchyRespVO> list) {
        if (CollUtil.isEmpty(list)) {
            return;
        }
        Set<Long> layerIds = convertSet(list, ModelHierarchyRespVO::getLayerId);
        Map<Long, DwLayerDO> layerMap = CollUtil.isEmpty(layerIds) ? Map.of()
                : convertMap(dwLayerMapper.selectBatchIds(layerIds), DwLayerDO::getId);

        Set<Long> subjectDomainIds = convertSet(list, ModelHierarchyRespVO::getSubjectDomainId);
        Map<Long, SubjectDomainDO> subjectDomainMap = CollUtil.isEmpty(subjectDomainIds) ? Map.of()
                : convertMap(subjectDomainMapper.selectBatchIds(subjectDomainIds), SubjectDomainDO::getId);

        Set<Long> dataDomainIds = convertSet(list, ModelHierarchyRespVO::getDataDomainId);
        Map<Long, DataDomainDO> dataDomainMap = CollUtil.isEmpty(dataDomainIds) ? Map.of()
                : convertMap(dataDomainMapper.selectBatchIds(dataDomainIds), DataDomainDO::getId);

        Set<Long> bizCategoryIds = convertSet(list, ModelHierarchyRespVO::getBizCategoryId);
        Map<Long, BizCategoryDO> bizCategoryMap = CollUtil.isEmpty(bizCategoryIds) ? Map.of()
                : convertMap(bizCategoryMapper.selectBatchIds(bizCategoryIds), BizCategoryDO::getId);

        for (ModelHierarchyRespVO target : list) {
            DwLayerDO layer = target.getLayerId() == null ? null : layerMap.get(target.getLayerId());
            SubjectDomainDO subjectDomain = target.getSubjectDomainId() == null ? null
                    : subjectDomainMap.get(target.getSubjectDomainId());
            if (layer != null) {
                target.setLayerCode(layer.getLayerCode());
                target.setLayerName(layer.getLayerName());
            }
            if (subjectDomain != null) {
                target.setSubjectDomainName(subjectDomain.getName());
            }
            DataDomainDO modelDomain = target.getDataDomainId() == null ? null
                    : dataDomainMap.get(target.getDataDomainId());
            if (modelDomain != null) {
                target.setDataDomainName(modelDomain.getName());
            }
            BizCategoryDO bizCategory = target.getBizCategoryId() == null ? null : bizCategoryMap.get(
                    target.getBizCategoryId());
            target.setBizCategoryName(bizCategory == null ? null : bizCategory.getName());
            target.setBizCategoryCode(bizCategory == null ? null : bizCategory.getCode());
            target.setHierarchyPath(buildHierarchyPath(layer, subjectDomain, modelDomain));
        }
    }

    /**
     * 构造层级路径。
     *
     * @param layer 分层
     * @param subjectDomain 主题域
     * @param domain 域
     * @return 构造层级路径文本
     */
    private String buildHierarchyPath(DwLayerDO layer, SubjectDomainDO subjectDomain,
            DataDomainDO domain) {
        List<String> segments = new ArrayList<>(4);
        if (domain != null) {
            segments.add("数据域：" + domain.getName());
        }
        if (layer != null && StrUtil.isNotBlank(layer.getLayerName())) {
            segments.add("技术分层：" + (StrUtil.isBlank(layer.getLayerCode()) ? layer.getLayerName()
                    : layer.getLayerName() + "(" + layer.getLayerCode() + ")"));
        }
        if (subjectDomain != null && StrUtil.isNotBlank(subjectDomain.getName())) {
            segments.add("分析主题：" + subjectDomain.getName());
        }
        return segments.isEmpty() ? null : String.join(" ｜ ", segments);
    }

}

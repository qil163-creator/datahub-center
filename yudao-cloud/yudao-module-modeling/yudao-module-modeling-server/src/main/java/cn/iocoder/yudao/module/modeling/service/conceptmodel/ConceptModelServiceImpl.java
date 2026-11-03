package cn.iocoder.yudao.module.modeling.service.conceptmodel;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.modeling.controller.admin.conceptmodel.vo.*;
import cn.iocoder.yudao.module.modeling.dal.dataobject.conceptmodel.ConceptModelDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.conceptmodel.ConceptModelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.DataDomainMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.planning.SubjectDomainMapper;
import cn.iocoder.yudao.module.modeling.framework.mybatis.core.type.JsonbStringTypeHandler;
import cn.iocoder.yudao.module.modeling.service.model.ModelHierarchyResolver;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;

/** 概念模型属于平台共享规划对象，以资源 RBAC 授权；设计修订由数据库 CAS 保证。 */
@Service
@Validated
public class ConceptModelServiceImpl implements ConceptModelService {

    @Resource
    private ConceptModelMapper mapper;

    @Resource
    private ModelHierarchyResolver hierarchyResolver;

    @Resource
    private DataDomainMapper dataDomainMapper;

    @Resource
    private SubjectDomainMapper subjectDomainMapper;

    /**
     * 创建概念模型草稿。
     *
     * @param request 请求参数
     * @return 生成或保存的记录编号
     */
    @Override
    public Long create(ConceptModelSaveReqVO request) {
        validateOwnership(request.getDataDomainId(), request.getSubjectDomainId());
        ConceptModelDesignVO design = normalizeDesign(request.getDesign());
        ConceptModelDO row = new ConceptModelDO();
        row.setName(request.getName().trim());
        row.setCode(normalizeCode(request.getCode()));
        row.setDataDomainId(request.getDataDomainId());
        row.setSubjectDomainId(request.getSubjectDomainId());
        row.setDescription(request.getDescription());
        row.setDesign(JsonUtils.toJsonString(design));
        row.setStatus(ConceptModelDO.STATUS_DRAFT);
        row.setRevision(0L);
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException duplicate) {
            throw exception(CONCEPT_MODEL_DUPLICATE);
        }
        return row.getId();
    }

    /**
     * 修改概念模型及实体关系设计。
     *
     * @param request 请求参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(ConceptModelUpdateReqVO request) {
        ConceptModelDO existing = requireRevision(request.getId(), request.getExpectedRevision());
        requireDraft(existing);
        validateOwnership(request.getDataDomainId(), request.getSubjectDomainId());
        String design = JsonUtils.toJsonString(normalizeDesign(request.getDesign()));
        try {
            int affected = mapper.update(null, Wrappers.lambdaUpdate(ConceptModelDO.class)
                    .set(ConceptModelDO::getName, request.getName().trim())
                    .set(ConceptModelDO::getCode, normalizeCode(request.getCode()))
                    .set(ConceptModelDO::getDataDomainId, request.getDataDomainId())
                    .set(ConceptModelDO::getSubjectDomainId, request.getSubjectDomainId())
                    .set(ConceptModelDO::getDescription, request.getDescription())
                    .set(ConceptModelDO::getDesign, design, JsonbStringTypeHandler.MAPPING)
                    .set(ConceptModelDO::getRevision, existing.getRevision() + 1)
                    .set(ConceptModelDO::getUpdateTime, LocalDateTime.now())
                    .set(ConceptModelDO::getUpdater, updater())
                    .eq(ConceptModelDO::getId, request.getId())
                    .eq(ConceptModelDO::getRevision, request.getExpectedRevision())
                    .eq(ConceptModelDO::getStatus, ConceptModelDO.STATUS_DRAFT));
            requireChanged(affected);
        } catch (DuplicateKeyException duplicate) {
            // 唯一索引是并发裁决边界，失败事务内不再发起查询。
            throw exception(CONCEPT_MODEL_DUPLICATE);
        }
    }

    /**
     * 启用或停用概念模型。
     *
     * @param request 请求参数
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(ConceptModelStatusReqVO request) {
        ConceptModelDO existing = requireRevision(request.getId(), request.getExpectedRevision());
        if (Objects.equals(existing.getStatus(), request.getStatus())) {
            return;
        }
        if (ConceptModelDO.STATUS_ACTIVE.equals(request.getStatus())) {
            validateOwnership(existing.getDataDomainId(), existing.getSubjectDomainId());
            ConceptModelDesignVO design = normalizeDesign(
                    JsonUtils.parseObject(existing.getDesign(), ConceptModelDesignVO.class));
            if (design.getEntities().isEmpty()) {
                throw exception(CONCEPT_MODEL_EMPTY);
            }
        }
        requireChanged(mapper.update(null, Wrappers.lambdaUpdate(ConceptModelDO.class)
                .set(ConceptModelDO::getStatus, request.getStatus())
                .set(ConceptModelDO::getRevision, existing.getRevision() + 1)
                .set(ConceptModelDO::getUpdateTime, LocalDateTime.now())
                .set(ConceptModelDO::getUpdater, updater())
                .eq(ConceptModelDO::getId, request.getId())
                .eq(ConceptModelDO::getRevision, request.getExpectedRevision())
                .eq(ConceptModelDO::getStatus, existing.getStatus())));
    }

    /**
     * 删除概念模型草稿。
     *
     * @param id 记录编号
     * @param expectedRevision 预期修订号
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long expectedRevision) {
        requireDraft(requireRevision(id, expectedRevision));
        requireChanged(mapper.deleteDraft(id, expectedRevision, updater()));
    }

    /**
     * 获得概念模型及完整实体关系设计。
     *
     * @param id 记录编号
     * @return 概念模型响应数据
     */
    @Override
    public ConceptModelRespVO get(Long id) {
        ConceptModelDO row = requireExists(id);
        ConceptModelRespVO response = summary(row);
        response.setDesign(JsonUtils.parseObject(row.getDesign(), ConceptModelDesignVO.class));
        fillNames(List.of(response));
        return response;
    }

    /**
     * 获得概念模型分页，不返回设计正文。
     *
     * @param request 请求参数
     * @return 概念模型分页，不返回设计正文结果
     */
    @Override
    public PageResult<ConceptModelRespVO> page(ConceptModelPageReqVO request) {
        PageResult<ConceptModelDO> page = mapper.selectPage(request);
        List<ConceptModelRespVO> rows = page.getList().stream().map(this::summary).toList();
        fillNames(rows);
        return new PageResult<>(rows, page.getTotal());
    }

    /**
     * 汇总概念模型信息。
     *
     * @param row 当前行数据
     * @return 概念模型响应数据
     */
    private ConceptModelRespVO summary(ConceptModelDO row) {
        // JSON 字符串只在详情中显式解码，避免 BeanUtils 尝试转换为嵌套对象。
        ConceptModelRespVO result = new ConceptModelRespVO();
        result.setId(row.getId());
        result.setName(row.getName());
        result.setCode(row.getCode());
        result.setDataDomainId(row.getDataDomainId());
        result.setSubjectDomainId(row.getSubjectDomainId());
        result.setDescription(row.getDescription());
        result.setStatus(row.getStatus());
        result.setRevision(row.getRevision());
        result.setCreateTime(row.getCreateTime());
        result.setUpdateTime(row.getUpdateTime());
        return result;
    }

    /**
     * 补充名称。
     *
     * @param rows 待处理的行数据
     */
    private void fillNames(List<ConceptModelRespVO> rows) {
        Set<Long> domainIds = new HashSet<>();
        Set<Long> subjectIds = new HashSet<>();
        rows.forEach(row -> {
            if (row.getDataDomainId() != null) {
                domainIds.add(row.getDataDomainId());
            }
            if (row.getSubjectDomainId() != null) {
                subjectIds.add(row.getSubjectDomainId());
            }
        });
        Map<Long, String> domains = new HashMap<>();
        Map<Long, String> subjects = new HashMap<>();
        if (!domainIds.isEmpty()) {
            dataDomainMapper.selectBatchIds(domainIds).forEach(row -> domains.put(row.getId(), row.getName()));
        }
        if (!subjectIds.isEmpty()) {
            subjectDomainMapper.selectBatchIds(subjectIds).forEach(row -> subjects.put(row.getId(), row.getName()));
        }
        rows.forEach(row -> {
            row.setDataDomainName(domains.get(row.getDataDomainId()));
            row.setSubjectDomainName(subjects.get(row.getSubjectDomainId()));
        });
    }

    /**
     * 校验归属。
     *
     * @param dataDomainId 数据域编号
     * @param subjectDomainId 主题域编号
     */
    private void validateOwnership(Long dataDomainId, Long subjectDomainId) {
        if (dataDomainId == null) {
            throw exception(MODEL_DATA_DOMAIN_REQUIRED);
        }
        hierarchyResolver.validateModelDataDomain(dataDomainId);
        hierarchyResolver.validateSubjectDomain(subjectDomainId, null, dataDomainId);
    }

    /**
     * 校验存在性。
     *
     * @param id 记录编号
     * @return 概念模型
     */
    private ConceptModelDO requireExists(Long id) {
        ConceptModelDO existing = mapper.selectById(id);
        if (existing == null) {
            throw exception(CONCEPT_MODEL_NOT_EXISTS);
        }
        return existing;
    }

    /**
     * 校验修订号。
     *
     * @param id 记录编号
     * @param revision 修订号
     * @return 概念模型
     */
    private ConceptModelDO requireRevision(Long id, Long revision) {
        ConceptModelDO row = requireExists(id);
        if (!Objects.equals(row.getRevision(), revision)) {
            throw exception(CONCEPT_MODEL_REVISION_CONFLICT);
        }
        return row;
    }

    /**
     * 校验草稿。
     *
     * @param row 当前行数据
     */
    private void requireDraft(ConceptModelDO row) {
        if (!ConceptModelDO.STATUS_DRAFT.equals(row.getStatus())) {
            throw exception(CONCEPT_MODEL_NOT_DRAFT);
        }
    }

    /**
     * 校验变化。
     *
     * @param count 数量
     */
    private void requireChanged(int count) {
        if (count != 1) {
            throw exception(CONCEPT_MODEL_REVISION_CONFLICT);
        }
    }

    /**
     * 获取概念模型的更新器。
     *
     * @return 概念模型的更新器文本
     */
    private String updater() {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        return userId == null ? "" : userId.toString();
    }

    /**
     * 规范化编码。
     *
     * @param code 编码
     * @return 规范化编码文本
     */
    private String normalizeCode(String code) {
        return code.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 规范化设计。
     *
     * @param source 来源数据
     * @return 概念模型设计
     */
    private ConceptModelDesignVO normalizeDesign(ConceptModelDesignVO source) {
        if (source == null || source.getEntities() == null || source.getRelations() == null
                || source.getEntities().size() > 50 || source.getRelations().size() > 100) {
            throw exception(CONCEPT_DESIGN_INVALID, "设计不能为空，最多 50 个实体和 100 条关系");
        }
        // 深复制后规范化，避免修订与请求对象共享可变结构。
        ConceptModelDesignVO design = JsonUtils.parseObject(JsonUtils.toJsonString(source), ConceptModelDesignVO.class);
        Set<String> entityKeys = new HashSet<>();
        Set<String> entityCodes = new HashSet<>();
        for (var entity : design.getEntities()) {
            if (entity != null && entity.getEntityType() == null) {
                entity.setEntityType("OBJECT");
            }
            if (entity != null && !java.util.Set.of("OBJECT", "ACTIVITY").contains(entity.getEntityType())) {
                throw exception(CONCEPT_DESIGN_INVALID, "实体类型必须为业务对象或业务活动");
            }
            if (entity == null || entity.getAttributes() == null || entity.getAttributes().size() > 30) {
                throw exception(CONCEPT_DESIGN_INVALID, "实体不能为空，每个实体最多 30 个属性");
            }
            unique(entityKeys, entity.getKey(), "实体标识");
            entity.setName(trimRequired(entity.getName(), "实体名称"));
            entity.setCode(normalizeCode(trimRequired(entity.getCode(), "实体编码")));
            unique(entityCodes, entity.getCode(), "实体编码");
            Set<String> attributeKeys = new HashSet<>();
            Set<String> attributeNames = new HashSet<>();
            Set<String> attributeCodes = new HashSet<>();
            for (var attribute : entity.getAttributes()) {
                if (attribute == null) {
                    throw exception(CONCEPT_DESIGN_INVALID, "属性不能为空");
                }
                unique(attributeKeys, attribute.getKey(), "属性标识");
                attribute.setName(trimRequired(attribute.getName(), "属性名称"));
                attribute.setCode(normalizeCode(trimRequired(attribute.getCode(), "属性编码")));
                unique(attributeNames, attribute.getName(), "属性名称");
                unique(attributeCodes, attribute.getCode(), "属性编码");
            }
        }
        Set<String> relationKeys = new HashSet<>();
        Set<String> endpoints = new HashSet<>();
        for (var relation : design.getRelations()) {
            if (relation == null) {
                throw exception(CONCEPT_DESIGN_INVALID, "关系不能为空");
            }
            unique(relationKeys, relation.getKey(), "关系标识");
            relation.setName(trimRequired(relation.getName(), "关系名称"));
            if (!entityKeys.contains(relation.getSourceEntityKey()) || !entityKeys.contains(
                    relation.getTargetEntityKey())) {
                throw exception(CONCEPT_DESIGN_INVALID, "关系端点必须引用本模型中的实体");
            }
            if (Objects.equals(relation.getSourceEntityKey(), relation.getTargetEntityKey())) {
                throw exception(CONCEPT_DESIGN_INVALID, "关系不能指向同一实体");
            }
            unique(endpoints, relation.getSourceEntityKey() + ":" + relation.getTargetEntityKey(),
                    "相同方向的实体关系");
        }
        return design;
    }

    /**
     * 去除文本首尾空白，并拒绝必填内容为空。
     *
     * @param value 待处理的值
     * @param field 字段
     * @return 去除文本首尾空白，并拒绝必填内容为空文本
     */
    private String trimRequired(String value, String field) {
        if (value == null || value.isBlank()) {
            throw exception(CONCEPT_DESIGN_INVALID, field + "不能为空");
        }
        return value.trim();
    }

    /**
     * 校验唯一性概念模型。
     *
     * @param values 字段值集合
     * @param value 待处理的值
     * @param field 字段
     */
    private void unique(Set<String> values, String value, String field) {
        if (value == null || value.isBlank() || !values.add(value)) {
            throw exception(CONCEPT_DESIGN_INVALID, field + "重复或为空");
        }
    }
}

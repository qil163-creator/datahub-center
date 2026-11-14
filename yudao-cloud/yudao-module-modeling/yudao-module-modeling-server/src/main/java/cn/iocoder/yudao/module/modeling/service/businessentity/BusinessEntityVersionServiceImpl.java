package cn.iocoder.yudao.module.modeling.service.businessentity;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.modeling.controller.admin.businessentity.vo.BusinessEntityVersionCompareRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.businessentity.vo.BusinessEntityVersionDetailRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.businessentity.vo.BusinessEntityVersionRespVO;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.version.ModelDiffRespVO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity.BusinessEntityDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity.BusinessEntityRelationDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businessentity.BusinessEntityVersionDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.businessentity.BusinessEntityMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.businessentity.BusinessEntityRelationMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.businessentity.BusinessEntityVersionMapper;
import cn.iocoder.yudao.module.system.api.user.AdminUserApi;
import cn.iocoder.yudao.module.system.api.user.dto.AdminUserRespDTO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import tools.jackson.core.type.TypeReference;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;

/**
 * 业务实体版本实现。版本写入只发生在上线事务内，读取与比对不持锁。
 */
@Slf4j
@Service
@Validated
public class BusinessEntityVersionServiceImpl implements BusinessEntityVersionService {

    /** 参与比对的实体属性：键 → 展示名称。 */
    static final Map<String, String> ATTRIBUTES;

    /** 关系类型展示名称。 */
    static final Map<String, String> RELATION_LABELS = Map.of(
            "INHERITANCE", "继承", "ASSOCIATION", "关联", "PREDECESSOR", "前置流程", "PROCESS_EVENT", "流程事件");

    static {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("name", "实体名称");
        attributes.put("code", "实体编码");
        attributes.put("entityType", "实体类型");
        attributes.put("entitySubtype", "实体子类型");
        attributes.put("dataDomainId", "业务板块");
        attributes.put("subjectDomainId", "主题域");
        attributes.put("leaderUserId", "负责人");
        attributes.put("leaderPhone", "联系电话");
        attributes.put("description", "描述");
        ATTRIBUTES = Collections.unmodifiableMap(attributes);
    }

    private static final String CURRENT_LABEL = "当前设计";

    @Resource
    private BusinessEntityVersionMapper versionMapper;

    @Resource
    private BusinessEntityMapper entityMapper;

    @Resource
    private BusinessEntityRelationMapper relationMapper;

    @Resource
    private AdminUserApi adminUserApi;

    @Override
    public BusinessEntityVersionDO recordOnline(BusinessEntityDO row, long revision, List<BusinessEntityRelationDO> own,
            Collection<BusinessEntityDO> boardEntities, Long publishedBy, LocalDateTime publishedTime) {
        Map<Long, BusinessEntityDO> byId = boardEntities.stream()
                .collect(Collectors.toMap(BusinessEntityDO::getId, entity -> entity, (left, right) -> left));
        Map<String, Object> snapshot = BusinessEntityVersionService.snapshot(row, own, byId);
        BusinessEntityVersionDO previous = versionMapper.selectLatest(row.getId());
        Map<String, Object> summary;
        if (previous == null) {
            summary = Map.of("initial", true);
        } else {
            BusinessEntityVersionCompareRespVO diff = diff(snapshot(previous), snapshot);
            summary = new LinkedHashMap<>();
            summary.put("attributeChanged", diff.getAttributeChanges().size());
            summary.put("relationAdded", diff.getRelationChanges().stream()
                    .filter(change -> ModelDiffRespVO.ADDED.equals(change.getChangeType())).count());
            summary.put("relationRemoved", diff.getRelationChanges().stream()
                    .filter(change -> ModelDiffRespVO.REMOVED.equals(change.getChangeType())).count());
        }
        BusinessEntityVersionDO version = BusinessEntityVersionDO.builder()
                .entityId(row.getId())
                .versionNo(previous == null ? 1 : previous.getVersionNo() + 1)
                .entityRevision(revision)
                .snapshot(JsonUtils.toJsonString(snapshot))
                .changeSummary(JsonUtils.toJsonString(summary))
                .publishedBy(publishedBy)
                .publishedTime(publishedTime == null ? LocalDateTime.now() : publishedTime)
                .build();
        versionMapper.insert(version);
        return version;
    }

    @Override
    public List<BusinessEntityVersionRespVO> listVersions(Long entityId) {
        List<BusinessEntityVersionDO> rows = versionMapper.selectListByEntityId(entityId);
        Map<Long, AdminUserRespDTO> users = users(rows.stream().map(BusinessEntityVersionDO::getPublishedBy).toList());
        List<BusinessEntityVersionRespVO> result = new ArrayList<>();
        for (BusinessEntityVersionDO row : rows) {
            BusinessEntityVersionRespVO item = new BusinessEntityVersionRespVO();
            fill(item, row, users);
            result.add(item);
        }
        return result;
    }

    @Override
    public BusinessEntityVersionDetailRespVO getVersion(Long id) {
        BusinessEntityVersionDO row = requireVersion(id);
        BusinessEntityVersionDetailRespVO result = new BusinessEntityVersionDetailRespVO();
        fill(result, row, users(Collections.singletonList(row.getPublishedBy())));
        result.setSnapshot(snapshot(row));
        return result;
    }

    @Override
    public BusinessEntityVersionCompareRespVO compare(Long entityId, Long fromVersionId, Long toVersionId) {
        BusinessEntityVersionDO from = requireVersion(fromVersionId);
        if (!Objects.equals(from.getEntityId(), entityId)) {
            throw exception(MODEL_VERSION_MISMATCH);
        }
        BusinessEntityVersionCompareRespVO result;
        String toLabel;
        if (toVersionId == null) {
            BusinessEntityDO current = entityMapper.selectById(entityId);
            if (current == null) {
                throw exception(BUSINESS_ENTITY_NOT_EXISTS);
            }
            List<BusinessEntityRelationDO> own = relationMapper.selectByBoard(current.getDataDomainId()).stream()
                    .filter(relation -> Objects.equals(relation.getSourceEntityId(), entityId)).toList();
            Map<Long, BusinessEntityDO> byId = entityMapper.selectByBoard(current.getDataDomainId()).stream()
                    .collect(Collectors.toMap(BusinessEntityDO::getId, entity -> entity, (left, right) -> left));
            result = diff(snapshot(from), BusinessEntityVersionService.snapshot(current, own, byId));
            toLabel = CURRENT_LABEL;
        } else {
            BusinessEntityVersionDO to = requireVersion(toVersionId);
            if (!Objects.equals(to.getEntityId(), entityId)) {
                throw exception(MODEL_VERSION_MISMATCH);
            }
            result = diff(snapshot(from), snapshot(to));
            toLabel = "V" + to.getVersionNo();
        }
        result.setFromLabel("V" + from.getVersionNo());
        result.setToLabel(toLabel);
        return result;
    }

    @Override
    public void deleteByEntityId(Long entityId) {
        versionMapper.deleteByEntityId(entityId);
    }

    /** 属性逐项比较；关系按（类型，对端实体）计算新增与删除。 */
    static BusinessEntityVersionCompareRespVO diff(Map<String, Object> before, Map<String, Object> after) {
        BusinessEntityVersionCompareRespVO result = new BusinessEntityVersionCompareRespVO();
        for (Map.Entry<String, String> attribute : ATTRIBUTES.entrySet()) {
            String left = text(before.get(attribute.getKey()));
            String right = text(after.get(attribute.getKey()));
            if (!left.equals(right)) {
                ModelDiffRespVO.AttributeChange change = new ModelDiffRespVO.AttributeChange();
                change.setKey(attribute.getKey());
                change.setLabel(attribute.getValue());
                change.setBefore(left);
                change.setAfter(right);
                result.getAttributeChanges().add(change);
            }
        }
        Map<String, Map<?, ?>> oldRelations = relations(before);
        Map<String, Map<?, ?>> newRelations = relations(after);
        newRelations.forEach((key, relation) -> {
            if (!oldRelations.containsKey(key)) {
                result.getRelationChanges().add(relationChange(relation, ModelDiffRespVO.ADDED));
            }
        });
        oldRelations.forEach((key, relation) -> {
            if (!newRelations.containsKey(key)) {
                result.getRelationChanges().add(relationChange(relation, ModelDiffRespVO.REMOVED));
            }
        });
        return result;
    }

    private static Map<String, Map<?, ?>> relations(Map<String, Object> snapshot) {
        Map<String, Map<?, ?>> result = new LinkedHashMap<>();
        if (snapshot.get("relations") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> relation) {
                    result.putIfAbsent(text(relation.get("relationType")) + "#" + text(relation.get("entityId")),
                            relation);
                }
            }
        }
        return result;
    }

    private static BusinessEntityVersionCompareRespVO.RelationChange relationChange(Map<?, ?> relation,
            String changeType) {
        BusinessEntityVersionCompareRespVO.RelationChange change = new BusinessEntityVersionCompareRespVO.RelationChange();
        String type = text(relation.get("relationType"));
        change.setRelationType(type);
        change.setRelationLabel(RELATION_LABELS.getOrDefault(type, type));
        change.setChangeType(changeType);
        Object entityId = relation.get("entityId");
        change.setEntityId(entityId instanceof Number number ? number.longValue() : null);
        change.setName(text(relation.get("name")));
        change.setCode(text(relation.get("code")));
        return change;
    }

    private BusinessEntityVersionDO requireVersion(Long id) {
        BusinessEntityVersionDO row = id == null ? null : versionMapper.selectById(id);
        if (row == null) {
            throw exception(BUSINESS_ENTITY_VERSION_NOT_EXISTS);
        }
        return row;
    }

    private void fill(BusinessEntityVersionRespVO item, BusinessEntityVersionDO row, Map<Long, AdminUserRespDTO> users) {
        item.setId(row.getId());
        item.setEntityId(row.getEntityId());
        item.setVersionNo(row.getVersionNo());
        item.setVersionLabel("V" + row.getVersionNo());
        item.setEntityRevision(row.getEntityRevision());
        item.setChangeSummary(StrUtil.isBlank(row.getChangeSummary()) ? Map.of()
                : JsonUtils.parseObject(row.getChangeSummary(), new TypeReference<Map<String, Object>>() {
                }));
        item.setPublishedBy(row.getPublishedBy());
        AdminUserRespDTO user = row.getPublishedBy() == null ? null : users.get(row.getPublishedBy());
        item.setPublishedByName(user == null ? null : user.getNickname());
        item.setPublishedTime(row.getPublishedTime());
    }

    private static Map<String, Object> snapshot(BusinessEntityVersionDO row) {
        if (StrUtil.isBlank(row.getSnapshot())) {
            return Map.of();
        }
        return JsonUtils.parseObject(row.getSnapshot(), new TypeReference<Map<String, Object>>() {
        });
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    /** 用户服务不可用时只展示编号，不影响版本读取。 */
    private Map<Long, AdminUserRespDTO> users(Collection<Long> ids) {
        Set<Long> distinct = new HashSet<>();
        ids.forEach(id -> {
            if (id != null) {
                distinct.add(id);
            }
        });
        if (distinct.isEmpty()) {
            return Map.of();
        }
        try {
            return adminUserApi.getUserMap(distinct);
        } catch (RuntimeException unavailable) {
            log.warn("[users][上线操作人名称暂不可用 count={}]", distinct.size());
            return Map.of();
        }
    }
}

package cn.iocoder.yudao.module.governance.service.quality;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaDbDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityCheckObjectDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardMappingDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaDbMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardMappingMapper;
import cn.iocoder.yudao.module.governance.service.standardconstraint.StandardConstraintCompilationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.STANDARD_QUALITY_BINDING_INVALID;

/** 标准领域执行绑定：只展开任务已有检查对象，不改写模板或任务配置。 */
@Service
@RequiredArgsConstructor
public class StandardQualityRuleBindingService {

    private final StandardMappingMapper mappings;

    private final MetaColumnMapper columns;

    private final MetaTableMapper tables;

    private final MetaDbMapper databases;

    private final QualityRuleMapper rules;

    private final StandardElementMapper elements;

    private final StandardQualityRuleMaterializer materializer;

    private final StandardConstraintCompilationService compilationService;

    /** 建议规则只按实际连接解析后的精确关系绑定，不沿用客户端携带的元数据编号。 */
    public Long findSuggestionTableId(Long datasourceId, String schemaName, String tableName) {
        if (datasourceId == null || StrUtil.isBlank(schemaName) || StrUtil.isBlank(tableName)) {
            return null;
        }
        var databaseIds = databases.selectListByDatasourceId(datasourceId).stream()
                .filter(database -> Objects.equals(database.getDatasourceId(), datasourceId)
                        && Objects.equals(database.getSchemaName(), schemaName)
                        && Objects.equals(database.getObjectStatus(), MetaDbDO.OBJECT_STATUS_ACTIVE))
                .map(MetaDbDO::getId).collect(Collectors.toSet());
        if (databaseIds.isEmpty()) {
            return null;
        }
        var matches = tables.selectListByDatasourceIdAndName(datasourceId, tableName).stream()
                .filter(table -> Objects.equals(table.getDatasourceId(), datasourceId)
                        && Objects.equals(table.getTableName(), tableName)
                        && databaseIds.contains(table.getDbId())
                        && Objects.equals(table.getObjectStatus(), MetaDbDO.OBJECT_STATUS_ACTIVE))
                .toList();
        if (matches.size() > 1) {
            throw invalid("建议规则的精确 schema/table 元数据不唯一，请先核验元数据");
        }
        return matches.isEmpty() ? null : matches.getFirst().getId();
    }

    /** 建议查询只读取映射和当前标准，不创建质量模板。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public List<QualityRuleDO> resolve(QualityTaskDO task, List<QualityCheckObjectDO> objects,
            List<QualityRuleDO> configured) {
        return resolveCurrent(task, objects, configured, false);
    }

    /** 任务执行前在独立事务内生成缺失模板，结果随后进入实例的冻结计划。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public List<QualityRuleDO> resolveForExecution(QualityTaskDO task, List<QualityCheckObjectDO> objects,
            List<QualityRuleDO> configured) {
        return resolveCurrent(task, objects, configured, true);
    }

    private List<QualityRuleDO> resolveCurrent(QualityTaskDO task, List<QualityCheckObjectDO> objects,
            List<QualityRuleDO> configured, boolean createTemplates) {
        if (StandardQualityRuleMaterializer.ASSET_TASK_CATEGORY.equals(task.getTaskCategory())) {
            return configured;
        }
        List<QualityRuleDO> result = new ArrayList<>(configured.stream()
                .filter(rule -> rule.getStandardElementId() == null).toList());
        if (createTemplates) {
            // 采集按表、字段顺序加锁，落标命令锁字段后修改映射；先锁定两者再读取映射。
            var tableIds = objects.stream().map(QualityCheckObjectDO::getTableId)
                    .filter(Objects::nonNull).distinct().sorted().toList();
            tableIds.forEach(tables::selectByIdForUpdate);
            tableIds.forEach(columns::selectListByTableIdForUpdate);
        }
        Map<Long, List<StandardMappingDO>> mappingsByTable = new LinkedHashMap<>();
        for (QualityCheckObjectDO object : objects) {
            if (object.getTableId() != null) {
                mappingsByTable.computeIfAbsent(object.getTableId(), mappings::selectConfirmedMetadataListByTableId);
            }
        }
        Map<Integer, StandardElementDO> lockedElements = new LinkedHashMap<>();
        mappingsByTable.values().stream().flatMap(List::stream).map(StandardMappingDO::getElementId)
                .filter(Objects::nonNull).distinct().sorted().forEach(id ->
                        lockedElements.put(id, createTemplates ? elements.selectByIdForUpdate(id) : elements.selectById(id)));
        if (createTemplates) {
            compilationService.lockCodeSets(lockedElements.values().stream().filter(Objects::nonNull)
                    .map(StandardElementDO::getCodeSetId).filter(Objects::nonNull).distinct().sorted().toList());
        }
        for (QualityCheckObjectDO object : objects) {
            if (object.getTableId() == null) {
                continue;
            }
            var confirmed = mappingsByTable.get(object.getTableId());
            if (confirmed.isEmpty()) {
                continue;
            }
            var table = tables.selectById(object.getTableId());
            var database = table == null ? null : databases.selectById(table.getDbId());
            if (table == null || database == null
                    || !Objects.equals(table.getObjectStatus(), MetaDbDO.OBJECT_STATUS_ACTIVE)
                    || !Objects.equals(database.getObjectStatus(), MetaDbDO.OBJECT_STATUS_ACTIVE)
                    || !Objects.equals(object.getDatasourceConnId(), table.getDatasourceId())
                    || !Objects.equals(object.getDatasourceConnId(), database.getDatasourceId())) {
                throw invalid("检查对象与落标元数据的数据源或有效状态不一致：对象 " + object.getId());
            }
            ProductTypeEnum product;
            try {
                product = ProductTypeEnum.valueOf(database.getDbType().toUpperCase(Locale.ROOT));
                var relation = RestrictedSelectGuard.parseTableReference(product, object.getTableName(),
                        database.getSchemaName());
                if (!Objects.equals(relation.getTableName(), table.getTableName())
                        || !Objects.equals(relation.getSchemaName(), database.getSchemaName())) {
                    throw invalid("检查对象物理关系与落标表不一致：对象 " + object.getId());
                }
            } catch (RuntimeException ex) {
                throw invalid("检查对象的标准字段关系无法精确定位：对象 " + object.getId());
            }
            for (var mapping : confirmed) {
                var column = columns.selectById(mapping.getColumnId());
                if (column == null || !Objects.equals(column.getObjectStatus(), MetaDbDO.OBJECT_STATUS_ACTIVE)
                        || !Objects.equals(column.getTableId(), object.getTableId())
                        || !Objects.equals(column.getDatasourceId(), object.getDatasourceConnId())
                        || StrUtil.isBlank(column.getColumnName())) {
                    throw invalid("落标字段已删除、待确认删除或关系失效：映射 " + mapping.getId());
                }
                var element = lockedElements.get(mapping.getElementId());
                if (element == null || !Objects.equals(element.getStatus(), (short) 0)
                        || !Objects.equals(element.getLifecycleStatus(), "PUBLISHED")) {
                    throw invalid("落标数据元未发布或已停用：映射 " + mapping.getId());
                }
                var compilation = compilationService.compile(element);
                if (!compilation.compilable()) {
                    throw invalid("落标数据元当前约束不可用：映射 " + mapping.getId());
                }
                if (createTemplates) {
                    materializer.materialize(element, compilation);
                }
                Map<String, QualityRuleDO> templates = rules.selectListByStandardElementId(
                                mapping.getElementId().longValue()).stream()
                        .filter(rule -> StrUtil.isNotBlank(rule.getConstraintHash()))
                        .collect(Collectors.toMap(QualityRuleDO::getConstraintHash, rule -> rule, (left, right) -> right));
                for (var constraint : compilation.constraints()) {
                    var template = templates.get(constraint.constraintHash());
                    if (template == null) {
                        if (createTemplates) {
                            throw invalid("数据元当前约束缺少质量模板：映射 " + mapping.getId());
                        }
                        template = materializer.preview(element, constraint);
                    }
                    if (!QualityRuleDO.STATUS_ENABLE.equals(template.getStatus())) {
                        continue;
                    }
                    var bound = BeanUtils.toBean(template, QualityRuleDO.class);
                    Map<String, Object> parameters = new LinkedHashMap<>(constraint.config());
                    parameters.put("column", column.getColumnName());
                    bound.setParamSchema(JsonUtils.toJsonString(parameters));
                    bound.setStrategyKey(constraint.strategyKey());
                    bound.setExecutionObjectId(object.getId());
                    bound.setExecutionColumnId(column.getId());
                    bound.setStandardMappingId(mapping.getId());
                    bound.setExecutionSchemaName(database.getSchemaName());
                    bound.setExecutionTableName(table.getTableName());
                    result.add(bound);
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * 构造标准质量规则绑定输入校验失败时的业务异常。
     */
    private static ServiceException invalid(String message) {
        return exception(STANDARD_QUALITY_BINDING_INVALID, message);
    }
}

package cn.iocoder.yudao.module.governance.service.quality;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.module.governance.dal.dataobject.metadata.MetaDbDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityCheckObjectDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityRuleDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.quality.QualityTaskDO;
import cn.iocoder.yudao.module.governance.dal.dataobject.standardelement.StandardElementDO;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaColumnMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaDbMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.metadata.MetaTableMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.quality.QualityRuleMapper;
import cn.iocoder.yudao.module.governance.dal.mysql.standardelement.StandardElementMapper;
import cn.iocoder.yudao.module.governance.service.standardconstraint.StandardConstraintCompilationService;
import cn.iocoder.yudao.module.governance.service.standardconstraint.StandardConstraintCompiler.CompiledConstraint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.governance.enums.ErrorCodeConstants.STANDARD_QUALITY_BINDING_INVALID;

/** 按当前数据元定义解释历史规则引用，不修改已保存的历史规则或执行记录。 */
@Service
@RequiredArgsConstructor
public class CurrentStandardQualityRules {
    private static final Pattern ASSET_COLUMN = Pattern.compile("^ASSET-[0-9]+-col-([0-9]+)$");
    private final StandardElementMapper elements;
    private final StandardConstraintCompilationService compilation;
    private final StandardQualityRuleMaterializer materializer;
    private final MetaTableMapper tables;
    private final MetaDbMapper databases;
    private final MetaColumnMapper columns;
    private final QualityRuleMapper rules;

    /** 只展示当前有效约束，复用已有规则编号供既有选择器保存引用。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public List<QualityRuleDO> selectable(List<QualityRuleDO> candidates) {
        var result = new ArrayList<QualityRuleDO>();
        var groups = new LinkedHashMap<Long, List<QualityRuleDO>>();
        for (var rule : candidates) {
            if (rule.getStandardElementId() == null) {
                if (Objects.equals(rule.getStatus(), QualityRuleDO.STATUS_ENABLE)) result.add(rule);
            } else {
                groups.computeIfAbsent(rule.getStandardElementId(), ignored -> new ArrayList<>()).add(rule);
            }
        }
        for (var entry : groups.entrySet()) {
            var element = element(entry.getKey(), false);
            if (!active(element)) continue;
            var current = compilation.compile(element);
            if (!current.compilable()) continue;
            for (var constraint : current.constraints()) {
                var source = choose(entry.getValue(), constraint);
                if (source != null && Objects.equals(source.getStatus(), QualityRuleDO.STATUS_ENABLE)) {
                    result.add(currentView(source, element, constraint));
                }
            }
        }
        return List.copyOf(result);
    }

    /** 资产任务保留明确选择的规则类型和字段，并在每次运行时使用当前标准约束。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, propagation = Propagation.REQUIRES_NEW)
    public List<QualityRuleDO> assetExecution(QualityTaskDO task, List<QualityCheckObjectDO> objects,
            List<QualityRuleDO> configured) {
        if (configured.stream().noneMatch(rule -> rule.getStandardElementId() != null)) return configured;
        var matcher = ASSET_COLUMN.matcher(StrUtil.nullToEmpty(task.getTaskCode()));
        if (!matcher.matches()) throw invalid("标准质量规则需要明确指定资产字段");
        Long columnId;
        try { columnId = Long.valueOf(matcher.group(1)); }
        catch (NumberFormatException ex) { throw invalid("资产字段编号无效"); }
        var tableIds = objects.stream().map(QualityCheckObjectDO::getTableId).filter(Objects::nonNull)
                .distinct().sorted().toList();
        tableIds.forEach(tables::selectByIdForUpdate);
        tableIds.forEach(columns::selectListByTableIdForUpdate);
        var column = columns.selectById(columnId);
        if (column == null || !Objects.equals(column.getObjectStatus(), MetaDbDO.OBJECT_STATUS_ACTIVE)
                || StrUtil.isBlank(column.getColumnName())) throw invalid("资产字段不存在或已失效");
        var object = objects.stream().filter(item -> Objects.equals(item.getTableId(), column.getTableId())
                && Objects.equals(item.getDatasourceConnId(), column.getDatasourceId())).findFirst()
                .orElseThrow(() -> invalid("资产字段与检查对象不匹配"));
        var table = tables.selectById(object.getTableId());
        var database = table == null ? null : databases.selectById(table.getDbId());
        if (table == null || database == null
                || !Objects.equals(table.getObjectStatus(), MetaDbDO.OBJECT_STATUS_ACTIVE)
                || !Objects.equals(database.getObjectStatus(), MetaDbDO.OBJECT_STATUS_ACTIVE)
                || !Objects.equals(table.getDatasourceId(), object.getDatasourceConnId())
                || !Objects.equals(database.getDatasourceId(), object.getDatasourceConnId())) {
            throw invalid("资产检查对象的数据源或元数据状态不一致");
        }
        try {
            var product = ProductTypeEnum.valueOf(database.getDbType().toUpperCase(Locale.ROOT));
            var relation = RestrictedSelectGuard.parseTableReference(product, object.getTableName(),
                    database.getSchemaName());
            if (!Objects.equals(relation.getTableName(), table.getTableName())
                    || !Objects.equals(relation.getSchemaName(), database.getSchemaName())) {
                throw invalid("资产检查对象与元数据表不一致");
            }
        } catch (IllegalArgumentException | UnsupportedOperationException ex) {
            throw invalid("资产检查对象的物理关系无法定位");
        }
        var locked = new LinkedHashMap<Long, StandardElementDO>();
        configured.stream().map(QualityRuleDO::getStandardElementId).filter(Objects::nonNull).distinct().sorted()
                .forEach(id -> locked.put(id, element(id, true)));
        compilation.lockCodeSets(locked.values().stream().filter(Objects::nonNull).map(StandardElementDO::getCodeSetId)
                .filter(Objects::nonNull).distinct().sorted().toList());
        var result = new ArrayList<QualityRuleDO>();
        for (var rule : configured) {
            if (rule.getStandardElementId() == null) { result.add(rule); continue; }
            var element = locked.get(rule.getStandardElementId());
            if (!active(element)) throw invalid("所选数据元未发布或已停用");
            var current = compilation.compile(element);
            if (!current.compilable()) throw invalid("所选数据元当前约束不可用");
            var constraint = current.constraints().stream().filter(item -> sameType(rule, item)).findFirst().orElse(null);
            if (constraint == null) continue;
            var preferred = choose(rules.selectListByStandardElementId(rule.getStandardElementId()), constraint);
            if (preferred != null && !Objects.equals(preferred.getStatus(), QualityRuleDO.STATUS_ENABLE)) continue;
            var bound = currentView(preferred == null ? rule : preferred, element, constraint);
            var parameters = new LinkedHashMap<String, Object>(constraint.config());
            parameters.put("column", column.getColumnName());
            bound.setParamSchema(JsonUtils.toJsonString(parameters));
            bound.setExecutionObjectId(object.getId());
            bound.setExecutionColumnId(column.getId());
            result.add(bound);
        }
        return List.copyOf(result);
    }

    private StandardElementDO element(Long id, boolean lock) {
        if (id == null || id <= 0 || id > Integer.MAX_VALUE) return null;
        return lock ? elements.selectByIdForUpdate(id.intValue()) : elements.selectById(id.intValue());
    }

    private static boolean active(StandardElementDO element) {
        return element != null && Objects.equals(element.getStatus(), (short) 0)
                && Objects.equals(element.getLifecycleStatus(), "PUBLISHED");
    }

    private static boolean sameType(QualityRuleDO rule, CompiledConstraint constraint) {
        return Objects.equals(rule.getRuleType(), StandardQualityRuleMaterializer.ruleType(constraint.constraintType()))
                && Objects.equals(rule.getStrategyKey(), constraint.strategyKey());
    }

    private static QualityRuleDO choose(List<QualityRuleDO> candidates, CompiledConstraint constraint) {
        return candidates.stream().filter(rule -> sameType(rule, constraint)).max(Comparator
                .comparing((QualityRuleDO rule) -> Objects.equals(rule.getConstraintHash(), constraint.constraintHash()))
                .thenComparing(QualityRuleDO::getId, Comparator.nullsFirst(Comparator.naturalOrder()))).orElse(null);
    }

    private QualityRuleDO currentView(QualityRuleDO source, StandardElementDO element, CompiledConstraint constraint) {
        var view = BeanUtils.toBean(source, QualityRuleDO.class);
        var current = materializer.preview(element, constraint);
        view.setRuleName(current.getRuleName());
        view.setDescription(current.getDescription());
        view.setConstraintHash(current.getConstraintHash());
        view.setParamSchema(current.getParamSchema());
        view.setStrategyKey(current.getStrategyKey());
        return view;
    }

    private static RuntimeException invalid(String message) {
        return exception(STANDARD_QUALITY_BINDING_INVALID, message);
    }
}

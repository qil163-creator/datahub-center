package cn.iocoder.yudao.module.modeling.service.indicator;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.dbswitch.core.model.CatalogRelationIdentity;
import cn.iocoder.yudao.framework.dbswitch.core.service.AggregateQuerySql;
import cn.iocoder.yudao.module.modeling.controller.admin.indicator.vo.*;
import cn.iocoder.yudao.module.modeling.controller.admin.model.vo.ModelFieldItem;
import cn.iocoder.yudao.module.modeling.dal.dataobject.businesscondition.BusinessConditionDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.indicator.IndicatorModelDO;
import cn.iocoder.yudao.module.modeling.dal.dataobject.model.ModelMaterializedDO;
import cn.iocoder.yudao.module.modeling.dal.mysql.businesscondition.BusinessConditionMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.indicator.IndicatorModelMapper;
import cn.iocoder.yudao.module.modeling.dal.mysql.model.ModelMaterializedMapper;
import cn.iocoder.yudao.module.modeling.service.model.ModelingDatabaseResolver;
import jakarta.annotation.Resource;
import cn.iocoder.yudao.module.governance.api.security.dto.SafetyDynamicMaskReqDTO;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.modeling.enums.ErrorCodeConstants.*;

/**
 * 指标查询服务：原子/派生指标在来源物化表上受限聚合，复合指标在服务端按维度键用 BigDecimal 计算。
 * <p>
 * 数据范围：指标定义平台内可见，查询受独立权限 {@code modeling:indicator-model:data-query} 控制；
 * 返回前经 {@link IndicatorQueryMaskSupport} 调用治理动态脱敏（失败关闭）。本服务不开启平台事务，外部查询只读执行。
 */
@Service
@Validated
public class IndicatorQueryServiceImpl implements IndicatorQueryService {

    /** 默认返回行数。 */
    public static final int DEFAULT_LIMIT = 200;

    /** 参与计算的基础（原子/派生）指标上限。 */
    public static final int MAX_LEAVES = 20;

    /** 单个派生指标的业务限定数量上限。 */
    public static final int MAX_CONDITIONS = 5;

    /** 时间范围跨度上限（天）。 */
    public static final long MAX_RANGE_DAYS = 3660;

    private static final Pattern CODE = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,99}");

    private static final Pattern CONDITION_SEPARATOR = Pattern.compile("[,，;；\\s]+");

    private static final Set<String> INTEGER_TYPES = Set.of("TINYINT", "SMALLINT", "INT", "INTEGER", "BIGINT",
            "INT2", "INT4", "INT8", "SERIAL", "BIGSERIAL");

    private static final Set<String> DECIMAL_TYPES = Set.of("DECIMAL", "NUMERIC", "NUMBER", "REAL", "FLOAT",
            "FLOAT4", "FLOAT8", "DOUBLE", "DOUBLE PRECISION", "MONEY");

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private IndicatorModelMapper mapper;

    @Resource
    private ModelMaterializedMapper materializedMapper;

    @Resource
    private BusinessConditionMapper conditionMapper;

    @Resource
    private IndicatorDependencyResolver dependencies;

    @Resource
    private ModelingDatabaseResolver databases;

    @Resource
    private IndicatorQueryExecutor executor;

    @Resource
    private IndicatorQueryMaskSupport maskSupport;

    @Override
    public IndicatorQueryRespVO query(IndicatorQueryReqVO request) {
        Resolution resolution = resolve(request.getIndicatorIds());
        AggregateQuerySql.TimeGrain grain = effectiveGrain(request.getTimeGrain(), resolution.leaves.values());
        LocalDate start = null;
        LocalDate end = null;
        if (request.getTimeRange() != null) {
            start = date(request.getTimeRange().getStart(), "开始日期");
            end = date(request.getTimeRange().getEnd(), "结束日期");
            if (end.isBefore(start)) {
                throw exception(INDICATOR_QUERY_INVALID, "结束日期不能早于开始日期");
            }
            if (ChronoUnit.DAYS.between(start, end) > MAX_RANGE_DAYS) {
                throw exception(INDICATOR_QUERY_INVALID, "时间范围不能超过 " + MAX_RANGE_DAYS + " 天");
            }
        }
        List<String> dimensions = request.getDimensions() == null ? List.of() : request.getDimensions();
        if (new LinkedHashSet<>(dimensions).size() != dimensions.size()) {
            throw exception(INDICATOR_QUERY_INVALID, "维度字段不能重复");
        }
        List<IndicatorQueryReqVO.Filter> filters = request.getFilters() == null ? List.of() : request.getFilters();
        int limit = request.getLimit() == null ? DEFAULT_LIMIT : request.getLimit();
        boolean timed = grain != null || start != null;
        requireNumericForComposites(resolution);

        Map<Long, LeafPlan> plans = new LinkedHashMap<>();
        for (IndicatorModelDO leaf : resolution.leaves.values()) {
            plans.put(leaf.getId(), plan(leaf, resolution.bases, dimensions, filters, timed, true));
        }

        // 同一数据源的基础指标复用同一只读连接。
        Map<Long, List<LeafPlan>> byDatasource = new LinkedHashMap<>();
        plans.values().forEach(plan -> byDatasource.computeIfAbsent(plan.materialized.getDatasourceId(),
                ignored -> new ArrayList<>()).add(plan));
        Map<Long, Map<List<String>, Object>> values = new HashMap<>();
        boolean truncated = false;
        for (Map.Entry<Long, List<LeafPlan>> group : byDatasource.entrySet()) {
            ModelingDatabaseResolver.ConnectionInfo connection = databases.resolve(group.getKey());
            if (!AggregateQuerySql.supports(connection.getProductType())) {
                throw exception(INDICATOR_QUERY_UNSUPPORTED, connection.getName());
            }
            for (LeafPlan plan : group.getValue()) {
                plan.connection = connection;
                plan.schema = schemaOf(plan);
            }
            // Doris 只能按执行连接上观测到的原生关系身份定位物化表，执行前后均复验同一身份。
            List<CatalogRelationIdentity> relations = AggregateQuerySql.requiresCatalogRelation(
                    connection.getProductType())
                    ? executor.resolveRelations(connection, group.getValue().stream()
                    .map(plan -> new String[]{plan.schema, plan.materialized.getTableName()}).toList())
                    : List.of();
            List<AggregateQuerySql.Plan> rendered = new ArrayList<>();
            for (int index = 0; index < group.getValue().size(); index++) {
                rendered.add(render(group.getValue().get(index), grain, start, end, limit + 1,
                        relations.isEmpty() ? null : relations.get(index)));
            }
            List<List<Map<String, Object>>> results = executor.execute(connection, rendered, relations, limit + 1);
            for (int index = 0; index < rendered.size(); index++) {
                List<Map<String, Object>> rows = results.get(index);
                if (rows.size() > limit) {
                    truncated = true;
                    rows = rows.subList(0, limit);
                }
                Map<List<String>, Object> keyed = new LinkedHashMap<>();
                AggregateQuerySql.Plan plan = rendered.get(index);
                for (Map<String, Object> row : rows) {
                    keyed.put(key(plan, row), value(row.get(plan.valueAlias())));
                }
                values.put(group.getValue().get(index).leaf.getId(), keyed);
            }
        }

        // 以全部基础指标的维度键并集作为行，排序后截断。
        TreeSet<List<String>> keys = new TreeSet<>(IndicatorQueryServiceImpl::compareKeys);
        values.values().forEach(map -> keys.addAll(map.keySet()));
        List<List<String>> ordered = new ArrayList<>(keys);
        if (ordered.size() > limit) {
            truncated = true;
            ordered = ordered.subList(0, limit);
        }

        Evaluator evaluator = new Evaluator(resolution, values);
        List<IndicatorQueryRespVO.Column> columns = columns(dimensions, plans.values().iterator().next(), grain,
                resolution.requested);
        List<Map<String, String>> rows = new ArrayList<>();
        for (List<String> key : ordered) {
            Map<String, String> row = new LinkedHashMap<>();
            int offset = grain == null ? 0 : 1;
            for (int index = 0; index < dimensions.size(); index++) {
                row.put(dimensionKey(index), key.get(offset + index));
            }
            if (grain != null) {
                row.put(TIME_KEY, key.getFirst());
            }
            for (IndicatorModelDO indicator : resolution.requested) {
                row.put(indicatorKey(indicator), evaluator.text(indicator, key));
            }
            rows.add(row);
        }
        IndicatorQueryMaskSupport.Result masked = mask(resolution, plans.values(), plans, columns, rows);

        IndicatorQueryRespVO response = new IndicatorQueryRespVO();
        response.setColumns(columns);
        response.setRows(masked.rows());
        response.setMaskedColumns(masked.maskedColumns());
        response.setTruncated(truncated);
        response.setTimeGrain(grain == null ? null : grain.name());
        response.setTimeStart(start == null ? null : start.toString());
        response.setTimeEnd(end == null ? null : end.toString());
        response.setCalibers(resolution.requested.stream().map(indicator -> caliber(indicator, resolution, plans))
                .toList());
        return response;
    }

    @Override
    public IndicatorQueryMetaRespVO meta(IndicatorQueryMetaReqVO request) {
        Resolution resolution = resolve(request.getIndicatorIds());
        LinkedHashMap<String, ModelFieldItem> common = null;
        boolean timeSupported = true;
        Set<String> periods = new LinkedHashSet<>();
        for (IndicatorModelDO leaf : resolution.leaves.values()) {
            LeafPlan plan = plan(leaf, resolution.bases, List.of(), List.of(), false, false);
            if (plan.timeColumn == null) {
                timeSupported = false;
            }
            if (IndicatorModelDO.DERIVED.equals(leaf.getIndicatorType())) {
                periods.add(leaf.getTimePeriod());
            }
            if (common == null) {
                common = new LinkedHashMap<>(plan.fields);
            } else {
                common.keySet().retainAll(plan.fields.keySet());
            }
        }
        IndicatorQueryMetaRespVO response = new IndicatorQueryMetaRespVO();
        response.setDimensions(common == null ? List.of() : common.values().stream().map(field -> {
            IndicatorSourceFieldRespVO item = new IndicatorSourceFieldRespVO();
            item.setFieldUid(field.getFieldUid());
            item.setFieldName(field.getFieldName());
            item.setCnName(field.getCnName());
            item.setDataType(field.getDataType());
            return item;
        }).toList());
        response.setTimeSupported(timeSupported);
        response.setDerivedPeriods(List.copyOf(periods));
        return response;
    }

    // ========== 指标解析 ==========

    /**
     * 加载请求指标并展开复合指标依赖。
     */
    private Resolution resolve(List<Long> requestedIds) {
        List<Long> ids = requestedIds.stream().distinct().toList();
        Map<Long, IndicatorModelDO> loaded = new HashMap<>();
        mapper.selectBatchIds(ids).forEach(row -> loaded.put(row.getId(), row));
        Resolution resolution = new Resolution();
        for (Long id : ids) {
            IndicatorModelDO indicator = loaded.get(id);
            if (indicator == null) {
                throw exception(INDICATOR_NOT_EXISTS);
            }
            if (!IndicatorModelDO.ACTIVE.equals(indicator.getStatus())) {
                throw exception(INDICATOR_QUERY_INVALID, "指标【" + indicator.getName() + "】未启用");
            }
            resolution.requested.add(indicator);
            IndicatorDependencyResolver.Node tree = dependencies.resolve(indicator);
            resolution.trees.put(indicator.getId(), tree);
            tree.collectLeaves(resolution.leaves);
            tree.collectComposites(resolution.composites);
            collectBases(tree, resolution.bases);
        }
        if (resolution.leaves.size() > MAX_LEAVES) {
            throw exception(INDICATOR_QUERY_INVALID, "参与计算的原子/派生指标不能超过 " + MAX_LEAVES + " 个");
        }
        resolution.leaves.values().forEach(leaf -> resolution.leafByCode.put(
                leaf.getCode().toLowerCase(Locale.ROOT), leaf));
        return resolution;
    }

    /**
     * 收集派生指标的原子来源（依赖树已校验其启用状态）。
     */
    private void collectBases(IndicatorDependencyResolver.Node node, Map<Long, IndicatorModelDO> bases) {
        if (IndicatorModelDO.DERIVED.equals(node.indicator().getIndicatorType())) {
            node.children().forEach(child -> bases.put(node.indicator().getId(), child.indicator()));
            return;
        }
        node.children().forEach(child -> collectBases(child, bases));
    }

    /**
     * 复合指标只能计算数值结果。
     */
    private void requireNumericForComposites(Resolution resolution) {
        for (IndicatorModelDO composite : resolution.composites.values()) {
            for (String code : IndicatorDependencyResolver.parse(composite.getFormula()).references()) {
                IndicatorModelDO leaf = resolution.leafByCode.get(code.toLowerCase(Locale.ROOT));
                if (leaf != null && !numeric(leaf.getResultDataType())) {
                    throw exception(INDICATOR_QUERY_INVALID, "复合指标【" + composite.getName() + "】引用的指标【"
                            + leaf.getName() + "】结果不是数值类型");
                }
            }
        }
    }

    /**
     * 生效时间粒度：请求粒度优先；未指定时采用派生指标统一的统计周期。
     */
    private AggregateQuerySql.TimeGrain effectiveGrain(String requested, Collection<IndicatorModelDO> leaves) {
        Set<String> periods = new LinkedHashSet<>();
        leaves.stream().filter(leaf -> IndicatorModelDO.DERIVED.equals(leaf.getIndicatorType()))
                .forEach(leaf -> periods.add(leaf.getTimePeriod()));
        String grain = requested;
        if (grain == null && !periods.isEmpty()) {
            if (periods.size() > 1) {
                throw exception(INDICATOR_QUERY_INVALID, "所选派生指标的统计周期不一致（" + String.join("、", periods)
                        + "），请指定查询粒度");
            }
            grain = periods.iterator().next();
        }
        if (grain == null) {
            return null;
        }
        for (IndicatorModelDO leaf : leaves) {
            if (IndicatorModelDO.DERIVED.equals(leaf.getIndicatorType()) && !grain.equals(leaf.getTimePeriod())) {
                throw exception(INDICATOR_QUERY_INVALID, "派生指标【" + leaf.getName() + "】的统计周期为 "
                        + leaf.getTimePeriod() + "，与查询粒度 " + grain + " 不一致");
            }
        }
        try {
            return AggregateQuerySql.TimeGrain.valueOf(grain);
        } catch (IllegalArgumentException invalid) {
            throw exception(INDICATOR_QUERY_INVALID, "时间粒度不合法");
        }
    }

    /**
     * 解析基础指标的物理来源、字段映射、过滤与业务限定。
     */
    private LeafPlan plan(IndicatorModelDO leaf, Map<Long, IndicatorModelDO> bases, List<String> dimensions,
            List<IndicatorQueryReqVO.Filter> filters, boolean timed, boolean withConditions) {
        LeafPlan plan = new LeafPlan();
        plan.leaf = leaf;
        IndicatorModelDO atomic = IndicatorModelDO.ATOMIC.equals(leaf.getIndicatorType()) ? leaf
                : bases.get(leaf.getId());
        if (atomic == null || !IndicatorModelDO.ATOMIC.equals(atomic.getIndicatorType())
                || !IndicatorModelDO.ACTIVE.equals(atomic.getStatus())) {
            throw exception(INDICATOR_BASE_INVALID);
        }
        plan.atomic = atomic;
        try {
            plan.aggregation = AggregateQuerySql.Aggregation.valueOf(atomic.getAggregation());
        } catch (RuntimeException invalid) {
            throw exception(INDICATOR_QUERY_INVALID, "指标【" + atomic.getName() + "】的聚合方式不合法");
        }
        ModelMaterializedDO materialized = atomic.getSourceModelId() == null ? null
                : materializedMapper.selectLatestSuccess(atomic.getSourceModelId());
        if (materialized == null || materialized.getDatasourceId() == null
                || StrUtil.isBlank(materialized.getTableName())) {
            throw exception(INDICATOR_QUERY_SOURCE_UNAVAILABLE, leaf.getName());
        }
        plan.materialized = materialized;
        List<ModelFieldItem> fields = StrUtil.isBlank(materialized.getFieldsJson()) ? List.of()
                : JsonUtils.parseArray(materialized.getFieldsJson(), ModelFieldItem.class);
        for (ModelFieldItem field : fields) {
            if (StrUtil.isNotBlank(field.getFieldUid()) && StrUtil.isNotBlank(field.getFieldName())) {
                plan.fields.putIfAbsent(field.getFieldUid(), field);
                if (!plan.physical.contains(field.getFieldName())) {
                    plan.physical.add(field.getFieldName());
                }
            }
        }
        ModelFieldItem value = plan.fields.get(atomic.getValueFieldUid());
        if (value == null) {
            throw exception(INDICATOR_QUERY_INVALID, "指标【" + atomic.getName()
                    + "】的度量字段不在已物化的物理表中，请重新发布来源模型");
        }
        plan.valueColumn = value.getFieldName();
        ModelFieldItem time = StrUtil.isBlank(atomic.getTimeFieldUid()) ? null
                : plan.fields.get(atomic.getTimeFieldUid());
        if (time != null) {
            plan.timeColumn = time.getFieldName();
            plan.timeIsDate = "DATE".equals(baseType(time.getDataType()));
        }
        if (timed && plan.timeColumn == null) {
            throw exception(INDICATOR_QUERY_INVALID, "指标【" + leaf.getName()
                    + "】未配置统计时间字段或该字段未物化，不能按时间查询");
        }
        for (String uid : dimensions) {
            ModelFieldItem field = plan.fields.get(uid);
            if (field == null) {
                throw exception(INDICATOR_QUERY_INVALID, "维度字段【" + uid + "】不属于指标【" + leaf.getName()
                        + "】的来源物理表");
            }
            plan.dimensions.add(field);
        }
        for (IndicatorQueryReqVO.Filter filter : filters) {
            ModelFieldItem field = plan.fields.get(filter.getFieldUid());
            if (field == null) {
                throw exception(INDICATOR_QUERY_INVALID, "过滤字段【" + filter.getFieldUid() + "】不属于指标【"
                        + leaf.getName() + "】的来源物理表");
            }
            plan.filters.add(new AggregateQuerySql.Filter(field.getFieldName(), typed(field, filter.getValue())));
        }
        if (withConditions && IndicatorModelDO.DERIVED.equals(leaf.getIndicatorType())) {
            plan.conditions.addAll(conditions(leaf, atomic));
        }
        return plan;
    }

    /**
     * 派生指标的业务限定字段保存业务限定英文编码（多个以逗号分隔），解析为同来源模型的启用业务限定。
     * 无法解析时拒绝查询，不会忽略限定返回放宽口径的结果。
     */
    private List<BusinessConditionDO> conditions(IndicatorModelDO derived, IndicatorModelDO atomic) {
        String text = StrUtil.trim(derived.getBusinessCondition());
        if (StrUtil.isBlank(text)) {
            return List.of();
        }
        List<String> codes = Arrays.stream(CONDITION_SEPARATOR.split(text)).filter(StrUtil::isNotBlank).distinct()
                .toList();
        if (codes.size() > MAX_CONDITIONS || codes.stream().anyMatch(code -> !CODE.matcher(code).matches())) {
            throw exception(INDICATOR_QUERY_CONDITION_INVALID, derived.getName(),
                    "业务限定需填写已启用业务限定的英文编码（多个以逗号分隔，最多 " + MAX_CONDITIONS + " 个）");
        }
        List<BusinessConditionDO> result = new ArrayList<>();
        for (String code : codes) {
            BusinessConditionDO condition = conditionMapper.selectByCodeIgnoreCase(code);
            if (condition == null || !BusinessConditionDO.ACTIVE.equals(condition.getStatus())
                    || !Objects.equals(condition.getSourceModelId(), atomic.getSourceModelId())) {
                throw exception(INDICATOR_QUERY_CONDITION_INVALID, derived.getName(),
                        "业务限定【" + code + "】不存在、未启用或不属于原子指标的来源模型");
            }
            result.add(condition);
        }
        return result;
    }

    /**
     * 物化记录的 schema 优先，缺省回退到数据源配置；两者都缺失时来源不可用。
     */
    private String schemaOf(LeafPlan plan) {
        String schema = StrUtil.blankToDefault(plan.materialized.getSchemaName(), plan.connection.getSchema());
        if (StrUtil.isBlank(schema)) {
            throw exception(INDICATOR_QUERY_SOURCE_UNAVAILABLE, plan.leaf.getName());
        }
        return schema;
    }

    /**
     * 渲染受限聚合 SQL；用户书写的限定片段只经组件解析重建。
     */
    private AggregateQuerySql.Plan render(LeafPlan plan, AggregateQuerySql.TimeGrain grain, LocalDate start,
            LocalDate end, int limit, CatalogRelationIdentity relation) {
        String schema = schemaOf(plan);
        plan.schema = schema;
        AggregateQuerySql.TimeWindow time = null;
        if (plan.timeColumn != null && (grain != null || start != null)) {
            Object from = start == null ? null : plan.timeIsDate ? start : start.atStartOfDay();
            Object to = end == null ? null : plan.timeIsDate ? end.plusDays(1) : end.plusDays(1).atStartOfDay();
            time = new AggregateQuerySql.TimeWindow(plan.timeColumn, grain, from, to);
        }
        List<String> rowFilters = plan.conditions.stream().map(BusinessConditionDO::getExpression).toList();
        AggregateQuerySql.Request request = new AggregateQuerySql.Request(plan.connection.getProductType(), schema,
                plan.materialized.getTableName(), plan.physical,
                plan.dimensions.stream().map(ModelFieldItem::getFieldName).toList(), plan.valueColumn,
                plan.aggregation, time, plan.filters, rowFilters, limit, relation);
        try {
            return AggregateQuerySql.render(request);
        } catch (UnsupportedOperationException unsupported) {
            throw exception(INDICATOR_QUERY_UNSUPPORTED, plan.connection.getName());
        } catch (IllegalArgumentException invalid) {
            if (!rowFilters.isEmpty()) {
                try {
                    AggregateQuerySql.render(new AggregateQuerySql.Request(request.product(), schema,
                            request.tableName(), request.physicalColumns(), request.dimensionColumns(),
                            request.valueColumn(), request.aggregation(), time, request.filters(), List.of(), limit,
                            relation));
                } catch (IllegalArgumentException other) {
                    throw exception(INDICATOR_QUERY_INVALID, other.getMessage());
                }
                throw exception(INDICATOR_QUERY_CONDITION_INVALID, plan.leaf.getName(), invalid.getMessage());
            }
            throw exception(INDICATOR_QUERY_INVALID, invalid.getMessage());
        }
    }

    // ========== 结果组装 ==========

    static final String TIME_KEY = "time_bucket";

    static String dimensionKey(int index) {
        return "dim_" + index;
    }

    static String indicatorKey(IndicatorModelDO indicator) {
        return "ind_" + indicator.getId();
    }

    /**
     * 结果列。
     */
    private List<IndicatorQueryRespVO.Column> columns(List<String> dimensions, LeafPlan first,
            AggregateQuerySql.TimeGrain grain, List<IndicatorModelDO> requested) {
        List<IndicatorQueryRespVO.Column> columns = new ArrayList<>();
        for (int index = 0; index < dimensions.size(); index++) {
            ModelFieldItem field = first.dimensions.get(index);
            IndicatorQueryRespVO.Column column = new IndicatorQueryRespVO.Column();
            column.setKey(dimensionKey(index));
            column.setTitle(StrUtil.blankToDefault(field.getCnName(), field.getFieldName()));
            column.setKind(IndicatorQueryRespVO.KIND_DIMENSION);
            column.setFieldUid(field.getFieldUid());
            columns.add(column);
        }
        if (grain != null) {
            IndicatorQueryRespVO.Column column = new IndicatorQueryRespVO.Column();
            column.setKey(TIME_KEY);
            column.setTitle("统计时间（" + grain.name() + "）");
            column.setKind(IndicatorQueryRespVO.KIND_TIME);
            columns.add(column);
        }
        for (IndicatorModelDO indicator : requested) {
            IndicatorQueryRespVO.Column column = new IndicatorQueryRespVO.Column();
            column.setKey(indicatorKey(indicator));
            column.setTitle(indicator.getName());
            column.setKind(IndicatorQueryRespVO.KIND_INDICATOR);
            column.setIndicatorId(indicator.getId());
            column.setIndicatorCode(indicator.getCode());
            column.setUnit(indicator.getUnit());
            columns.add(column);
        }
        return columns;
    }

    /**
     * 返回前动态脱敏（失败关闭）：每个输出列提交取自实际使用的物化记录的物理来源。
     * <ul>
     *     <li>维度列：各基础指标来源表中的物理维度列；</li>
     *     <li>时间桶：时间列截断值仍会暴露原始日期的一部分，按物理时间列提交；</li>
     *     <li>指标值：COUNT/COUNT_DISTINCT/SUM/AVG 不暴露原值，来源为空；MIN/MAX 直接暴露原值，来源为被聚合的物理列；
     *     复合指标提交其依赖树中全部 MIN/MAX 基础指标的物理列（公式可能原样透出该值），其余为空。</li>
     * </ul>
     * 调用时目标库连接已全部关闭，且不在任何事务内。
     */
    private IndicatorQueryMaskSupport.Result mask(Resolution resolution, Collection<LeafPlan> plans,
            Map<Long, LeafPlan> planByLeaf, List<IndicatorQueryRespVO.Column> columns,
            List<Map<String, String>> rows) {
        List<SafetyDynamicMaskReqDTO.Column> specs = new ArrayList<>();
        for (IndicatorQueryRespVO.Column column : columns) {
            Set<SafetyDynamicMaskReqDTO.Source> sources = new LinkedHashSet<>();
            boolean traceable = true;
            if (IndicatorQueryRespVO.KIND_DIMENSION.equals(column.getKind())) {
                int index = Integer.parseInt(column.getKey().substring("dim_".length()));
                for (LeafPlan plan : plans) {
                    ModelFieldItem field = index < plan.dimensions.size() ? plan.dimensions.get(index) : null;
                    if (field == null || !Objects.equals(field.getFieldUid(), column.getFieldUid())) {
                        traceable = false;
                    } else {
                        sources.add(source(plan, field.getFieldName()));
                    }
                }
            } else if (IndicatorQueryRespVO.KIND_TIME.equals(column.getKind())) {
                for (LeafPlan plan : plans) {
                    if (plan.timeColumn == null) {
                        traceable = false;
                    } else {
                        sources.add(source(plan, plan.timeColumn));
                    }
                }
            } else {
                Map<Long, IndicatorModelDO> leaves = new LinkedHashMap<>();
                resolution.trees.get(column.getIndicatorId()).collectLeaves(leaves);
                for (Long leafId : leaves.keySet()) {
                    LeafPlan plan = planByLeaf.get(leafId);
                    if (plan == null) {
                        traceable = false;
                    } else if (plan.aggregation == AggregateQuerySql.Aggregation.MIN
                            || plan.aggregation == AggregateQuerySql.Aggregation.MAX) {
                        sources.add(source(plan, plan.valueColumn));
                    }
                }
            }
            specs.add(traceable ? IndicatorQueryMaskSupport.column(column.getKey(), sources)
                    : IndicatorQueryMaskSupport.untraceable(column.getKey(), candidateTables(plans)));
        }
        String resource = String.join(",", plans.stream().map(plan -> StrUtil.isBlank(plan.schema)
                ? plan.materialized.getTableName() : plan.schema + "." + plan.materialized.getTableName())
                .distinct().toList());
        return maskSupport.apply(resource, specs, rows);
    }

    private static SafetyDynamicMaskReqDTO.Source source(LeafPlan plan, String column) {
        return SafetyDynamicMaskReqDTO.Source.of(plan.materialized.getDatasourceId(),
                StrUtil.emptyToNull(plan.schema), plan.materialized.getTableName(), column);
    }

    private static List<SafetyDynamicMaskReqDTO.Source> candidateTables(Collection<LeafPlan> plans) {
        return plans.stream().map(plan -> source(plan, null)).distinct().limit(20).toList();
    }

    /**
     * 生效口径。
     */
    private IndicatorQueryRespVO.Caliber caliber(IndicatorModelDO indicator, Resolution resolution,
            Map<Long, LeafPlan> plans) {
        IndicatorQueryRespVO.Caliber caliber = new IndicatorQueryRespVO.Caliber();
        caliber.setIndicatorId(indicator.getId());
        caliber.setCode(indicator.getCode());
        caliber.setName(indicator.getName());
        caliber.setIndicatorType(indicator.getIndicatorType());
        caliber.setUnit(indicator.getUnit());
        caliber.setFormula(indicator.getFormula());
        Map<Long, IndicatorModelDO> leaves = new LinkedHashMap<>();
        resolution.trees.get(indicator.getId()).collectLeaves(leaves);
        caliber.setLeaves(leaves.keySet().stream().map(plans::get).filter(Objects::nonNull).map(plan -> {
            IndicatorQueryRespVO.LeafCaliber leaf = new IndicatorQueryRespVO.LeafCaliber();
            leaf.setIndicatorId(plan.leaf.getId());
            leaf.setCode(plan.leaf.getCode());
            leaf.setName(plan.leaf.getName());
            leaf.setIndicatorType(plan.leaf.getIndicatorType());
            leaf.setAggregation(plan.aggregation.name());
            leaf.setValueField(plan.valueColumn);
            leaf.setTimeField(plan.timeColumn);
            leaf.setTimePeriod(plan.leaf.getTimePeriod());
            leaf.setConditions(plan.conditions.stream().map(condition -> {
                IndicatorQueryRespVO.Condition item = new IndicatorQueryRespVO.Condition();
                item.setId(condition.getId());
                item.setCode(condition.getCode());
                item.setName(condition.getName());
                item.setExpression(condition.getExpression());
                return item;
            }).toList());
            IndicatorQueryRespVO.Source source = new IndicatorQueryRespVO.Source();
            source.setModelId(plan.atomic.getSourceModelId());
            source.setMaterializedId(plan.materialized.getId());
            source.setDatasourceId(plan.materialized.getDatasourceId());
            source.setDatasourceName(StrUtil.blankToDefault(plan.materialized.getDatasourceName(),
                    plan.connection == null ? null : plan.connection.getName()));
            source.setSchemaName(plan.schema);
            source.setTableName(plan.materialized.getTableName());
            leaf.setSource(source);
            return leaf;
        }).toList());
        return caliber;
    }

    /**
     * 结果行的维度键：时间桶（按时间分组时）在前，维度值依次在后。
     */
    static List<String> key(AggregateQuerySql.Plan plan, Map<String, Object> row) {
        List<String> key = new ArrayList<>();
        if (plan.timeAlias() != null) {
            key.add(text(row.get(plan.timeAlias())));
        }
        plan.dimensionAliases().forEach(alias -> key.add(text(row.get(alias))));
        return Collections.unmodifiableList(key);
    }

    /**
     * 键按位置依次比较：时间桶优先，其次按维度；两侧都是数值时按大小，空值最后。
     */
    static int compareKeys(List<String> left, List<String> right) {
        int size = Math.min(left.size(), right.size());
        for (int index = 0; index < size; index++) {
            int result = compareText(left.get(index), right.get(index));
            if (result != 0) {
                return result;
            }
        }
        return 0;
    }

    /**
     * 文本比较：两侧均为数值时按大小比较。
     */
    private static int compareText(String left, String right) {
        if (Objects.equals(left, right)) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        BigDecimal a = decimal(left);
        BigDecimal b = decimal(right);
        if (a != null && b != null && a.compareTo(b) != 0) {
            return a.compareTo(b);
        }
        return left.compareTo(right);
    }

    private static BigDecimal decimal(String text) {
        if (text.length() > 60 || !text.matches("-?[0-9]+(\\.[0-9]+)?")) {
            return null;
        }
        return new BigDecimal(text);
    }

    /**
     * 维度与时间值的规范文本。
     */
    static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return DATE_TIME.format(timestamp.toLocalDateTime());
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof LocalDateTime dateTime) {
            return DATE_TIME.format(dateTime);
        }
        if (value instanceof OffsetDateTime dateTime) {
            return DATE_TIME.format(dateTime.toLocalDateTime());
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof byte[]) {
            return "[binary]";
        }
        return value.toString();
    }

    /**
     * 聚合值：数值统一为 BigDecimal，非数值（MIN/MAX 文本或日期）保留文本。
     */
    static Object value(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof BigDecimal decimal) {
            return decimal;
        }
        if (raw instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        if (raw instanceof Long || raw instanceof Integer || raw instanceof Short || raw instanceof Byte) {
            return BigDecimal.valueOf(((Number) raw).longValue());
        }
        if (raw instanceof Number number) {
            double primitive = number.doubleValue();
            return Double.isFinite(primitive) ? new BigDecimal(number.toString()) : null;
        }
        return text(raw);
    }

    /**
     * 过滤值按来源字段类型转换后参数绑定。
     */
    private static Object typed(ModelFieldItem field, String value) {
        String type = baseType(field.getDataType());
        try {
            if (INTEGER_TYPES.contains(type)) {
                BigInteger integer = new BigInteger(value.trim());
                return integer.bitLength() < 64 ? (Object) integer.longValueExact() : integer;
            }
            if (DECIMAL_TYPES.contains(type)) {
                return new BigDecimal(value.trim());
            }
            if ("DATE".equals(type)) {
                return LocalDate.parse(value.trim());
            }
            if (type.startsWith("TIMESTAMP") || "DATETIME".equals(type)) {
                String text = value.trim();
                return text.length() == 10 ? LocalDate.parse(text).atStartOfDay()
                        : LocalDateTime.parse(text.replace(' ', 'T'));
            }
            if ("BOOLEAN".equals(type) || "BOOL".equals(type)) {
                if (!"true".equalsIgnoreCase(value.trim()) && !"false".equalsIgnoreCase(value.trim())) {
                    throw new IllegalArgumentException("boolean");
                }
                return Boolean.parseBoolean(value.trim());
            }
        } catch (NumberFormatException | ArithmeticException | DateTimeParseException invalid) {
            throw exception(INDICATOR_QUERY_INVALID, "过滤字段【" + field.getFieldName() + "】的值与字段类型不匹配");
        } catch (IllegalArgumentException invalid) {
            throw exception(INDICATOR_QUERY_INVALID, "过滤字段【" + field.getFieldName() + "】的值与字段类型不匹配");
        }
        return value;
    }

    private static String baseType(String type) {
        return StrUtil.blankToDefault(type, "").toUpperCase(Locale.ROOT).split("\\(", 2)[0].trim();
    }

    private static boolean numeric(String type) {
        String base = baseType(type);
        return INTEGER_TYPES.contains(base) || DECIMAL_TYPES.contains(base);
    }

    private static LocalDate date(String text, String label) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException | NullPointerException invalid) {
            throw exception(INDICATOR_QUERY_INVALID, label + "格式应为 yyyy-MM-dd");
        }
    }

    // ========== 内部结构 ==========

    /** 请求指标与依赖展开结果。 */
    private static final class Resolution {

        private final List<IndicatorModelDO> requested = new ArrayList<>();

        private final Map<Long, IndicatorModelDO> leaves = new LinkedHashMap<>();

        private final Map<String, IndicatorModelDO> leafByCode = new HashMap<>();

        private final Map<String, IndicatorModelDO> composites = new HashMap<>();

        private final Map<Long, IndicatorModelDO> bases = new HashMap<>();

        private final Map<Long, IndicatorDependencyResolver.Node> trees = new HashMap<>();
    }

    /** 单个基础指标的执行计划。 */
    private static final class LeafPlan {

        private IndicatorModelDO leaf;

        private IndicatorModelDO atomic;

        private ModelMaterializedDO materialized;

        private ModelingDatabaseResolver.ConnectionInfo connection;

        private String schema;

        private AggregateQuerySql.Aggregation aggregation;

        private final LinkedHashMap<String, ModelFieldItem> fields = new LinkedHashMap<>();

        private final List<String> physical = new ArrayList<>();

        private String valueColumn;

        private String timeColumn;

        private boolean timeIsDate;

        private final List<ModelFieldItem> dimensions = new ArrayList<>();

        private final List<AggregateQuerySql.Filter> filters = new ArrayList<>();

        private final List<BusinessConditionDO> conditions = new ArrayList<>();
    }

    /** 按维度键求值；复合指标递归计算并缓存。 */
    private static final class Evaluator {

        private final Resolution resolution;

        private final Map<Long, Map<List<String>, Object>> values;

        private final Map<String, IndicatorFormula> formulas = new HashMap<>();

        private final Map<List<String>, Map<String, Optional<BigDecimal>>> memo = new HashMap<>();

        private Evaluator(Resolution resolution, Map<Long, Map<List<String>, Object>> values) {
            this.resolution = resolution;
            this.values = values;
        }

        /** 请求指标在该键下的十进制字符串。 */
        private String text(IndicatorModelDO indicator, List<String> key) {
            if (IndicatorModelDO.COMPOSITE.equals(indicator.getIndicatorType())) {
                return IndicatorFormula.format(composite(indicator.getCode().toLowerCase(Locale.ROOT), key));
            }
            Object value = values.getOrDefault(indicator.getId(), Map.of()).get(key);
            return value instanceof BigDecimal decimal ? decimal.toPlainString() : (String) value;
        }

        private BigDecimal composite(String code, List<String> key) {
            Map<String, Optional<BigDecimal>> cache = memo.computeIfAbsent(key, ignored -> new HashMap<>());
            Optional<BigDecimal> cached = cache.get(code);
            if (cached != null) {
                return cached.orElse(null);
            }
            IndicatorModelDO indicator = resolution.composites.get(code);
            IndicatorFormula formula = formulas.computeIfAbsent(code,
                    ignored -> IndicatorDependencyResolver.parse(indicator.getFormula()));
            BigDecimal result = formula.evaluate(reference -> reference(reference.toLowerCase(Locale.ROOT), key));
            cache.put(code, Optional.ofNullable(result));
            return result;
        }

        private BigDecimal reference(String code, List<String> key) {
            if (resolution.composites.containsKey(code)) {
                return composite(code, key);
            }
            IndicatorModelDO leaf = resolution.leafByCode.get(code);
            Object value = leaf == null ? null : values.getOrDefault(leaf.getId(), Map.of()).get(key);
            return value instanceof BigDecimal decimal ? decimal : null;
        }
    }
}

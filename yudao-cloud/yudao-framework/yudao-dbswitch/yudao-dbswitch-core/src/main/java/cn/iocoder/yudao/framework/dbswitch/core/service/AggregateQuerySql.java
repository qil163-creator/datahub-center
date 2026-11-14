package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.CatalogRelationIdentity;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 单表受限聚合查询的 SQL 组装器：只渲染 SQL 与有序绑定参数，不打开连接、不执行 SQL。
 *
 * <p>生成的语句固定为三层：
 * <ol>
 *     <li>受限源：调用 {@link RestrictedSelectGuard#restrictProjectionWithRowFilters} 生成只含所需物理列的
 *     单表 SELECT；调用方行条件（如业务限定）经同一受限语法解析、按元数据列重建后合并，原始片段不会被拼接；</li>
 *     <li>投影层：把物理列映射为本组件生成的固定别名（{@code dh_dim_N}、{@code dh_time_bucket}、
 *     {@code dh_value}），时间范围与等值过滤只使用 JDBC 占位符；</li>
 *     <li>聚合层：只引用生成的、加引号的别名，产出 GROUP BY / ORDER BY / LIMIT。</li>
 * </ol>
 *
 * <p>产品支持沿用 {@link RestrictedSelectGuard#supportsExactBinding}，另外 Doris 必须携带调用方在执行连接上
 * 观测到的原生 catalog 关系身份（见 {@link #requiresCatalogRelation}）；时间粒度函数按 PostgreSQL 族
 * （PostgreSQL、openGauss）、MySQL 族（MySQL、MariaDB）与 Doris 分别渲染，其余产品抛出
 * {@link UnsupportedOperationException}。本组件不决定对象授权、数据范围或脱敏，均由调用方负责。
 * 线程安全：无状态静态方法。
 */
public final class AggregateQuerySql {

    /** 维度数量上限。 */
    public static final int MAX_DIMENSIONS = 10;

    /** 等值过滤数量上限。 */
    public static final int MAX_FILTERS = 20;

    /** 行数上限。 */
    public static final int MAX_LIMIT = 100_000;

    /** 投影层别名：维度前缀。 */
    public static final String DIMENSION_ALIAS_PREFIX = "dh_dim_";

    /** 投影层别名：时间粒度桶。 */
    public static final String TIME_BUCKET_ALIAS = "dh_time_bucket";

    /** 投影层与聚合层别名：度量值。 */
    public static final String VALUE_ALIAS = "dh_value";

    private static final String SOURCE_ALIAS = "dh_src";

    private static final String PROJECTION_ALIAS = "dh_agg";

    /** 聚合函数；只允许下列固定函数。 */
    public enum Aggregation {
        SUM, COUNT, COUNT_DISTINCT, AVG, MIN, MAX
    }

    /** 时间粒度；WEEK 以周一为一周开始。 */
    public enum TimeGrain {
        DAY, WEEK, MONTH, QUARTER, YEAR
    }

    /**
     * 等值过滤：列取自服务端元数据，值必须已由调用方转换为列类型，渲染为占位符绑定。
     *
     * @param column 物理列名
     * @param value  已类型化的非空值
     */
    public record Filter(String column, Object value) {

        /** 避免在诊断日志中意外输出过滤值。 */
        @Override
        public String toString() {
            return "AggregateQuerySql.Filter[column=" + column + "]";
        }
    }

    /**
     * 时间列及可选的粒度和左闭右开范围。
     *
     * @param column         物理时间列
     * @param grain          时间粒度，可为空表示不按时间分组
     * @param startInclusive 起始（含），可为空
     * @param endExclusive   结束（不含），可为空
     */
    public record TimeWindow(String column, TimeGrain grain, Object startInclusive, Object endExclusive) {

        /** 避免在诊断日志中意外输出范围值。 */
        @Override
        public String toString() {
            return "AggregateQuerySql.TimeWindow[column=" + column + ", grain=" + grain + "]";
        }
    }

    /**
     * 聚合请求；全部列名都必须来自服务端可信元数据。
     *
     * @param product          目标数据库产品
     * @param schemaName       物理 schema
     * @param tableName        物理表
     * @param physicalColumns  该表物理列（保留原始大小写，不允许重复）
     * @param dimensionColumns 分组维度列，按输出顺序
     * @param valueColumn      度量列
     * @param aggregation      聚合函数
     * @param time             时间窗口，可为空
     * @param filters          等值过滤，可为空
     * @param rowFilters       调用方可信来源的行条件片段（如业务限定），经受限语法校验后合并，可为空
     * @param limit            最大行数（1..{@value #MAX_LIMIT}）
     * @param relation         原生 catalog 关系身份；{@link #requiresCatalogRelation} 为真的产品必填，
     *                         须由调用方在执行连接上观测并在执行后复验，其余产品为空
     */
    public record Request(ProductTypeEnum product, String schemaName, String tableName,
                          List<String> physicalColumns, List<String> dimensionColumns, String valueColumn,
                          Aggregation aggregation, TimeWindow time, List<Filter> filters, List<String> rowFilters,
                          int limit, CatalogRelationIdentity relation) {

        /**
         * 不需要原生 catalog 身份的产品沿用的构造方式。
         */
        public Request(ProductTypeEnum product, String schemaName, String tableName,
                       List<String> physicalColumns, List<String> dimensionColumns, String valueColumn,
                       Aggregation aggregation, TimeWindow time, List<Filter> filters, List<String> rowFilters,
                       int limit) {
            this(product, schemaName, tableName, physicalColumns, dimensionColumns, valueColumn, aggregation, time,
                    filters, rowFilters, limit, null);
        }
    }

    /**
     * 渲染结果。
     *
     * @param sql              可直接预编译执行的 SQL
     * @param parameters       与占位符顺序一致的绑定值
     * @param dimensionAliases 输出维度列别名，与请求维度顺序一致
     * @param timeAlias        时间桶输出别名；未按时间分组时为空
     * @param valueAlias       度量输出别名
     */
    public record Plan(String sql, List<Object> parameters, List<String> dimensionAliases, String timeAlias,
                       String valueAlias) {

        /**
         * 复制不可变集合。
         */
        public Plan {
            Objects.requireNonNull(sql, "sql");
            parameters = List.copyOf(parameters);
            dimensionAliases = List.copyOf(dimensionAliases);
        }

        /** 避免在诊断日志中意外输出绑定值。 */
        @Override
        public String toString() {
            return "AggregateQuerySql.Plan[parameters=" + parameters.size() + "]";
        }
    }

    /**
     * 初始化 {@code AggregateQuerySql}。
     */
    private AggregateQuerySql() {
    }

    /**
     * 当前产品是否支持受限聚合查询；与精确绑定、行条件合并的产品矩阵一致。
     */
    public static boolean supports(ProductTypeEnum product) {
        return product != null && (RestrictedSelectGuard.supportsExactBinding(product)
                || requiresCatalogRelation(product));
    }

    /**
     * 当前产品是否只能通过原生 catalog 关系身份定位目标表。Doris 不开放名称精确绑定，
     * 调用方须在执行连接上观测 {@link CatalogRelationIdentity} 并在取得结果后复验。
     */
    public static boolean requiresCatalogRelation(ProductTypeEnum product) {
        return product == ProductTypeEnum.DORIS;
    }

    /**
     * 渲染受限聚合 SQL。
     *
     * @throws IllegalArgumentException      列不在元数据中、行条件不满足受限语法或参数不合法
     * @throws UnsupportedOperationException 数据库产品未验证
     */
    public static Plan render(Request request) {
        Objects.requireNonNull(request, "request");
        ProductTypeEnum product = request.product();
        if (!supports(product)) {
            throw new UnsupportedOperationException("数据库产品尚未验证受限聚合查询：" + product);
        }
        if (request.aggregation() == null) {
            throw new IllegalArgumentException("聚合函数不能为空");
        }
        if (request.limit() < 1 || request.limit() > MAX_LIMIT) {
            throw new IllegalArgumentException("行数上限必须在 1 到 " + MAX_LIMIT + " 之间");
        }
        List<String> physical = request.physicalColumns();
        if (physical == null || physical.isEmpty()) {
            throw new IllegalArgumentException("缺少目标表的物理列元数据");
        }
        List<String> dimensions = request.dimensionColumns() == null ? List.of() : request.dimensionColumns();
        List<Filter> filters = request.filters() == null ? List.of() : request.filters();
        List<String> rowFilters = request.rowFilters() == null ? List.of() : request.rowFilters();
        if (dimensions.size() > MAX_DIMENSIONS) {
            throw new IllegalArgumentException("维度数量超过上限 " + MAX_DIMENSIONS);
        }
        if (new LinkedHashSet<>(dimensions).size() != dimensions.size()) {
            throw new IllegalArgumentException("维度列不能重复");
        }
        if (filters.size() > MAX_FILTERS) {
            throw new IllegalArgumentException("过滤条件数量超过上限 " + MAX_FILTERS);
        }

        // 所需物理列必须逐一来自元数据；不做大小写折叠，避免把请求拼写当作列名。
        Set<String> needed = new LinkedHashSet<>();
        for (String dimension : dimensions) {
            needed.add(requirePhysical(physical, dimension, "维度列"));
        }
        needed.add(requirePhysical(physical, request.valueColumn(), "度量列"));
        TimeWindow time = request.time();
        if (time != null) {
            needed.add(requirePhysical(physical, time.column(), "时间列"));
            requireTemporal(time.startInclusive());
            requireTemporal(time.endExclusive());
        }
        for (Filter filter : filters) {
            if (filter == null) {
                throw new IllegalArgumentException("过滤条件不能为空");
            }
            needed.add(requirePhysical(physical, filter.column(), "过滤列"));
            requireBindable(filter.value());
        }

        // 第一层：受限单表源；行条件由组件解析、校验并按元数据列重建。
        String probe = "SELECT " + String.join(", ", needed.stream()
                .map(column -> RestrictedSelectGuard.quotePhysicalIdentifier(product, column)).toList())
                + " FROM " + RestrictedSelectGuard.qualifiedTableName(product, request.schemaName(),
                request.tableName());
        RestrictedSelectProjection source;
        if (requiresCatalogRelation(product)) {
            CatalogRelationIdentity relation = request.relation();
            if (relation == null || relation.product() != product
                    || !relation.schemaName().equals(request.schemaName())
                    || !relation.tableName().equals(request.tableName())) {
                throw new IllegalArgumentException("聚合查询必须绑定与目标表一致的原生 catalog 关系");
            }
            source = RestrictedSelectGuard.restrictCatalogProjectionWithRowFilters(probe, request.schemaName(),
                    relation, physical, needed, rowFilters);
        } else {
            source = RestrictedSelectGuard.restrictProjectionWithRowFilters(product, probe,
                    request.schemaName(), request.tableName(), physical, needed, rowFilters);
        }

        // 第二层：物理列 → 生成别名，范围与过滤全部参数绑定。
        List<Object> parameters = new ArrayList<>();
        List<String> projection = new ArrayList<>();
        List<String> dimensionAliases = new ArrayList<>();
        for (int index = 0; index < dimensions.size(); index++) {
            String alias = DIMENSION_ALIAS_PREFIX + index;
            projection.add(sourceColumn(product, dimensions.get(index)) + " AS " + quote(product, alias));
            dimensionAliases.add(alias);
        }
        String timeAlias = null;
        if (time != null && time.grain() != null) {
            timeAlias = TIME_BUCKET_ALIAS;
            projection.add(bucket(product, time.grain(), sourceColumn(product, time.column())) + " AS "
                    + quote(product, timeAlias));
        }
        projection.add(sourceColumn(product, request.valueColumn()) + " AS " + quote(product, VALUE_ALIAS));
        List<String> conditions = new ArrayList<>();
        if (time != null && time.startInclusive() != null) {
            conditions.add(sourceColumn(product, time.column()) + " >= ?");
            parameters.add(time.startInclusive());
        }
        if (time != null && time.endExclusive() != null) {
            conditions.add(sourceColumn(product, time.column()) + " < ?");
            parameters.add(time.endExclusive());
        }
        for (Filter filter : filters) {
            conditions.add(sourceColumn(product, filter.column()) + " = ?");
            parameters.add(filter.value());
        }
        StringBuilder middle = new StringBuilder("SELECT ").append(String.join(", ", projection))
                .append(" FROM (").append(source.sql()).append(") ").append(quote(product, SOURCE_ALIAS));
        if (!conditions.isEmpty()) {
            middle.append(" WHERE ").append(String.join(" AND ", conditions));
        }

        // 第三层：只引用组件生成的别名。
        List<String> groups = new ArrayList<>();
        dimensionAliases.forEach(alias -> groups.add(quote(product, alias)));
        if (timeAlias != null) {
            groups.add(quote(product, timeAlias));
        }
        List<String> outer = new ArrayList<>(groups);
        outer.add(aggregate(request.aggregation(), quote(product, VALUE_ALIAS)) + " AS "
                + quote(product, VALUE_ALIAS));
        StringBuilder sql = new StringBuilder("SELECT ").append(String.join(", ", outer))
                .append(" FROM (").append(middle).append(") ").append(quote(product, PROJECTION_ALIAS));
        if (!groups.isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", groups));
            List<String> orders = new ArrayList<>();
            if (timeAlias != null) {
                orders.add(quote(product, timeAlias) + " ASC");
            }
            dimensionAliases.forEach(alias -> orders.add(quote(product, alias) + " ASC"));
            sql.append(" ORDER BY ").append(String.join(", ", orders));
        }
        sql.append(" LIMIT ").append(request.limit());
        return new Plan(sql.toString(), parameters, dimensionAliases, timeAlias, VALUE_ALIAS);
    }

    /**
     * 校验列来自物理元数据，返回元数据中的精确拼写。
     */
    private static String requirePhysical(List<String> physical, String column, String label) {
        if (column == null || column.isBlank() || !physical.contains(column)) {
            throw new IllegalArgumentException(label + "不在目标表物理元数据中");
        }
        return column;
    }

    /**
     * 时间范围只接受 JDBC 4.2 时间类型。
     */
    private static void requireTemporal(Object value) {
        if (value != null && !(value instanceof LocalDate || value instanceof LocalDateTime
                || value instanceof OffsetDateTime || value instanceof java.sql.Date
                || value instanceof java.sql.Timestamp)) {
            throw new IllegalArgumentException("时间范围必须是日期或时间类型");
        }
    }

    /**
     * 过滤值只接受可安全绑定的标量类型。
     */
    private static void requireBindable(Object value) {
        boolean valid = value instanceof String || value instanceof Boolean || value instanceof BigDecimal
                || value instanceof BigInteger || value instanceof Long || value instanceof Integer
                || value instanceof Short || value instanceof Byte
                || (value instanceof Double number && Double.isFinite(number))
                || (value instanceof Float number && Float.isFinite(number));
        if (!valid) {
            try {
                requireTemporal(value);
                valid = value != null;
            } catch (IllegalArgumentException ignored) {
                valid = false;
            }
        }
        if (!valid) {
            throw new IllegalArgumentException("过滤值必须是已转换为列类型的非空标量");
        }
    }

    /**
     * 受限源中的物理列引用。
     */
    private static String sourceColumn(ProductTypeEnum product, String column) {
        return quote(product, SOURCE_ALIAS) + "." + RestrictedSelectGuard.quotePhysicalIdentifier(product, column);
    }

    /**
     * 组件生成的固定别名；仍按方言引用。
     */
    private static String quote(ProductTypeEnum product, String alias) {
        return RestrictedSelectGuard.quotePhysicalIdentifier(product, alias);
    }

    /**
     * 固定聚合函数。
     */
    private static String aggregate(Aggregation aggregation, String column) {
        return switch (aggregation) {
            case SUM -> "SUM(" + column + ")";
            case COUNT -> "COUNT(" + column + ")";
            case COUNT_DISTINCT -> "COUNT(DISTINCT " + column + ")";
            case AVG -> "AVG(" + column + ")";
            case MIN -> "MIN(" + column + ")";
            case MAX -> "MAX(" + column + ")";
        };
    }

    /**
     * 时间粒度桶，输出该粒度起始日期（DATE）。
     */
    private static String bucket(ProductTypeEnum product, TimeGrain grain, String column) {
        if (product == ProductTypeEnum.POSTGRESQL || product == ProductTypeEnum.OPENGAUSS) {
            String unit = switch (grain) {
                case DAY -> "day";
                case WEEK -> "week";
                case MONTH -> "month";
                case QUARTER -> "quarter";
                case YEAR -> "year";
            };
            return "CAST(DATE_TRUNC('" + unit + "', CAST(" + column + " AS TIMESTAMP)) AS DATE)";
        }
        if (product == ProductTypeEnum.DORIS) {
            // Doris 的 DATE_TRUNC 参数顺序为 (datetime, unit)，week 同样以周一为一周开始。
            String unit = switch (grain) {
                case DAY -> "day";
                case WEEK -> "week";
                case MONTH -> "month";
                case QUARTER -> "quarter";
                case YEAR -> "year";
            };
            return "CAST(DATE_TRUNC(CAST(" + column + " AS DATETIME), '" + unit + "') AS DATE)";
        }
        if (product == ProductTypeEnum.MYSQL || product == ProductTypeEnum.MARIADB) {
            return switch (grain) {
                case DAY -> "DATE(" + column + ")";
                case WEEK -> "DATE_SUB(DATE(" + column + "), INTERVAL WEEKDAY(" + column + ") DAY)";
                case MONTH -> "DATE_SUB(DATE(" + column + "), INTERVAL (DAYOFMONTH(" + column + ") - 1) DAY)";
                case QUARTER -> "(MAKEDATE(YEAR(" + column + "), 1) + INTERVAL (QUARTER(" + column
                        + ") - 1) QUARTER)";
                case YEAR -> "MAKEDATE(YEAR(" + column + "), 1)";
            };
        }
        throw new UnsupportedOperationException("数据库产品尚未验证时间粒度函数：" + product);
    }
}

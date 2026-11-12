package cn.iocoder.yudao.framework.quality.core.profile;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.quality.core.audit.AuditSqlDialect;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 字段级数据探查 SQL 生成器：按产品生成总行数、单遍聚合与 Top-N 三类只读查询。
 *
 * <ul>
 *     <li>标识符一律经 DB Switch {@link RestrictedSelectGuard#quotePhysicalIdentifier} 按产品引用，非法名称直接拒绝；</li>
 *     <li>抽样行数上限复用 DB Switch {@code limitGeneratedSelect}（PostgreSQL/MySQL/达梦/Hive 为 LIMIT，Oracle 为 FETCH FIRST）；</li>
 *     <li>文本长度复用 {@link AuditSqlDialect#charLength}（MySQL 系按字符数 CHAR_LENGTH）；</li>
 *     <li>生成的 SQL 不含任何字符串字面量，调用方传入的值不会进入 SQL 文本。</li>
 * </ul>
 *
 * <p>支持矩阵：PostgreSQL 族（PostgreSQL、Kingbase、Greenplum、HighGo、openGauss、UXDB）、
 * MySQL 族（MySQL、MariaDB、TiDB、Doris、StarRocks）、Oracle、达梦、Hive；其余产品抛出
 * {@link UnsupportedOperationException}。抽样为不带排序的前 N 行，统计结果是近似值。</p>
 *
 * <p>本类无状态、线程安全；不持有连接，执行、超时、行数上限与关闭由调用方负责。</p>
 *
 * @author Li
 */
public final class ProfileSqlGenerator {

    /** 取值类文本（最小/最大值、Top 值）的最大保留字符数 */
    public static final int MAX_VALUE_LENGTH = 256;

    private static final Set<ProductTypeEnum> SUPPORTED = EnumSet.of(
            ProductTypeEnum.POSTGRESQL, ProductTypeEnum.KINGBASE, ProductTypeEnum.GREENPLUM,
            ProductTypeEnum.HIGHGO, ProductTypeEnum.OPENGAUSS, ProductTypeEnum.UXDB,
            ProductTypeEnum.MYSQL, ProductTypeEnum.MARIADB, ProductTypeEnum.TIDB,
            ProductTypeEnum.DORIS, ProductTypeEnum.STARROCKS,
            ProductTypeEnum.ORACLE, ProductTypeEnum.DM, ProductTypeEnum.HIVE);

    private static final String SAMPLE_ALIAS = "p_sample";

    private final DbSwitchProductRegistry products;

    /**
     * 以执行应用上下文的产品注册表初始化生成器。
     */
    public ProfileSqlGenerator(DbSwitchProductRegistry products) {
        this.products = Objects.requireNonNull(products, "products");
    }

    /**
     * 判断产品是否在探查支持矩阵内。
     */
    public static boolean supports(ProductTypeEnum product) {
        return product != null && SUPPORTED.contains(product);
    }

    /**
     * 全表总行数：{@code SELECT COUNT(*) FROM 表}。可能较慢，调用方必须设置查询超时。
     */
    public String buildTotalCountSql(ProfileRequest request) {
        requireSupported(request.product());
        return "SELECT COUNT(*) AS p_rows FROM " + qualifiedTable(request);
    }

    /**
     * 单遍聚合：对抽样子查询统计行数及各列指标。
     */
    public ProfileAggregatePlan buildAggregate(ProfileRequest request) {
        requireSupported(request.product());
        ProductTypeEnum product = request.product();
        List<String> expressions = new ArrayList<>();
        expressions.add("COUNT(*) AS p_rows");
        for (int i = 0; i < request.columns().size(); i++) {
            ProfileColumn column = request.columns().get(i);
            String quoted = quote(product, column.name());
            for (ProfileMetric metric : column.kind().metrics()) {
                expressions.add(metricExpression(product, metric, quoted) + " AS c" + i + "_" + alias(metric));
            }
        }
        String sql = "SELECT " + String.join(", ", expressions)
                + " FROM (" + sample(request, request.columns()) + ") " + SAMPLE_ALIAS;
        return new ProfileAggregatePlan(sql, request.columns());
    }

    /**
     * 为所有支持 Top-N 的列各生成一条分布查询。
     */
    public List<ProfileTopValuesQuery> buildTopValues(ProfileRequest request) {
        requireSupported(request.product());
        return request.columns().stream().filter(column -> column.kind().supportsTopValues())
                .map(column -> buildTopValues(request, column)).collect(Collectors.toList());
    }

    /**
     * 单列 Top-N：{@code SELECT col, COUNT(*) FROM (抽样) WHERE col IS NOT NULL GROUP BY col ORDER BY 次数 DESC, 取值}。
     */
    public ProfileTopValuesQuery buildTopValues(ProfileRequest request, ProfileColumn column) {
        requireSupported(request.product());
        if (!request.columns().contains(column)) {
            throw new IllegalArgumentException("Column is not part of the profile request");
        }
        if (!column.kind().supportsTopValues()) {
            throw new IllegalArgumentException("Column kind does not support top values: " + column.kind());
        }
        String quoted = quote(request.product(), column.name());
        String sql = "SELECT " + quoted + " AS p_value, COUNT(*) AS p_count FROM ("
                + sample(request, List.of(column)) + ") " + SAMPLE_ALIAS
                + " WHERE " + quoted + " IS NOT NULL GROUP BY " + quoted + " ORDER BY p_count DESC, p_value ASC";
        return new ProfileTopValuesQuery(column, limit(request.product(), sql, request.topN()), request.topN());
    }

    /**
     * 按字符（码点）截断取值文本。
     */
    public static String truncate(String value) {
        if (value == null || value.length() <= MAX_VALUE_LENGTH
                || value.codePointCount(0, value.length()) <= MAX_VALUE_LENGTH) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, MAX_VALUE_LENGTH));
    }

    /**
     * 抽样子查询：只投影待探查列，按产品追加行数上限。
     */
    private String sample(ProfileRequest request, List<ProfileColumn> columns) {
        String projection = columns.stream().map(column -> quote(request.product(), column.name()))
                .collect(Collectors.joining(", "));
        return limit(request.product(), "SELECT " + projection + " FROM " + qualifiedTable(request),
                request.sampleRows());
    }

    /**
     * 委托 DB Switch 为组件生成的 SELECT 追加行数上限。
     */
    private String limit(ProductTypeEnum product, String sql, int rows) {
        return products.metadata(product).limitGeneratedSelect(sql, rows);
    }

    /**
     * 生成单个统计项表达式；列已按产品引用。
     */
    private static String metricExpression(ProductTypeEnum product, ProfileMetric metric, String column) {
        return switch (metric) {
            case NON_NULL -> "COUNT(" + column + ")";
            case DISTINCT -> "COUNT(DISTINCT " + column + ")";
            case MIN -> "MIN(" + column + ")";
            case MAX -> "MAX(" + column + ")";
            case AVG_LENGTH -> "AVG(" + AuditSqlDialect.charLength(product, column) + ")";
            case MIN_LENGTH -> "MIN(" + AuditSqlDialect.charLength(product, column) + ")";
            case MAX_LENGTH -> "MAX(" + AuditSqlDialect.charLength(product, column) + ")";
            case AVG_VALUE -> "AVG(" + column + ")";
        };
    }

    /**
     * 统计项结果别名后缀（结果按位置读取，别名只用于可读性）。
     */
    private static String alias(ProfileMetric metric) {
        return switch (metric) {
            case NON_NULL -> "nn";
            case DISTINCT -> "dc";
            case MIN -> "min";
            case MAX -> "max";
            case AVG_LENGTH -> "avglen";
            case MIN_LENGTH -> "minlen";
            case MAX_LENGTH -> "maxlen";
            case AVG_VALUE -> "avg";
        };
    }

    /**
     * 物理表限定名；schema 为空时只引用表名。
     */
    private static String qualifiedTable(ProfileRequest request) {
        return request.schemaName() == null ? quote(request.product(), request.tableName())
                : RestrictedSelectGuard.qualifiedTableName(request.product(), request.schemaName(),
                request.tableName());
    }

    /**
     * 按产品引用单个物理标识符；名称非法时抛出 IllegalArgumentException。
     */
    private static String quote(ProductTypeEnum product, String name) {
        return RestrictedSelectGuard.quotePhysicalIdentifier(product, name);
    }

    /**
     * 拒绝支持矩阵之外的产品。
     */
    private static void requireSupported(ProductTypeEnum product) {
        if (!supports(product)) {
            throw new UnsupportedOperationException("数据探查暂不支持该数据库产品：" + product);
        }
    }

}

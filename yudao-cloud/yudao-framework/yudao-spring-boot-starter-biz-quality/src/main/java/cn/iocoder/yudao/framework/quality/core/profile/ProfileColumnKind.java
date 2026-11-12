package cn.iocoder.yudao.framework.quality.core.profile;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 探查字段类别：决定单遍聚合中为该列生成哪些统计表达式，以及是否生成 Top-N 查询。
 *
 * <p>未识别或不可比较的类型（LOB、JSON、二进制、数组、几何等）一律归为 {@link #OTHER}，只统计非空数，
 * 避免 {@code COUNT(DISTINCT)} / {@code MIN} 在目标库上因类型不支持而整条失败。</p>
 *
 * @author Li
 */
public enum ProfileColumnKind {

    /** 文本：非空、唯一、最小/最大值、平均/最小/最大长度 */
    TEXT(List.of(ProfileMetric.NON_NULL, ProfileMetric.DISTINCT, ProfileMetric.MIN, ProfileMetric.MAX,
            ProfileMetric.AVG_LENGTH, ProfileMetric.MIN_LENGTH, ProfileMetric.MAX_LENGTH), true),
    /** 数值：非空、唯一、最小/最大值、平均值 */
    NUMERIC(List.of(ProfileMetric.NON_NULL, ProfileMetric.DISTINCT, ProfileMetric.MIN, ProfileMetric.MAX,
            ProfileMetric.AVG_VALUE), true),
    /** 日期时间：非空、唯一、最小/最大值 */
    TEMPORAL(List.of(ProfileMetric.NON_NULL, ProfileMetric.DISTINCT, ProfileMetric.MIN, ProfileMetric.MAX), true),
    /** 布尔：非空、唯一（PostgreSQL 等不支持布尔 MIN/MAX） */
    BOOLEAN(List.of(ProfileMetric.NON_NULL, ProfileMetric.DISTINCT), true),
    /** 其他：只统计非空数，不生成 Top-N */
    OTHER(List.of(ProfileMetric.NON_NULL), false);

    private static final Set<String> NUMERIC_TYPES = Set.of("tinyint", "smallint", "mediumint", "int", "integer",
            "bigint", "int2", "int4", "int8", "smallserial", "serial", "bigserial", "serial2", "serial4", "serial8",
            "decimal", "dec", "numeric", "number", "float", "float4", "float8", "double", "double precision", "real",
            "binary_float", "binary_double", "byte");
    private static final Set<String> TEXT_TYPES = Set.of("char", "character", "varchar", "character varying",
            "varchar2", "nvarchar2", "nchar", "nvarchar", "national char", "national character", "bpchar", "text",
            "tinytext", "mediumtext", "longtext", "string", "name", "citext", "enum", "set", "varchar_ignorecase");
    private static final Set<String> TEMPORAL_TYPES = Set.of("date", "time", "timetz", "timestamp", "timestamptz",
            "datetime", "datetime2", "smalldatetime", "year", "time with time zone", "time without time zone",
            "timestamp with time zone", "timestamp without time zone", "timestamp with local time zone");
    private static final Set<String> BOOLEAN_TYPES = Set.of("boolean", "bool");
    /** Oracle 系中 TEXT/LONG 等名称对应 LOB 或不可比较类型 */
    private static final Set<String> ORACLE_LIKE_LOB_TYPES = Set.of("text", "long", "clob", "nclob",
            "longvarchar", "image", "blob", "bfile");

    private final List<ProfileMetric> metrics;
    private final boolean topValues;

    /**
     * 初始化字段类别及其统计项。
     */
    ProfileColumnKind(List<ProfileMetric> metrics, boolean topValues) {
        this.metrics = metrics;
        this.topValues = topValues;
    }

    /**
     * 返回该类别在聚合 SQL 中依次生成的统计项（顺序即结果列顺序）。
     */
    public List<ProfileMetric> metrics() {
        return metrics;
    }

    /**
     * 是否为该类别生成 Top-N 取值分布查询。
     */
    public boolean supportsTopValues() {
        return topValues;
    }

    /**
     * 按目标产品与元数据中的类型名称推断类别；无法确定时返回 {@link #OTHER}（失败保守）。
     *
     * @param product  目标数据库产品
     * @param typeName 元数据采集得到的类型名称，如 {@code varchar(64)}、{@code NUMBER(10,2)}、{@code timestamp(6) with time zone}
     */
    public static ProfileColumnKind fromTypeName(ProductTypeEnum product, String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return OTHER;
        }
        String raw = typeName.trim().toLowerCase(Locale.ROOT);
        if (raw.contains("[]") || raw.contains("<") || raw.startsWith("array") || raw.startsWith("map")
                || raw.startsWith("struct") || raw.startsWith("_")) {
            return OTHER;
        }
        // 去掉长度/精度参数与无符号等修饰：varchar(64) → varchar，timestamp(6) with time zone → timestamp with time zone
        String normalized = raw.replaceAll("\\([^)]*\\)", "")
                .replace(" unsigned", "").replace(" zerofill", "").replace(" signed", "")
                .replaceAll("\\s+", " ").trim();
        if (product != null && product.isLikeOracle() && ORACLE_LIKE_LOB_TYPES.contains(normalized)) {
            return OTHER;
        }
        if (NUMERIC_TYPES.contains(normalized)) {
            return NUMERIC;
        }
        if (TEXT_TYPES.contains(normalized)) {
            return TEXT;
        }
        if (TEMPORAL_TYPES.contains(normalized)) {
            return TEMPORAL;
        }
        if (BOOLEAN_TYPES.contains(normalized)) {
            return BOOLEAN;
        }
        return OTHER;
    }

}

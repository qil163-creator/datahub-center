package cn.iocoder.yudao.framework.dbswitch.product.influxdb3;

import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;

import java.util.List;

/**
 * InfluxDB 3 Core 3.11.5 列目录。SQL 使用已探测的 {@code information_schema.columns} 文本。
 * 模式为 {@code iox}。数据库名不是模式，{@code default_catalog} {@code public} 也不是。
 * {@code data_type} 区分大小写（{@code Int64}、{@code UInt64}、{@code Float64}、{@code Utf8}、{@code Boolean}、
 * {@code Timestamp(ns)}、{@code Dictionary(Int32, Utf8)}）。不得折叠大小写。
 * Utf8 长度为 {@code character_octet_length}（探测字段上为 2147483647）。所有探测列的 {@code character_maximum_length}
 * 均为 NULL，不得选取。Dictionary 标签的字节长度为 NULL。
 * Float64 的 {@code numeric_precision} 为 24，基数为 2，小数位数为 NULL。此读取器不读取基数列；
 * 不得将 24 改写为十进制位数，也不得在 SQL 中注入固定精度。
 * {@code parameters_visible} 保持 true，使 SQL NULL 成为 ABSENT。{@code system.influxdb_schema} 是 Utf8View，
 * 此 JDBC 驱动无法读取它。不得使用 {@code MYSQL_COLUMNS}。
 * 主键保持为空。{@code getPrimaryKeys} 在探测表上返回 0 行，重复的 tagset+time
 * 会覆盖先前数据点。该覆盖行为不是目录主键。不得执行 {@code MYSQL_PK}。
 * 附加对象状态为 {@link MetadataValue.State#UNKNOWN}。不得执行 {@code MYSQL_ADDITIONAL}。
 * 附加对象结果为空，不能证明没有附加对象。
 */
public final class InfluxDb3CatalogColumns {

    /** 物理 SQL 模式，绝非数据库名。 */
    public static final String SCHEMA = "iox";

    /**
     * 列别名与共享目录读取器一致。生成、身份、默认值、字符集和排序规则为 SQL NULL，
     * 因为探测列没有这些属性。别名为 {@code generated_expression}，而非 MySQL {@code GENERATION_EXPRESSION}。
     */
    public static final String COLUMNS = cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.InfluxDb3CatalogSql.COLUMNS;

    /** 与 NativeSchemaReader 使用相同的固定有界目录查询；Flight 绑定不可用。 */
    public static String columns(String schema, String table) {
        return cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.InfluxDb3CatalogSql.columns(schema, table);
    }

    /**
     * 初始化 {@code InfluxDb3CatalogColumns}。
     */
    private InfluxDb3CatalogColumns() {
    }

    /**
     * 有意保持为空。这不是主键查询结果，也不证明没有键。
     */
    public static List<String> primaryKeys() {
        return List.of();
    }

    /** 未查询。UNKNOWN 既不是空列表，也不是 ABSENT。 */
    public static MetadataValue<List<String>> additionalObjects() {
        return MetadataValue.unknown();
    }
}

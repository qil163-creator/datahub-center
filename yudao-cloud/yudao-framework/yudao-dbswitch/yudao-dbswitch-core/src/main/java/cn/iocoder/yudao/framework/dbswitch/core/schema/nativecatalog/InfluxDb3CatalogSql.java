package cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog;

/** 用于现有普通名称元数据契约的固定 Flight SQL 目录投影。 */
public final class InfluxDb3CatalogSql {

    public static final String COLUMNS = """
            SELECT column_name,
                   data_type AS native_type,
                   CAST(NULL AS VARCHAR) AS default_expression,
                   CAST(NULL AS VARCHAR) AS generated_expression,
                   CAST(NULL AS VARCHAR) AS identity_clause,
                   is_nullable = 'YES' AS is_nullable,
                   numeric_precision,
                   CAST(NULL AS INTEGER) AS numeric_scale,
                   character_octet_length AS character_length,
                   true AS parameters_visible,
                   CAST(NULL AS VARCHAR) AS character_set,
                   CAST(NULL AS VARCHAR) AS collation_name,
                   CAST(NULL AS VARCHAR) AS column_comment
            FROM information_schema.columns
            """;

    /**
     * 初始化 {@code InfluxDb3CatalogSql}。
     */
    private InfluxDb3CatalogSql() {
    }

    /**
     * InfluxDB 3.11.5/Flight JDBC 18.1.0 对两个问号报告只有一个参数；编号参数
     * 会报告数量，但绑定值始终未进入查询执行。仅将此前已支持的
     * 普通名称渲染到此固定谓词中，不接受表达式，也不扩展标识符支持范围。
     */
    public static String columns(String schema, String table) {
        if (!"iox".equals(schema) || table == null || !table.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("InfluxDB catalog requires iox and an ordinary physical table name");
        }
        return COLUMNS + "WHERE table_schema = 'iox' AND table_name = '" + table
                + "' ORDER BY ordinal_position LIMIT 2001";
    }
}

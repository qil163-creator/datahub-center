package cn.iocoder.yudao.framework.dbswitch.product.opengauss;

import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;

import java.util.List;

/**
 * openGauss 7.0.0-RC3 A 模式列目录。语句已在 {@code public.wave14_types} 上执行成功。
 * 这台服务器的 {@code pg_attribute} 没有 {@code attgenerated}、{@code attidentity}，也没有 {@code to_regclass}，
 * 所以不能用 {@code POSTGRES_COLUMNS}。也不能用 {@code MYSQL_COLUMNS}。
 * 主键和附加对象没有执行过：不要换成 {@code POSTGRES_PK}，也不要执行 {@code MYSQL_PK}。
 * 附加对象保持未知，空结果不是“没有”。
 */
public final class OpengaussCatalogColumns {

    /**
     * 初始化 {@code OpengaussCatalogColumns}。
     */
    private OpengaussCatalogColumns() {
    }

    /** 两个占位符依次是 schema、表名。生成表达式和 identity 子句是 SQL NULL，因为对应目录列不存在。 */
    public static final String COLUMNS = """
            SELECT a.attname AS column_name, pg_catalog.format_type(a.atttypid, a.atttypmod) AS native_type,
              pg_catalog.pg_get_expr(d.adbin, d.adrelid) AS default_expression,
              NULL::text AS generated_expression,
              NULL::text AS identity_clause,
              NOT a.attnotnull AS is_nullable, i.numeric_precision, i.numeric_scale,
              i.character_maximum_length AS character_length, i.column_name IS NOT NULL AS parameters_visible,
              NULL::text AS character_set,
              CASE WHEN a.attcollation<>t.typcollation THEN quote_ident(cn.nspname)||'.'||quote_ident(co.collname) END AS collation_name,
              pg_catalog.col_description(c.oid, a.attnum) AS column_comment
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
            JOIN pg_catalog.pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped
            JOIN pg_catalog.pg_type t ON t.oid=a.atttypid
            LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
            LEFT JOIN information_schema.columns i
              ON i.table_schema=n.nspname AND i.table_name=c.relname AND i.column_name=a.attname
            LEFT JOIN pg_catalog.pg_collation co ON co.oid=a.attcollation
            LEFT JOIN pg_catalog.pg_namespace cn ON cn.oid=co.collnamespace
            WHERE n.nspname=? AND c.relname=? AND c.relkind IN ('r','p','v','m','f') ORDER BY a.attnum
            """;

    /**
     * 返回列集合。
     */
    public static String columns() {
        return COLUMNS;
    }

    /** 主键目录没有执行。未知不是“没有主键”。 */
    public static MetadataValue<List<String>> primaryKeys() {
        return MetadataValue.unknown();
    }

    /** 附加对象没有执行。未知不是“没有附加对象”。 */
    public static MetadataValue<List<String>> additionalObjects() {
        return MetadataValue.unknown();
    }
}

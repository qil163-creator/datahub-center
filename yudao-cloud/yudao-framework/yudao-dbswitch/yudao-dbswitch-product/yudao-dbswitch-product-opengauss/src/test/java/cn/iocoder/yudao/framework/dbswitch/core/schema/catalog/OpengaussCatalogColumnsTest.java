package cn.iocoder.yudao.framework.dbswitch.core.schema.catalog;

import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.product.opengauss.OpengaussCatalogColumns;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 列目录语句的文本契约。不连接数据库，也不执行主键或附加对象查询。 */
class OpengaussCatalogColumnsTest {
    private static final String COLUMNS = """
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

    @Test
    void columnSqlIsTheVerifiedOpenGaussCatalog() throws Exception {
        assertEquals(COLUMNS, OpengaussCatalogColumns.COLUMNS);
        assertEquals(COLUMNS, OpengaussCatalogColumns.columns());
        assertEquals(2, COLUMNS.length() - COLUMNS.replace("?", "").length());
        assertFalse(COLUMNS.contains("attgenerated"));
        assertFalse(COLUMNS.contains("attidentity"));
        assertFalse(COLUMNS.contains("to_regclass"));
        assertFalse(COLUMNS.contains("unnest"));
        assertFalse(COLUMNS.contains("pg_constraint"));
        assertFalse(COLUMNS.contains("information_schema.COLUMNS"));
        assertFalse(COLUMNS.contains("GENERATION_EXPRESSION"));
        assertFalse(COLUMNS.contains("AUTO_INCREMENT"));
        assertTrue(COLUMNS.contains("NULL::text AS generated_expression"));
        assertTrue(COLUMNS.contains("NULL::text AS identity_clause"));
        assertTrue(COLUMNS.contains("information_schema.columns"));
        List<String> sql = new ArrayList<>();
        for (Field field : OpengaussCatalogColumns.class.getDeclaredFields()) {
            if (field.getType() == String.class) {
                field.setAccessible(true);
                sql.add((String) field.get(null));
            }
        }
        assertEquals(List.of(COLUMNS), sql);
        for (Method method : OpengaussCatalogColumns.class.getDeclaredMethods()) {
            if (method.getReturnType() == String.class) {
                assertEquals("columns", method.getName());
                assertEquals(0, method.getParameterCount());
            }
        }
    }

    @Test
    void primaryKeysAndAdditionalObjectsStayUnknown() {
        MetadataValue<List<String>> primaryKeys = OpengaussCatalogColumns.primaryKeys();
        MetadataValue<List<String>> additional = OpengaussCatalogColumns.additionalObjects();
        assertEquals(MetadataValue.State.UNKNOWN, primaryKeys.state());
        assertEquals(MetadataValue.State.UNKNOWN, additional.state());
        assertNull(primaryKeys.value());
        assertNull(additional.value());
        assertFalse(primaryKeys.known());
        assertFalse(additional.known());
    }
}

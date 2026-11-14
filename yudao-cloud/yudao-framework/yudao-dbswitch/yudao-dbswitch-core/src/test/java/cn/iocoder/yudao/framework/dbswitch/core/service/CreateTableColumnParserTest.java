package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static cn.iocoder.yudao.framework.dbswitch.core.service.CreateTableColumnParser.Column;
import static cn.iocoder.yudao.framework.dbswitch.core.service.CreateTableColumnParser.parse;
import static org.junit.jupiter.api.Assertions.*;

/** 建表语句解析：四种方言的字段、主键、注释与默认值，以及拒绝规则。 */
class CreateTableColumnParserTest {

    @Test
    void mysqlInlineCommentsPrimaryKeyAndDefaults() {
        var result = parse(ProductTypeEnum.MYSQL, "CREATE TABLE `shop`.`t_order` ("
                + "`id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',"
                + "`amount` decimal(18,2) DEFAULT '0.00' COMMENT '金额',"
                + "`name` varchar(64) NULL,"
                + "PRIMARY KEY (`id`)) ENGINE=InnoDB COMMENT='订单'");

        assertEquals("shop", result.schemaName());
        assertEquals("t_order", result.tableName());
        assertEquals("订单", result.tableComment());
        assertEquals(new Column("id", "BIGINT", null, null, null, false, true, null, "主键"), result.columns().get(0));
        assertEquals(new Column("amount", "DECIMAL", null, 18, 2, true, false, "0.00", "金额"),
                result.columns().get(1));
        assertEquals(new Column("name", "VARCHAR", 64, null, null, true, false, null, null), result.columns().get(2));
    }

    @Test
    void dorisUniqueKeyColumnsAreTreatedAsPrimaryKey() {
        var result = parse(ProductTypeEnum.DORIS, "CREATE TABLE dw.fact_order ("
                + "order_id BIGINT NOT NULL COMMENT '订单', region VARCHAR(32) COMMENT '区域',"
                + "amount DECIMAL(20,4) DEFAULT \"0\") UNIQUE KEY(order_id) COMMENT '订单事实'"
                + " DISTRIBUTED BY HASH(order_id) BUCKETS 10 PROPERTIES (\"replication_num\" = \"1\")");

        assertEquals("dw", result.schemaName());
        assertEquals("fact_order", result.tableName());
        assertTrue(result.columns().get(0).primaryKey());
        assertFalse(result.columns().get(0).nullable());
        assertEquals(32, result.columns().get(1).length());
        assertEquals("区域", result.columns().get(1).comment());
        assertEquals(20, result.columns().get(2).precision());
        assertEquals(4, result.columns().get(2).scale());
        assertEquals("0", result.columns().get(2).defaultValue());
    }

    @Test
    void postgresTableLevelPrimaryKeyAndCommentOnStatements() {
        var result = parse(ProductTypeEnum.POSTGRESQL, "CREATE TABLE public.t_user (id int8 NOT NULL,"
                + " name varchar(50), created timestamp(3) DEFAULT now(), CONSTRAINT pk_user PRIMARY KEY (id));"
                + " COMMENT ON COLUMN public.t_user.name IS '姓名'; COMMENT ON TABLE public.t_user IS '用户'");

        assertEquals("用户", result.tableComment());
        assertTrue(result.columns().get(0).primaryKey());
        assertEquals("INT8", result.columns().get(0).typeName());
        assertEquals("姓名", result.columns().get(1).comment());
        assertEquals("TIMESTAMP", result.columns().get(2).typeName());
        assertNull(result.columns().get(2).precision(), "时间类型的小数秒不当作定点精度");
        assertEquals("now()", result.columns().get(2).defaultValue());
    }

    @Test
    void oracleQuotedNamesNumberPrecisionAndComments() {
        var result = parse(ProductTypeEnum.ORACLE, "CREATE TABLE \"T_DEPT\" (\"DEPT_ID\" NUMBER(10,0) NOT NULL,"
                + " \"DEPT_NAME\" VARCHAR2(100 CHAR), PRIMARY KEY (\"DEPT_ID\"));"
                + " COMMENT ON COLUMN \"T_DEPT\".\"DEPT_NAME\" IS '部门名称'");

        assertEquals("T_DEPT", result.tableName());
        assertEquals(new Column("DEPT_ID", "NUMBER", null, 10, 0, false, true, null, null), result.columns().get(0));
        assertEquals("VARCHAR2", result.columns().get(1).typeName());
        assertEquals(100, result.columns().get(1).length());
        assertEquals("部门名称", result.columns().get(1).comment());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CREATE TABLE a (id int); CREATE TABLE b (id int)",
            "CREATE TABLE a (id int); DROP TABLE a",
            "CREATE TABLE a (id int); COMMENT ON COLUMN b.id IS 'x'",
            "CREATE TABLE a (id int, ID int)",
            "SELECT 1",
            "CREATE TABLE a (",
            "   "
    })
    void rejectsAnythingButOneCreateTableWithMatchingComments(String sql) {
        assertThrows(IllegalArgumentException.class, () -> parse(ProductTypeEnum.POSTGRESQL, sql));
    }

    @Test
    void rejectsOversizedInput() {
        String sql = "CREATE TABLE a (id int) " + " ".repeat(CreateTableColumnParser.MAX_SQL_LENGTH);
        assertThrows(IllegalArgumentException.class, () -> parse(ProductTypeEnum.MYSQL, sql));
    }

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"SQLSERVER", "DM", "HIVE", "CLICKHOUSE", "UNKNOWN"})
    void unverifiedDialectsAreUnsupported(ProductTypeEnum product) {
        assertFalse(CreateTableColumnParser.supports(product));
        assertThrows(UnsupportedOperationException.class, () -> parse(product, "CREATE TABLE a (id int)"));
    }
}

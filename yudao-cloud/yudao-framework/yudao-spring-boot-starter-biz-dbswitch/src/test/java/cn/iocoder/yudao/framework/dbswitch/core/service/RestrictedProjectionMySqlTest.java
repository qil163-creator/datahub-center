package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** #17 MYSQL 差异验证：默认、ANSI_QUOTES、NO_BACKSLASH_ESCAPES 下受限投影输出都不能触达未批准列。 */
@EnabledIfSystemProperty(named = "datahub.test.mysql.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:.*")
class RestrictedProjectionMySqlTest {
    private Connection connection;
    private String schema;
    private String decoy;

    @BeforeEach
    void create() throws Exception {
        schema = RestrictedProjectionDifferential.schemaName("q17_");
        connection = RestrictedProjectionDifferential.open(System.getProperty("datahub.test.mysql.url"),
                System.getProperty("datahub.test.mysql.user", "root"));
        try (var sql = connection.createStatement()) {
            sql.execute("CREATE DATABASE " + schema);
            sql.execute("CREATE TABLE " + schema + ".citizen(id bigint, name varchar(64), secret varchar(64))");
            sql.execute("INSERT INTO " + schema + ".citizen VALUES (1,'row','s1'),(2,'row','s2')");
        }
    }

    @AfterEach
    void drop() throws Exception {
        if (connection == null) return;
        try (var owned = connection; var sql = owned.createStatement()) {
            sql.execute("SET SESSION sql_mode = DEFAULT");
            sql.execute("DROP DATABASE IF EXISTS " + schema);
            if (decoy != null) {
                sql.execute("DROP DATABASE IF EXISTS " + decoy);
            }
        }
    }

    @Test
    void acceptedProjectionsNeverReachUnapprovedColumnsUnderSessionModes() throws Exception {
        int accepted = RestrictedProjectionDifferential.run(ProductTypeEnum.MYSQL, connection, schema, "citizen",
                List.of("SET SESSION sql_mode = DEFAULT",
                        "SET SESSION sql_mode = CONCAT(@@GLOBAL.sql_mode, ',ANSI_QUOTES')",
                        "SET SESSION sql_mode = CONCAT(@@GLOBAL.sql_mode, ',NO_BACKSLASH_ESCAPES')",
                        "SET SESSION sql_mode = 'ANSI_QUOTES,NO_BACKSLASH_ESCAPES'"));
        // 加固后所有越权输入都在授权前被拒绝；合法查询的真实执行覆盖由 run(...) 内部断言。
        assertEquals(0, accepted);
    }

    @Test
    void configuredDatabaseWinsOverTheSessionDatabase() throws Exception {
        decoy = schema + "d";
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(30);
            try (var version = sql.executeQuery("SELECT VERSION(), @@lower_case_table_names")) {
                assertTrue(version.next());
                assertTrue(version.getString(1).startsWith("8.4."), version.getString(1));
                assertEquals("0", version.getString(2));
            }
            sql.execute("CREATE DATABASE " + decoy);
            sql.execute("CREATE TABLE " + decoy + ".citizen(id bigint)");
            sql.execute("INSERT INTO " + decoy + ".citizen VALUES (99)");
            sql.execute("CREATE TABLE " + schema + ".Citizen(id bigint)");
            sql.execute("INSERT INTO " + schema + ".Citizen VALUES (55)");
            sql.execute("USE " + decoy);
        }
        assertEquals(java.util.List.of("99"), firstColumn("SELECT id FROM citizen"));
        var bound = RestrictedSelectGuard.parseBound(ProductTypeEnum.MYSQL, "select id from citizen", schema);
        assertEquals(schema, bound.getSchemaName());
        assertEquals("citizen", bound.getTableName());
        assertTrue(bound.getNormalizedSql().contains("`" + schema + "`.`citizen`"), bound.getNormalizedSql());
        assertEquals(java.util.List.of("1", "2"), firstColumn(bound.getNormalizedSql() + " order by id"));
        var other = RestrictedSelectGuard.parseBound(ProductTypeEnum.MYSQL,
                "select id from " + decoy + ".citizen", schema);
        assertEquals(decoy, other.getSchemaName());
        assertEquals(java.util.List.of("99"), firstColumn(other.getNormalizedSql()));
        var mixed = RestrictedSelectGuard.parseBound(ProductTypeEnum.MYSQL, "select id from Citizen", schema);
        assertEquals("Citizen", mixed.getTableName());
        assertEquals(java.util.List.of("55"), firstColumn(mixed.getNormalizedSql()));
    }

    private java.util.List<String> firstColumn(String sql) throws Exception {
        java.util.List<String> values = new java.util.ArrayList<>();
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            try (var rows = statement.executeQuery(sql)) {
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
            }
        }
        return values;
    }
}

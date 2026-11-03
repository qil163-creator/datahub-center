package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #17 Doris 差异验证：Doris 4.0.8 的 {@code VERSION()} 返回 {@code 5.7.99}，真实版本在
 * {@code version_comment}。它走 MySQL 线协议、反引号引用，{@code isLikeMysql()}，与 MySQL/MariaDB/TiDB/StarRocks
 * 共用 {@code MySqlLexer} 与 Druid {@code mysql} 方言。{@code lower_case_table_names=0}：未加引号表名区分大小写，
 * 列名不区分。会话变量可设为 DEFAULT、ANSI_QUOTES、NO_BACKSLASH_ESCAPES 及组合；实测 ANSI_QUOTES 不把双引号改成标识符，
 * NO_BACKSLASH_ESCAPES 会让反斜杠变成字面字符。守卫只输出反引号，并拒绝含反斜杠或单引号的字面值。
 * <p>
 * 连接沿用组件内既有 Doris 真实库测试约定：系统属性 {@code datahub.test.doris.url} 指向本机实例
 * （{@code jdbc:mysql://127.0.0.1:PORT/}），用户默认 {@code root}、空口令（可用 {@code datahub.test.doris.user} 覆盖）。
 * 建表使用 Doris 语法（{@code DUPLICATE KEY} + {@code DISTRIBUTED BY} + 单副本），DROP 用 {@code FORCE}。
 */
@EnabledIfSystemProperty(named = "datahub.test.doris.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
class RestrictedProjectionDorisTest {
    private static final List<String> SESSION_MODES = List.of(
            "SET SESSION sql_mode = DEFAULT",
            "SET SESSION sql_mode = CONCAT(@@GLOBAL.sql_mode, ',ANSI_QUOTES')",
            "SET SESSION sql_mode = CONCAT(@@GLOBAL.sql_mode, ',NO_BACKSLASH_ESCAPES')",
            "SET SESSION sql_mode = 'ANSI_QUOTES,NO_BACKSLASH_ESCAPES'");
    private static final String TABLE_SUFFIX = " DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 "
            + "PROPERTIES ('replication_num'='1')";
    private Connection connection;
    private String schema;
    private String decoy;

    @BeforeEach
    void create() throws Exception {
        schema = RestrictedProjectionDifferential.schemaName("q17_");
        connection = RestrictedProjectionDifferential.open(System.getProperty("datahub.test.doris.url"),
                System.getProperty("datahub.test.doris.user", "root"));
        assertVerifiedDorisCaseMode();
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(60);
            sql.execute("CREATE DATABASE " + schema);
            sql.execute("CREATE TABLE " + schema + ".citizen(id bigint, name varchar(64), secret varchar(64))"
                    + TABLE_SUFFIX);
            sql.execute("INSERT INTO " + schema + ".citizen VALUES (1,'row','s1'),(2,'row','s2')");
        }
    }

    @AfterEach
    void drop() throws Exception {
        if (connection == null) return;
        try (var owned = connection; var sql = owned.createStatement()) {
            sql.setQueryTimeout(60);
            try {
                sql.execute("SET SESSION sql_mode = DEFAULT");
            } finally {
                try {
                    sql.execute("DROP DATABASE IF EXISTS " + schema + " FORCE");
                } finally {
                    if (decoy != null) sql.execute("DROP DATABASE IF EXISTS " + decoy + " FORCE");
                }
            }
        }
    }

    @Test
    void acceptedProjectionsNeverReachUnapprovedColumnsUnderSessionModes() throws Exception {
        int accepted = RestrictedProjectionDifferential.run(ProductTypeEnum.DORIS, connection, schema, "citizen",
                SESSION_MODES);
        // 加固后所有越权输入都在授权前被拒绝；合法查询的真实执行覆盖由 run(...) 内部断言。
        assertEquals(0, accepted);
    }

    @Test
    void caseDistinctTablesCannotBeRewrittenIntoTheAuthorizedTable() throws Exception {
        String lower = qualified(schema, "citizen");
        String upper = qualified(schema, "Citizen");
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(60);
            sql.execute("CREATE TABLE " + upper + "(id bigint, name varchar(64), secret varchar(64))" + TABLE_SUFFIX);
            sql.execute("INSERT INTO " + upper + " VALUES (55,'upper','s55')");
        }
        for (String mode : SESSION_MODES) {
            setMode(mode);
            // 两种拼写在原生 Doris 上确实命中不同表；拒绝断言不能只依赖解析器输出。
            assertEquals(List.of("1", "2"), firstColumn("SELECT ID FROM " + lower + " ORDER BY ID"), mode);
            assertEquals(List.of("55"), firstColumn("SELECT ID FROM " + upper), mode);
            for (String sql : List.of("SELECT id FROM " + schema + ".Citizen", "SELECT id FROM " + upper,
                    "SELECT id FROM Citizen")) {
                assertThrows(IllegalArgumentException.class, () -> restrict(sql, schema, "citizen"), mode + ": " + sql);
            }
            for (String sql : List.of("SELECT id FROM " + schema + ".citizen", "SELECT id FROM " + lower,
                    "SELECT id FROM citizen")) {
                assertThrows(IllegalArgumentException.class, () -> restrict(sql, schema, "Citizen"), mode + ": " + sql);
            }
            // 列名大小写、WHERE、ORDER BY 仍走既有列规则，合法的大写表不会被折叠。
            String uppercaseProjection = restrict("SELECT ID FROM " + upper
                    + " WHERE NAME = 'upper' ORDER BY ID", schema, "Citizen").sql();
            assertTrue(uppercaseProjection.contains(upper), uppercaseProjection);
            assertEquals(List.of("55"), firstColumn(uppercaseProjection), mode);
            String lowercaseProjection = restrict("SELECT ID FROM " + lower
                    + " WHERE NAME = 'row' ORDER BY ID", schema, "citizen").sql();
            assertEquals(List.of("1", "2"), firstColumn(lowercaseProjection), mode);
        }
    }

    @Test
    void crossDatabaseNamesCannotReplaceTheAuthorizedDatabase() throws Exception {
        decoy = schema + "d";
        String actual = qualified(schema, "citizen");
        String other = qualified(decoy, "citizen");
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(60);
            sql.execute("CREATE DATABASE " + decoy);
            sql.execute("CREATE TABLE " + other + "(id bigint, name varchar(64), secret varchar(64))" + TABLE_SUFFIX);
            sql.execute("INSERT INTO " + other + " VALUES (99,'decoy','s99')");
        }
        // Connector/J 的 Statement 绑定创建时的 catalog，切库后所有查询均新建 Statement。
        connection.setCatalog(decoy);
        for (String mode : SESSION_MODES) {
            setMode(mode);
            assertEquals(List.of("99"), firstColumn("SELECT ID FROM citizen"), mode);
            assertEquals(List.of("1", "2"), firstColumn("SELECT ID FROM " + actual + " ORDER BY ID"), mode);
            for (String sql : List.of("SELECT id FROM " + decoy + ".citizen", "SELECT id FROM " + other)) {
                assertThrows(IllegalArgumentException.class, () -> restrict(sql, schema, "citizen"), mode + ": " + sql);
            }
            for (String sql : List.of("SELECT id FROM " + schema + ".citizen", "SELECT id FROM " + actual)) {
                assertThrows(IllegalArgumentException.class, () -> restrict(sql, decoy, "citizen"), mode + ": " + sql);
            }
            String bound = restrict("SELECT ID FROM citizen WHERE NAME = 'row' ORDER BY ID", schema, "citizen").sql();
            assertTrue(bound.contains(actual), bound);
            assertEquals(List.of("1", "2"), firstColumn(bound), mode);
            assertEquals(List.of("99"), firstColumn(restrict(
                    "SELECT ID FROM " + other, decoy, "citizen").sql()), mode);
            assertEquals(decoy, connection.getCatalog(), "投影解析和执行不能切换调用方的当前库");
        }
    }

    private RestrictedSelectProjection restrict(String sql, String database, String table) {
        return RestrictedSelectGuard.restrictProjection(ProductTypeEnum.DORIS, sql, database, table,
                List.of("id", "name", "secret"), Set.of("id", "name"));
    }

    private String qualified(String database, String table) {
        return RestrictedSelectGuard.qualifiedTableName(ProductTypeEnum.DORIS, database, table);
    }

    private void setMode(String mode) throws Exception {
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(60);
            sql.execute(mode);
        }
    }

    private List<String> firstColumn(String query) throws Exception {
        var values = new ArrayList<String>();
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(60);
            sql.setMaxRows(10);
            try (var rows = sql.executeQuery(query)) {
                while (rows.next()) values.add(rows.getString(1));
            }
        }
        return values;
    }

    private void assertVerifiedDorisCaseMode() throws Exception {
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(60);
            sql.setMaxRows(65);
            try (var rows = sql.executeQuery("SELECT CURRENT_CATALOG(), @@lower_case_table_names")) {
                assertTrue(rows.next());
                assertEquals("internal", rows.getString(1));
                assertEquals("0", rows.getString(2));
                assertFalse(rows.next());
            }
            // MySQL 协议 VERSION() 不作为 Doris 产品证据。
            int connected = 0;
            try (var rows = sql.executeQuery("SHOW FRONTENDS")) {
                while (rows.next()) {
                    if (!"Yes".equals(rows.getString("CurrentConnected"))) continue;
                    connected++;
                    String version = rows.getString("Version");
                    assertTrue(version != null && version.matches("doris-4[.]0[.]8(?:[-+].*)?"), version);
                    assertTrue(rows.getBoolean("Alive"));
                    assertTrue(rows.getBoolean("Join"));
                }
            }
            assertEquals(1, connected, "必须连接到唯一健康的 Doris 4.0.8 FE");
        }
    }
}

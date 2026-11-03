package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #17 TiDB 差异验证：TiDB 为 MySQL 协议族（{@code isLikeMysql()}，与 MySQL/MariaDB 共用反引号、
 * {@code MySqlLexer} 与 Druid {@code mysql} 方言）。默认、ANSI_QUOTES、NO_BACKSLASH_ESCAPES 及组合下，
 * 受限投影输出都不能借字面值/注释/变量/附加子句触达未批准列。
 * <p>
 * 连接沿用组件内既有 TiDB 真实库测试约定：系统属性 {@code datahub.test.tidb.url} 指向本机实例
 * （{@code jdbc:mysql://}），用户默认 {@code root}、空口令（可用 {@code datahub.test.tidb.user} 覆盖）。
 */
@EnabledIfSystemProperty(named = "datahub.test.tidb.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:.*")
class RestrictedProjectionTiDbTest {
    private Connection connection;
    private String schema;

    @BeforeEach
    void create() throws Exception {
        schema = RestrictedProjectionDifferential.schemaName("q17_");
        connection = RestrictedProjectionDifferential.open(System.getProperty("datahub.test.tidb.url"),
                System.getProperty("datahub.test.tidb.user", "root"));
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
        }
    }

    @Test
    void acceptedProjectionsNeverReachUnapprovedColumnsUnderSessionModes() throws Exception {
        int accepted = RestrictedProjectionDifferential.run(ProductTypeEnum.TIDB, connection, schema, "citizen",
                List.of("SET SESSION sql_mode = DEFAULT",
                        "SET SESSION sql_mode = CONCAT(@@GLOBAL.sql_mode, ',ANSI_QUOTES')",
                        "SET SESSION sql_mode = CONCAT(@@GLOBAL.sql_mode, ',NO_BACKSLASH_ESCAPES')",
                        "SET SESSION sql_mode = 'ANSI_QUOTES,NO_BACKSLASH_ESCAPES'"));
        // 加固后所有越权输入都在授权前被拒绝；合法查询的真实执行覆盖由 run(...) 内部断言。
        assertEquals(0, accepted);
    }
}

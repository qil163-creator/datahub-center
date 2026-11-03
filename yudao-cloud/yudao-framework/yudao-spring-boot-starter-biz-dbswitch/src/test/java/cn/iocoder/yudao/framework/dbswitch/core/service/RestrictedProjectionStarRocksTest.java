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
 * #17 StarRocks 差异验证：StarRocks 3.5.21 走 MySQL 线协议（{@code version}=8.0.33），反引号引用，
 * {@code isLikeMysql()}，与 MySQL/MariaDB/TiDB 共用 {@code MySqlLexer} 与 Druid {@code mysql} 方言。
 * 会话变量可设为 DEFAULT、ANSI_QUOTES、NO_BACKSLASH_ESCAPES 及组合；实测 ANSI_QUOTES 不把双引号改成标识符，
 * NO_BACKSLASH_ESCAPES 也不关闭反斜杠转义。守卫只输出反引号标识符，并拒绝含反斜杠或单引号的字面值，
 * 因此这些语义差不放行本套攻击。四种会话变量下，受限投影输出都不能触达未批准列。
 * <p>
 * 连接沿用组件内既有 StarRocks 真实库测试约定：系统属性 {@code datahub.test.starrocks.url} 指向本机实例
 * （{@code jdbc:mysql://127.0.0.1:PORT/}），用户默认 {@code root}、空口令（可用 {@code datahub.test.starrocks.user} 覆盖）。
 * 建表使用 StarRocks 语法（{@code DUPLICATE KEY} + {@code DISTRIBUTED BY} + 单副本），DROP 用 {@code FORCE}。
 */
@EnabledIfSystemProperty(named = "datahub.test.starrocks.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
class RestrictedProjectionStarRocksTest {
    private Connection connection;
    private String schema;

    @BeforeEach
    void create() throws Exception {
        schema = RestrictedProjectionDifferential.schemaName("q17_");
        connection = RestrictedProjectionDifferential.open(System.getProperty("datahub.test.starrocks.url"),
                System.getProperty("datahub.test.starrocks.user", "root"));
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(30);
            sql.execute("CREATE DATABASE " + schema);
            sql.execute("CREATE TABLE " + schema + ".citizen(id bigint, name varchar(64), secret varchar(64)) "
                    + "DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ('replication_num'='1')");
            sql.execute("INSERT INTO " + schema + ".citizen VALUES (1,'row','s1'),(2,'row','s2')");
        }
    }

    @AfterEach
    void drop() throws Exception {
        if (connection == null) return;
        try (var owned = connection; var sql = owned.createStatement()) {
            sql.execute("SET SESSION sql_mode = DEFAULT");
            sql.execute("DROP DATABASE IF EXISTS " + schema + " FORCE");
        }
    }

    @Test
    void acceptedProjectionsNeverReachUnapprovedColumnsUnderSessionModes() throws Exception {
        int accepted = RestrictedProjectionDifferential.run(ProductTypeEnum.STARROCKS, connection, schema, "citizen",
                List.of("SET SESSION sql_mode = DEFAULT",
                        "SET SESSION sql_mode = CONCAT(@@GLOBAL.sql_mode, ',ANSI_QUOTES')",
                        "SET SESSION sql_mode = CONCAT(@@GLOBAL.sql_mode, ',NO_BACKSLASH_ESCAPES')",
                        "SET SESSION sql_mode = 'ANSI_QUOTES,NO_BACKSLASH_ESCAPES'"));
        // 加固后所有越权输入都在授权前被拒绝；合法查询的真实执行覆盖由 run(...) 内部断言。
        assertEquals(0, accepted);
    }
}

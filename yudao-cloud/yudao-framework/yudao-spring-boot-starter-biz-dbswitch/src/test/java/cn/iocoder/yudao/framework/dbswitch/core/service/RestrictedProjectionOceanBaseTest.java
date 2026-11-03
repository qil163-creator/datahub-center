package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #17 OceanBase CE 4.3.5.6 MySQL 模式差异验证。{@code @@ob_compatibility_mode=MYSQL}，
 * {@code lower_case_table_names=1}。反引号是标识符。默认双引号是字符串；{@code ANSI_QUOTES}
 * 会把双引号改成标识符，{@code NO_BACKSLASH_ESCAPES} 会关闭反斜杠转义。两种设置下反引号查询仍可执行。
 * Oracle 模式不在本次范围。
 * <p>
 * 连接沿用既有约定：{@code datahub.test.oceanbase.url}，用户 {@code root@test}、空口令。
 */
@EnabledIfSystemProperty(named = "datahub.test.oceanbase.url", matches = "jdbc:oceanbase://127[.]0[.]0[.]1:[0-9]+/")
class RestrictedProjectionOceanBaseTest {
    private Connection connection;
    private String schema;

    @BeforeEach
    void create() throws Exception {
        Class.forName(ProductTypeEnum.OCEANBASE.getDriveClassName());
        schema = RestrictedProjectionDifferential.schemaName("q17_");
        connection = DriverManager.getConnection(System.getProperty("datahub.test.oceanbase.url"),
                System.getProperty("datahub.test.oceanbase.user", "root@test"), "");
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(30);
            sql.execute("CREATE DATABASE " + schema);
            sql.execute("CREATE TABLE " + schema + ".citizen(id bigint, name varchar(64), secret varchar(64))");
            sql.execute("INSERT INTO " + schema + ".citizen VALUES (1,'row','s1'),(2,'row','s2')");
        }
    }

    @AfterEach
    void drop() throws Exception {
        if (connection == null) return;
        try (var owned = connection; var sql = owned.createStatement()) {
            sql.execute("SET SESSION sql_mode = @@GLOBAL.sql_mode");
            sql.execute("DROP DATABASE IF EXISTS " + schema);
        }
    }

    @Test
    void acceptedProjectionsNeverReachUnapprovedColumnsUnderSessionModes() throws Exception {
        int accepted = RestrictedProjectionDifferential.run(ProductTypeEnum.OCEANBASE, connection, schema, "citizen",
                List.of("SET SESSION sql_mode = @@GLOBAL.sql_mode",
                        "SET SESSION sql_mode = CONCAT(@@GLOBAL.sql_mode, ',ANSI_QUOTES')",
                        "SET SESSION sql_mode = CONCAT(@@GLOBAL.sql_mode, ',NO_BACKSLASH_ESCAPES')",
                        "SET SESSION sql_mode = 'ANSI_QUOTES,NO_BACKSLASH_ESCAPES'"));
        assertEquals(0, accepted);
    }
}

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
 * #17 SQL Server 2022 差异验证。默认排序规则下未加引号和双引号标识符都不区分大小写。
 * QUOTED_IDENTIFIER ON 时双引号是标识符；方括号同样是标识符。反斜杠不是转义。
 * 不使用 LIMIT。SQL Server 2000 不在本次范围。
 */
@EnabledIfSystemProperty(named = "datahub.test.sqlserver.url",
        matches = "jdbc:sqlserver://127[.]0[.]0[.]1:[0-9]+;databaseName=codex_q11;encrypt=false")
class RestrictedProjectionSqlServerTest {
    private Connection connection;
    private String schema;

    @BeforeEach
    void create() throws Exception {
        schema = RestrictedProjectionDifferential.schemaName("q17_");
        connection = DriverManager.getConnection(System.getProperty("datahub.test.sqlserver.url"),
                System.getProperty("datahub.test.sqlserver.user", "sa"),
                System.getenv("DATAHUB_TEST_SQLSERVER_PASSWORD") != null
                        ? System.getenv("DATAHUB_TEST_SQLSERVER_PASSWORD") : System.getProperty("datahub.test.sqlserver.password"));
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(30);
            sql.execute("CREATE SCHEMA " + schema);
            sql.execute("CREATE TABLE " + schema + ".citizen(id int, name nvarchar(64), secret nvarchar(64))");
            sql.execute("INSERT INTO " + schema + ".citizen VALUES (1,N'row',N's1'),(2,N'row',N's2')");
        }
    }

    @AfterEach
    void drop() throws Exception {
        if (connection == null) return;
        try (var owned = connection; var sql = owned.createStatement()) {
            sql.execute("SET QUOTED_IDENTIFIER ON");
            sql.execute("DROP TABLE IF EXISTS " + schema + ".citizen");
            sql.execute("DROP SCHEMA IF EXISTS " + schema);
        }
    }

    @Test
    void acceptedProjectionsNeverReachUnapprovedColumns() throws Exception {
        String table = "\"" + schema + "\".citizen";
        int accepted = RestrictedProjectionDifferential.run(ProductTypeEnum.SQLSERVER, connection, schema, "citizen",
                List.of("SET QUOTED_IDENTIFIER ON"),
                List.of("select id, name from " + table,
                        "select id, name from " + table + " where name = 'row' order by name desc",
                        "select top 1 id from " + table + " order by id"));
        assertEquals(0, accepted);
    }
}

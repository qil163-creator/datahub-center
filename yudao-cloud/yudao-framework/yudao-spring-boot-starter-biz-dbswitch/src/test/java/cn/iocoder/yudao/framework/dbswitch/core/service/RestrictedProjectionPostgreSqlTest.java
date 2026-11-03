package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** #17 PostgreSQL 差异验证：两种字符串转义语义下，受限投影输出都不能借字面值/注释触达未批准列。 */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class RestrictedProjectionPostgreSqlTest {
    private Connection connection;
    private String schema;

    @BeforeEach
    void create() throws Exception {
        schema = RestrictedProjectionDifferential.schemaName("q17_");
        connection = RestrictedProjectionDifferential.open(System.getProperty("datahub.test.postgres.url"),
                System.getProperty("datahub.test.postgres.user", "postgres"));
        try (var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            sql.execute("CREATE TABLE " + schema + ".citizen(id int, name text, secret text)");
            sql.execute("INSERT INTO " + schema + ".citizen VALUES (1,'row','s1'),(2,'row','s2')");
        }
    }

    @AfterEach
    void drop() throws Exception {
        if (connection == null) return;
        try (var owned = connection; var sql = owned.createStatement()) {
            sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void acceptedProjectionsNeverReachUnapprovedColumnsUnderEitherStringMode() throws Exception {
        int accepted = RestrictedProjectionDifferential.run(ProductTypeEnum.POSTGRESQL, connection, schema, "citizen",
                List.of("SET standard_conforming_strings = on", "SET standard_conforming_strings = off"));
        // 加固后所有越权输入都在授权前被拒绝；合法查询的真实执行覆盖由 run(...) 内部断言。
        assertEquals(0, accepted);
    }
}

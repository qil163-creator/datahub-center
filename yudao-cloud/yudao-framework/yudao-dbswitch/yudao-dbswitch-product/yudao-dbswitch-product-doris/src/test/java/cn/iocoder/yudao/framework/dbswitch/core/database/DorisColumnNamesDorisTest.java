package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDorisImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.*;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Physical names are exact values, never JDBC wildcard patterns or case-insensitive aliases. */
@Timeout(60)
@EnabledIfSystemProperty(named = "datahub.test.doris.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
class DorisColumnNamesDorisTest {
    @ParameterizedTest @ValueSource(strings = {"ordinary", "wild_%_name", "literal'quote", "back`tick\"name", "MixedCase"})
    void exactPhysicalNamesNeverExpandAsJdbcPatternsOrLoseQuotedCharacters(String name) throws Exception {
        String schema = "dm74_columns_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = DriverManager.getConnection(System.getProperty("datahub.test.doris.url"), "root", "")) {
            try (var s = c.createStatement(); var r = s.executeQuery("SHOW VARIABLES LIKE 'version_comment'")) {
                assertTrue(r.next()); assertTrue(r.getString(2).contains("doris-4.0.8"));
            }
            sql(c, "CREATE DATABASE " + q(schema));
            try {
                sql(c, "CREATE TABLE " + q(schema) + "." + q(name) + "(id BIGINT NOT NULL,label VARCHAR(40),amount DECIMAL(10,2))" + model("id"),
                        "CREATE TABLE " + q(schema) + "." + q("wild_otherXname") + "(decoy INT NOT NULL)" + model("decoy"));
                var product = new DatabaseDorisImpl();
                assertEquals(Set.of("id", "label", "amount"), new HashSet<>(product.queryTableColumnName(c, schema, name)));
                assertTrue(product.queryTableColumnName(c, schema, "missing_%_name").isEmpty());
                if (name.equals("MixedCase")) assertTrue(product.queryTableColumnName(c, schema, "mixedcase").isEmpty());
                assertTrue(c.getAutoCommit()); assertFalse(c.isClosed(), "The caller retains connection ownership");
            } finally { sql(c, "DROP DATABASE IF EXISTS " + q(schema)); }
        }
    }
    private static String model(String key) { return " ENGINE=OLAP DUPLICATE KEY(" + key + ") DISTRIBUTED BY HASH(" + key + ") BUCKETS 1 PROPERTIES('replication_num'='1')"; }
    private static String q(String name) { return '`' + name.replace("`", "``") + '`'; }
    private static void sql(Connection c, String... statements) throws SQLException { try (var s = c.createStatement()) { s.setQueryTimeout(30); for (String text : statements) s.execute(text); } }
}

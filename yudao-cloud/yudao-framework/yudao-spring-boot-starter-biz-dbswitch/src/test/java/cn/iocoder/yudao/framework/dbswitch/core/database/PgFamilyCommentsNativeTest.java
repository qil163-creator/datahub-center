package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.driver.VerifiedDriverTestHandle;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Native vendor engines: emitted comments must preserve text regardless of the string-literal mode. */
@Timeout(45)
class PgFamilyCommentsNativeTest {
    @Test
    @EnabledIfSystemProperty(named = "datahub.test.opengauss.url", matches = "jdbc:opengauss://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_OPENGAUSS_TEST_PASSWORD", matches = ".+")
    void opengaussCommentsKeepQuotedIdentifiersAndExactText() throws Exception {
        roundTrip(ProductTypeEnum.OPENGAUSS, "opengauss", "dataforge_test", "pg", "openGauss 7.0.0-RC3");
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.greenplum.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_GREENPLUM_TEST_PASSWORD", matches = ".+")
    void greenplumCommentsKeepQuotedIdentifiersAndExactText() throws Exception {
        roundTrip(ProductTypeEnum.GREENPLUM, "greenplum", "gpadmin", "pg", "Greenplum Database 7.1.0");
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.highgo.url", matches = "jdbc:highgo://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_HIGHGO_TEST_PASSWORD", matches = ".+")
    void highgoCommentsKeepQuotedIdentifiersAndExactText() throws Exception {
        roundTrip(ProductTypeEnum.HIGHGO, "highgo", "highgo", "pg", "HighGo Database 6 Release 6.0.4.2");
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.kingbase.url", matches = "jdbc:kingbase8://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_KINGBASE_TEST_PASSWORD", matches = ".+")
    void kingbaseOracleCommentsKeepQuotedIdentifiersAndExactText() throws Exception {
        roundTrip(ProductTypeEnum.KINGBASE, "kingbase", "system", "pg", "KingbaseES V008R006C009B0014");
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.uxdb.url", matches = "jdbc:uxdb://127[.]0[.]0[.]1:.*")
    @EnabledIfEnvironmentVariable(named = "DATAHUB_UXDB_TEST_PASSWORD", matches = ".+")
    void uxdbCommentsKeepQuotedIdentifiersAndExactText() throws Exception {
        roundTrip(ProductTypeEnum.UXDB, "uxdb", "uxdb", "ux", "UXsinoDB 2.1.0.3");
    }

    private static void roundTrip(ProductTypeEnum product, String property, String user, String catalogPrefix,
                                  String version) throws Exception {
        String schema = "comments88_" + UUID.randomUUID().toString().replace("-", "") + "\"";
        String tableName = "table\"'--";
        String columnName = "column\"'--";
        String tableComment = "表 'single' \"double\" \\n literal slash\nreal line; --";
        String columnComment = "列 '); DROP TABLE sentinel; -- \\\" end";
        var table = new TableDescription();
        table.setSchemaName(schema); table.setTableName(tableName); table.setRemarks(tableComment);
        var column = new ColumnDescription();
        column.setFieldName(columnName); column.setRemarks(columnComment);
        try (var driver = new VerifiedDriverTestHandle(
                Path.of(System.getProperty("datahub.test." + property + ".driver")), product)) {
            var source = driver.dataSource(System.getProperty("datahub.test." + property + ".url"),
                    product.getDriveClassName(), user, System.getenv("DATAHUB_" + property.toUpperCase(java.util.Locale.ROOT)
                            + "_TEST_PASSWORD"), new Properties());
            try (var connection = source.getConnection()) {
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT version()")) {
                    assertTrue(rows.next()); assertTrue(rows.getString(1).contains(version));
                }
                var metadata = DbSwitchProductRegistry.discover().metadata(product);
                metadata.prepareDialect(connection);
                execute(connection, "CREATE SCHEMA " + quote(schema));
                try {
                    execute(connection, "CREATE TABLE " + quote(schema) + "." + quote(tableName)
                            + "(" + quote(columnName) + " INTEGER)");
                    for (String mode : List.of("on", "off")) {
                        execute(connection, "SET standard_conforming_strings=" + mode);
                        var statements = metadata.getTableColumnCommentDefinition(table, List.of(column));
                        assertEquals(2, statements.size());
                        for (String statement : statements) execute(connection, statement);
                        String query = "SELECT d.description FROM " + catalogPrefix + "_catalog." + catalogPrefix
                                + "_description d JOIN " + catalogPrefix + "_catalog." + catalogPrefix
                                + "_class c ON c.oid=d.objoid JOIN " + catalogPrefix + "_catalog." + catalogPrefix
                                + "_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=? AND d.objsubid=?";
                        try (var statement = connection.prepareStatement(query)) {
                            statement.setString(1, schema); statement.setString(2, tableName);
                            for (int ordinal = 0; ordinal <= 1; ordinal++) {
                                statement.setInt(3, ordinal);
                                try (var rows = statement.executeQuery()) {
                                    assertTrue(rows.next());
                                    assertEquals(ordinal == 0 ? tableComment : columnComment, rows.getString(1), mode);
                                    assertFalse(rows.next());
                                }
                            }
                        }
                    }
                } finally { execute(connection, "DROP SCHEMA " + quote(schema) + " CASCADE"); }
            }
        }
    }

    private static String quote(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }
    private static void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) { statement.setQueryTimeout(10); statement.execute(sql); }
    }
}

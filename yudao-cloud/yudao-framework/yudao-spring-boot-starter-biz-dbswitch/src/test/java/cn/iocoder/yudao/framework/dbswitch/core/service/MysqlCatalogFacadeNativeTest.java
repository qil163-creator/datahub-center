package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MysqlCatalogFacadeNativeTest {
    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"MYSQL", "MARIADB"})
    void returnsOnlyExactCatalogTablesAndViewsOrFailsOnBudget(ProductTypeEnum product) throws Exception {
        String prefix = "datahub.test." + product.name().toLowerCase(Locale.ROOT);
        String url = System.getProperty(prefix + ".url");
        assumeTrue(url != null && url.matches("jdbc:(mysql|mariadb)://127[.]0[.]0[.]1:[0-9]+/.*"));
        String user = System.getProperty(prefix + ".user", "root");
        String password = System.getenv("DATAHUB_MYSQL_CATALOG_TEST_PASSWORD");
        if (password == null) password = "";
        String schema = "catalog_" + UUID.randomUUID().toString().replace("-", "");
        String neighbor = schema.replace('_', 'x');
        var owned = new ArrayList<java.sql.Connection>();
        var facade = new MetaDataByJdbcServiceImpl(product, DbSwitchProductRegistry.discover(), (u, n, p) -> {
            var connection = DriverManager.getConnection(u, n, p); owned.add(connection); return connection;
        });
        try (var connection = DriverManager.getConnection(url, user, password); var sql = connection.createStatement()) {
            sql.setQueryTimeout(10);
            sql.execute("CREATE DATABASE `" + schema + "`");
            try {
                sql.execute("CREATE DATABASE `" + neighbor + "`");
                sql.execute("CREATE TABLE `" + schema + "`.`orders`(id INTEGER)");
                sql.execute("CREATE VIEW `" + schema + "`.`order_view` AS SELECT id FROM `" + schema + "`.`orders`");
                sql.execute("CREATE TABLE `" + neighbor + "`.`must_not_leak`(id INTEGER)");
                var tables = facade.queryExactTableList(url, user, password, schema, 2, 10000);
                assertEquals(List.of("order_view", "orders"), tables.stream().map(TableDescription::getTableName).toList());
                assertTrue(tables.stream().allMatch(table -> schema.equals(table.getSchemaName())));
                assertEquals(List.of("VIEW", "TABLE"), tables.stream().map(TableDescription::getTableType).toList());
                final String secret = password;
                assertThrows(IllegalArgumentException.class,
                        () -> facade.queryExactTableList(url, user, secret, schema, 1, 10000));
                // Catalog comparisons can be case insensitive; aliases must not become authorization keys.
                try {
                    assertTrue(facade.queryExactTableList(url, user, password,
                            schema.toUpperCase(Locale.ROOT), 2, 10000).isEmpty());
                } catch (IllegalStateException rejectedAlias) {
                    var cause = assertInstanceOf(java.sql.SQLFeatureNotSupportedException.class, rejectedAlias.getCause());
                    assertEquals("0A000", cause.getSQLState());
                }
                for (var resource : owned) assertTrue(resource.isClosed());
                assertTrue(connection.getAutoCommit());
            } finally {
                sql.execute("DROP DATABASE IF EXISTS `" + neighbor + "`");
                sql.execute("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }
}

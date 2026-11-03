package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.DriverManager;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MysqlTemporalDefinitionNativeTest {
    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"MYSQL", "MARIADB"})
    void generatedDdlPreservesAllSupportedFractionsInTheRealServer(ProductTypeEnum product) throws Exception {
        String prefix = "datahub.test." + product.name().toLowerCase(Locale.ROOT);
        String url = System.getProperty(prefix + ".url");
        assumeTrue(url != null && url.matches("jdbc:(mysql|mariadb)://127[.]0[.]0[.]1:[0-9]+/.*"));
        String password = System.getenv("DATAHUB_MYSQL_CATALOG_TEST_PASSWORD");
        String schema = "temporal_" + UUID.randomUUID().toString().replace("-", "");
        var dialect = DbSwitchProductRegistry.discover().metadata(product);
        try (var connection = DriverManager.getConnection(url, System.getProperty(prefix + ".user", "root"),
                password == null ? "" : password); var sql = connection.createStatement()) {
            sql.setQueryTimeout(10);
            sql.execute("CREATE DATABASE `" + schema + "`");
            try {
                for (int scale = 0; scale <= 6; scale++) {
                    var column = new ColumnDescription();
                    column.setFieldName("seen"); column.setFieldType(Types.TIMESTAMP); column.setFieldTypeName("TIMESTAMP");
                    column.setScaleSize(scale); column.setNullable(true);
                    column.setDefaultValue("CURRENT_TIMESTAMP");
                    String table = "`" + schema + "`.`fraction_" + scale + "`";
                    sql.execute("CREATE TABLE " + table + "(" + dialect.getFieldDefinition(new ColumnMetaData(column),
                            List.of(), false, false, false) + ")");
                    int nanos = scale == 0 ? 0 : Integer.parseInt("123456".substring(0, scale) + "0".repeat(9 - scale));
                    var value = LocalDateTime.of(2026, 10, 8, 12, 34, 56, nanos);
                    try (var insert = connection.prepareStatement("INSERT INTO " + table + " VALUES (?)")) {
                        insert.setQueryTimeout(10); insert.setTimestamp(1, Timestamp.valueOf(value)); insert.executeUpdate();
                    }
                    try (var rows = sql.executeQuery("SELECT seen FROM " + table)) {
                        assertTrue(rows.next());
                        assertEquals(value, rows.getTimestamp(1).toLocalDateTime()); assertFalse(rows.next());
                    }
                    // Connector/J reports zero ResultSet scale for DATETIME(p); query the native declaration.
                    try (var metadata = connection.prepareStatement("SELECT DATETIME_PRECISION,COLUMN_DEFAULT FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND COLUMN_NAME='seen'")) {
                        metadata.setQueryTimeout(10); metadata.setString(1, schema);
                        metadata.setString(2, "fraction_" + scale);
                        try (var rows = metadata.executeQuery()) {
                            assertTrue(rows.next()); assertEquals(scale, rows.getInt(1));
                            assertEquals(scale == 0 ? "current_timestamp" : "current_timestamp(" + scale + ")",
                                    rows.getString(2).toLowerCase(Locale.ROOT).replace("()", ""));
                            assertFalse(rows.next());
                        }
                    }
                    sql.executeUpdate("INSERT INTO " + table + " VALUES(DEFAULT)");
                    try (var rows = sql.executeQuery("SELECT COUNT(*),COUNT(seen) FROM " + table)) {
                        assertTrue(rows.next()); assertEquals(2, rows.getInt(1)); assertEquals(2, rows.getInt(2));
                    }
                }
                assertTrue(connection.getAutoCommit());
            } finally {
                sql.execute("DROP DATABASE `" + schema + "`");
            }
        }
    }
}

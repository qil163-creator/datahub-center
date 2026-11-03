package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMariaDBImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Types;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.mariadb.url", matches = "jdbc:mariadb://127[.]0[.]0[.]1:.*")
class MariaDbCatalogMetadataMariaDbTest {
    private final DatabaseMariaDBImpl database = new DatabaseMariaDBImpl();
    private Connection connection;
    private String schema;
    private String other;

    @BeforeEach
    void createOwnedCatalogs() throws Exception {
        schema = "catalog_parity_" + UUID.randomUUID().toString().replace("-", "");
        other = schema + "_other";
        connection = DriverManager.getConnection(System.getProperty("datahub.test.mariadb.url"),
                System.getProperty("datahub.test.mariadb.user", "root"),
                System.getProperty("datahub.test.mariadb.password", ""));
        try (var sql = connection.createStatement()) {
            sql.execute("CREATE DATABASE `" + schema + "`");
            sql.execute("CREATE DATABASE `" + other + "`");
            sql.execute("CREATE TABLE `" + schema + "`.`orders_%` (z_id INT, a_label VARCHAR(20)) ENGINE=InnoDB");
            sql.execute("CREATE TABLE `" + schema + "`.`orders_A` (wrong_pattern_column INT) ENGINE=InnoDB");
            sql.execute("CREATE TABLE `" + other + "`.`orders_%` (wrong_catalog_column INT) ENGINE=InnoDB");
            sql.execute("CREATE VIEW `" + schema + "`.`a_view` AS SELECT z_id FROM `" + schema + "`.`orders_%`");
        }
        connection.setCatalog(other);
    }

    @AfterEach
    void dropOnlyOwnedCatalogs() throws Exception {
        if (connection == null) return;
        try (var owned = connection) {
            if (!owned.getAutoCommit()) owned.rollback();
            owned.setAutoCommit(true);
            owned.setCatalog("information_schema");
            try (var cleanup = owned.createStatement()) {
                if (other != null) cleanup.execute("DROP DATABASE IF EXISTS `" + other + "`");
                if (schema != null) cleanup.execute("DROP DATABASE IF EXISTS `" + schema + "`");
            }
        }
    }

    @Test
    void inheritedCommentsPreserveQuotesWithNativeDriverInBothEscapeModes() throws Exception {
        String comment = "中文 O'Reilly \"quoted\"\n'; DROP TABLE other; --";
        var column = new ColumnDescription();
        column.setFieldName("id"); column.setFieldType(Types.INTEGER); column.setPrecisionSize(9);
        column.setScaleSize(0); column.setNullable(true); column.setRemarks(comment);
        try (var sql = connection.createStatement()) {
            int number = 0;
            for (String mode : List.of("STRICT_ALL_TABLES", "STRICT_ALL_TABLES,ANSI_QUOTES,NO_BACKSLASH_ESCAPES")) {
                sql.execute("SET SESSION sql_mode='" + mode + "'");
                String table = "comment_" + number++;
                sql.execute(cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils.getDDLCreateTableSQL(
                        database, List.of(column), List.of(), schema, table, true, comment, false, java.util.Map.of()));
                assertEquals(comment, database.queryTableList(connection, schema).stream()
                        .filter(t -> t.getTableName().equals(table)).findFirst().orElseThrow().getRemarks());
                assertEquals(comment, database.queryTableColumnMeta(connection, schema, table).getFirst().getRemarks());
            }
        }
    }

    @Test
    void inheritedCatalogMetadataUsesNativeMariaDbDriverAcrossCatalogs() throws Exception {
        assertTrue(database.querySchemaList(connection).containsAll(List.of(schema, other)));
        assertEquals(List.of("z_id", "a_label"), database.queryTableColumnName(connection, schema, "orders_%"));
        assertTrue(database.queryTableColumnName(connection, schema, "missing_%").isEmpty());
        assertEquals(List.of("a_view", "orders_%", "orders_A"), database.queryCatalogTableList(connection, schema, 3, 5)
                .stream().map(TableDescription::getTableName).toList());
        assertEquals("VIEW", database.queryCatalogTableList(connection, schema, 3, 5).getFirst().getTableType());
        assertEquals(other, connection.getCatalog());
        assertFalse(connection.isClosed());
    }

    @Test
    void inheritedPickerRejectsTruncationAndBindsSchemaLiterals() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> database.queryCatalogTableList(connection, schema, 2, 5));
        assertTrue(database.queryTableList(connection, schema + "' OR '1'='1").isEmpty());
        assertEquals(other, connection.getCatalog());
    }

    @Test
    void inheritedIntegerPrecisionHoldsTenAndElevenDigitValues() throws Exception {
        try (var sql = connection.createStatement()) {
            for (int digits : new int[]{10, 11}) {
                var column = new ColumnDescription();
                column.setDbType(ProductTypeEnum.POSTGRESQL);
                column.setFieldName("amount"); column.setFieldType(Types.NUMERIC);
                column.setPrecisionSize(digits); column.setScaleSize(0); column.setNullable(true);
                String table = "`" + schema + "`.`integer_" + digits + "`";
                sql.execute("CREATE TABLE " + table + "(" + database.getFieldDefinition(
                        new ColumnMetaData(column), List.of(), false, false, false) + ")");
                var positive = new BigDecimal("9".repeat(digits));
                try (var insert = connection.prepareStatement("INSERT INTO " + table + " VALUES(?),(?)")) {
                    insert.setBigDecimal(1, positive); insert.setBigDecimal(2, positive.negate());
                    assertEquals(2, insert.executeUpdate());
                }
                try (var rows = sql.executeQuery("SELECT amount FROM " + table + " ORDER BY amount")) {
                    assertTrue(rows.next()); assertEquals(positive.negate(), rows.getBigDecimal(1));
                    assertTrue(rows.next()); assertEquals(positive, rows.getBigDecimal(1));
                    assertFalse(rows.next());
                }
            }
        }
    }


    @Test
    void bothColumnMetadataPathsRebuildTimestampPrecisionAcrossCatalogs() throws Exception {
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(10);
            for (int scale = 0; scale <= 6; scale++) {
                String sourceName = "temporal_source_" + scale;
                String source = q(schema) + "." + q(sourceName);
                sql.execute("CREATE TABLE " + source + "(id INT, seen DATETIME(" + scale
                        + "), instant TIMESTAMP(" + scale + ") NULL)");
                int nanos = scale == 0 ? 0 : Integer.parseInt("123456".substring(0, scale) + "0".repeat(9 - scale));
                var value = java.time.LocalDateTime.of(2026, 10, 8, 12, 34, 56, nanos);
                try (var insert = connection.prepareStatement("INSERT INTO " + source + " VALUES(1,?,?)")) {
                    insert.setTimestamp(1, java.sql.Timestamp.valueOf(value));
                    insert.setTimestamp(2, java.sql.Timestamp.valueOf(value));
                    assertEquals(1, insert.executeUpdate());
                }
                for (boolean fullMetadata : new boolean[]{false, true}) {
                    var columns = fullMetadata ? database.queryTableColumnMeta(connection, schema, sourceName)
                            : database.queryTableColumnMetaOnly(connection, schema, sourceName);
                    assertEquals(List.of("id", "seen", "instant"), columns.stream().map(ColumnDescription::getFieldName).toList());
                    assertEquals(scale, columns.get(1).getScaleSize());
                    assertEquals(scale, columns.get(2).getScaleSize());
                    String cloneName = "temporal_clone_" + scale + "_" + fullMetadata;
                    String clone = q(schema) + "." + q(cloneName);
                    String definitions = columns.stream().map(column -> database.getFieldDefinition(
                                    new ColumnMetaData(column), List.of(), false, false, false))
                            .collect(java.util.stream.Collectors.joining(","));
                    sql.execute("CREATE TABLE " + clone + "(" + definitions + ")");
                    try (var precision = connection.prepareStatement("SELECT DATETIME_PRECISION FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND COLUMN_NAME IN ('seen','instant') ORDER BY ORDINAL_POSITION")) {
                        precision.setString(1, schema); precision.setString(2, cloneName);
                        try (var rows = precision.executeQuery()) {
                            assertTrue(rows.next()); assertEquals(scale, rows.getInt(1)); assertFalse(rows.wasNull());
                            assertTrue(rows.next()); assertEquals(scale, rows.getInt(1)); assertFalse(rows.wasNull());
                            assertFalse(rows.next());
                        }
                    }
                    try (var rows = sql.executeQuery("SELECT id,seen,instant FROM " + source);
                         var insert = connection.prepareStatement("INSERT INTO " + clone + " VALUES(?,?,?)")) {
                        assertTrue(rows.next());
                        insert.setInt(1, rows.getInt(1));
                        insert.setTimestamp(2, rows.getTimestamp(2)); insert.setTimestamp(3, rows.getTimestamp(3));
                        assertEquals(1, insert.executeUpdate());
                    }
                    try (var rows = sql.executeQuery("SELECT seen,instant FROM " + clone)) {
                        assertTrue(rows.next());
                        assertEquals(value, rows.getTimestamp(1).toLocalDateTime());
                        assertEquals(value, rows.getTimestamp(2).toLocalDateTime());
                        assertFalse(rows.next());
                    }
                    assertEquals(other, connection.getCatalog());
                    assertTrue(connection.getAutoCommit());
                }
            }
        }
    }

    @Test
    void inheritedMetadataDoesNotCommitCallerTransaction() throws Exception {
        connection.setAutoCommit(false);
        try (var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO `" + other + "`.`orders_%` VALUES(17)");
            database.querySchemaList(connection);
            database.queryTableColumnName(connection, schema, "orders_%");
            database.queryCatalogTableList(connection, schema, 3, 5);
            assertFalse(connection.getAutoCommit());
            assertEquals(other, connection.getCatalog());
            connection.rollback();
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM `" + other + "`.`orders_%`")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
        }
    }
    private static String q(String value) { return "`" + value.replace("`", "``") + "`"; }
}

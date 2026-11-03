package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.driver.VerifiedDriverTestHandle;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Explicitly selected original vendor fixtures; a passed product does not certify another version or table model. */
@Timeout(180)
@EnabledIfSystemProperty(named = "datahub.test.alter.products", matches = ".+")
class AddColumnNativeMatrixTest {
    private static Stream<ProductTypeEnum> products() {
        return Arrays.stream(System.getProperty("datahub.test.alter.products").split(","))
                .map(String::trim).map(ProductTypeEnum::valueOf);
    }

    @ParameterizedTest(name = "{0}: nullable ADD and exact physical names") @MethodSource("products")
    void nullableAddPreservesExistingValuesAndAcceptsQuotedColumns(ProductTypeEnum product) throws Exception {
        try (var fixture = Fixture.open(product)) {
            var dialect = DbSwitchProductRegistry.discover().metadata(product);
            if (dialect.requiresDialectConnection()) dialect.prepareDialect(fixture.connection);
            String special = switch (product) {
                case MYSQL, MARIADB, TIDB, GBASE8A, HIVE, OCEANBASE -> "part`quote";
                case SQLSERVER, SQLSERVER2000, SYBASE -> "part]quote";
                default -> "part\"quote";
            };
            int added = 0;
            for (String field : List.of("added", "MixedCase", "Mixed Name", special)) {
                try { fixture.q(field); }
                catch (IllegalArgumentException outsideNameProfile) {
                    assertThrows(IllegalArgumentException.class, () -> dialect.getAddColumnSql(fixture.schema, fixture.table, column(product, field)));
                    assertEquals(fixture.baseColumns() + added, fixture.columnCount());
                    continue;
                }
                String sql = dialect.getAddColumnSql(fixture.schema, fixture.table, column(product, field));
                assertNotNull(sql); assertTrue(sql.toUpperCase(Locale.ROOT).startsWith("ALTER TABLE "));
                fixture.execute(sql);
                try (var statement = fixture.connection.createStatement(); var rows = statement.executeQuery(
                        "SELECT " + fixture.q("id") + "," + fixture.q("note") + "," + fixture.q(field) + " FROM " + fixture.relation())) {
                    assertTrue(rows.next()); assertEquals(7, rows.getInt(1)); assertEquals("sentinel", rows.getString(2));
                    assertNull(rows.getObject(3)); assertFalse(rows.next());
                }
                if (!Set.of(ProductTypeEnum.HIVE, ProductTypeEnum.CLICKHOUSE, ProductTypeEnum.DORIS,
                        ProductTypeEnum.STARROCKS, ProductTypeEnum.TDENGINE).contains(product)) {
                    fixture.execute("UPDATE " + fixture.relation() + " SET " + fixture.q(field) + "=42");
                    assertEquals(42, fixture.oneInt("SELECT " + fixture.q(field) + " FROM " + fixture.relation()));
                }
                added++;
            }
            assertEquals(fixture.baseColumns() + added, fixture.columnCount());
        }
    }

    @ParameterizedTest(name = "{0}: refused alterations preserve table and sentinel") @MethodSource("products")
    void unsupportedFeaturesAreRejectedBeforeAnySqlCanExecute(ProductTypeEnum product) throws Exception {
        try (var fixture = Fixture.open(product)) {
            var dialect = DbSwitchProductRegistry.discover().metadata(product);
            if (dialect.requiresDialectConnection()) dialect.prepareDialect(fixture.connection);
            var required = column(product, "rejected"); required.setNullable(false);
            var key = column(product, "rejected"); key.setPk(true);
            var defaulted = column(product, "rejected"); defaulted.setDefaultValue("7");
            var identity = column(product, "rejected"); identity.setAutoIncrement(true);
            var unknown = column(product, "rejected"); unknown.setFieldType(Integer.MIN_VALUE); unknown.setFieldTypeName("UNVERIFIED_TYPE");
            for (var addition : List.of(required, key, defaulted, identity, unknown)) {
                var refused = assertThrows(Exception.class, () -> dialect.getAddColumnSql(fixture.schema, fixture.table, addition),
                        "Unsupported alteration must not emit SQL for " + product + "; jdbcType=" + addition.getFieldType()
                                + "; nullable=" + addition.isNullable() + "; key=" + addition.isPk()
                                + "; identity=" + addition.isAutoIncrement() + "; default=" + (addition.getDefaultValue() != null));
                assertTrue(refused instanceof SQLFeatureNotSupportedException || refused instanceof IllegalArgumentException
                        || refused instanceof UnsupportedOperationException, "Expected an explicit unsupported-input contract, got " + refused.getClass());
                assertEquals(fixture.baseColumns(), fixture.columnCount());
                assertEquals(7, fixture.oneInt("SELECT " + fixture.q("id") + " FROM " + fixture.relation()));
            }
        }
    }

    private static ColumnDescription column(ProductTypeEnum product, String name) {
        var column = new ColumnDescription(); column.setDbType(product); column.setFieldName(name);
        column.setFieldType(Types.INTEGER); column.setFieldTypeName("INTEGER"); column.setFiledTypeClassName(Integer.class.getName());
        column.setPrecisionSize(9); column.setScaleSize(0); column.setNullable(true); column.setSigned(true);
        return column;
    }

    private static final class Fixture implements AutoCloseable {
        final ProductTypeEnum product;
        final Connection connection;
        final VerifiedDriverTestHandle driver;
        final String schema, table;
        final boolean ownNamespace, databaseNamespace;
        boolean tableCreated, namespaceCreated;

        private Fixture(ProductTypeEnum product, Connection connection, VerifiedDriverTestHandle driver, String schema,
                        String table, boolean ownNamespace, boolean databaseNamespace) {
            this.product = product; this.connection = connection; this.driver = driver; this.schema = schema;
            this.table = table; this.ownNamespace = ownNamespace; this.databaseNamespace = databaseNamespace;
        }

        static Fixture open(ProductTypeEnum product) throws Exception {
            String key = switch (product) { case POSTGRESQL -> "postgres"; case SQLITE3 -> "sqlite"; default -> product.name().toLowerCase(Locale.ROOT); };
            String prefix = "datahub.test." + key + ".";
            String url = System.getProperty(prefix + "url", product == ProductTypeEnum.SQLITE3 ? "jdbc:sqlite::memory:" : "");
            assertFalse(url.isBlank(), "Selected native ALTER product requires its real URL: " + product);
            String user = System.getProperty(prefix + "user", "");
            String password = System.getProperty(prefix + "password", System.getenv().getOrDefault("DATAHUB_" + key.toUpperCase(Locale.ROOT) + "_TEST_PASSWORD", ""));
            String artifact = System.getProperty(prefix + "driver");
            VerifiedDriverTestHandle driver = artifact == null ? null : new VerifiedDriverTestHandle(Path.of(artifact), product);
            Connection connection = driver == null ? DriverManager.getConnection(url, user, password)
                    : driver.dataSource(url, product.getDriveClassName(), user, password, new Properties()).getConnection();
            String unique = "add88_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            boolean database = Set.of(ProductTypeEnum.MYSQL, ProductTypeEnum.MARIADB, ProductTypeEnum.TIDB, ProductTypeEnum.GBASE8A,
                    ProductTypeEnum.HIVE, ProductTypeEnum.OCEANBASE, ProductTypeEnum.CLICKHOUSE, ProductTypeEnum.DORIS, ProductTypeEnum.STARROCKS,
                    ProductTypeEnum.TDENGINE).contains(product);
            boolean namespace = database || Set.of(ProductTypeEnum.POSTGRESQL, ProductTypeEnum.GREENPLUM, ProductTypeEnum.HIGHGO,
                    ProductTypeEnum.KINGBASE, ProductTypeEnum.UXDB, ProductTypeEnum.OPENGAUSS).contains(product);
            String schema = namespace ? unique : switch (product) {
                case SQLITE3 -> "main";
                case SYBASE, SQLSERVER, SQLSERVER2000 -> "dbo";
                default -> System.getProperty(prefix + "schema", user.toUpperCase(Locale.ROOT));
            };
            var fixture = new Fixture(product, connection, driver, schema, "t_" + unique, namespace, database);
            try {
                if (namespace) {
                    fixture.execute("CREATE " + (database ? "DATABASE " : "SCHEMA ") + fixture.q(schema));
                    fixture.namespaceCreated = true;
                }
                if (product == ProductTypeEnum.SYBASE) fixture.execute("SET quoted_identifier ON");
                String sql = "CREATE TABLE " + fixture.relation() + " (" + fixture.q("id") + " INTEGER NOT NULL, " + fixture.q("note") + " VARCHAR(40))";
                if (product == ProductTypeEnum.GREENPLUM) sql += " DISTRIBUTED BY (" + fixture.q("id") + ")";
                if (product == ProductTypeEnum.CLICKHOUSE) sql += " ENGINE=MergeTree ORDER BY " + fixture.q("id");
                if (product == ProductTypeEnum.DORIS || product == ProductTypeEnum.STARROCKS)
                    sql += " DUPLICATE KEY(" + fixture.q("id") + ") DISTRIBUTED BY HASH(" + fixture.q("id")
                            + ") BUCKETS 1 PROPERTIES(\"replication_num\"=\"1\")";
                if (product == ProductTypeEnum.TDENGINE) sql = "CREATE TABLE " + fixture.relation() + " (ts TIMESTAMP, "
                        + fixture.q("id") + " INT, " + fixture.q("note") + " BINARY(40))";
                if (product == ProductTypeEnum.HIVE) sql = sql.replace("CREATE TABLE", "CREATE EXTERNAL TABLE").replace(" INTEGER NOT NULL", " INTEGER")
                        + " STORED AS TEXTFILE TBLPROPERTIES ('external.table.purge'='true')";
                fixture.execute(sql); fixture.tableCreated = true;
                fixture.execute("INSERT INTO " + fixture.relation() + " VALUES ("
                        + (product == ProductTypeEnum.TDENGINE ? "'2024-01-01 00:00:00.000'," : "") + "7,'sentinel')");
                return fixture;
            } catch (Exception failure) { fixture.close(); throw failure; }
        }

        String q(String name) { return RestrictedSelectGuard.quotePhysicalIdentifier(product, name); }
        int baseColumns() { return product == ProductTypeEnum.TDENGINE ? 3 : 2; }
        String relation() { return q(schema) + "." + q(table); }
        void execute(String sql) throws SQLException {
            try (var statement = connection.createStatement()) { statement.setQueryTimeout(60); statement.execute(sql); }
        }
        int oneInt(String sql) throws SQLException {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
                assertTrue(rows.next()); int result = rows.getInt(1); assertFalse(rows.next()); return result;
            }
        }
        int columnCount() throws SQLException {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM " + relation() + " WHERE 1=0")) {
                return rows.getMetaData().getColumnCount();
            }
        }
        @Override public void close() throws Exception {
            try {
                try { if (tableCreated) execute("DROP TABLE " + relation()); }
                finally { if (namespaceCreated) execute("DROP " + (databaseNamespace ? "DATABASE " : "SCHEMA ") + q(schema)); }
            } finally { try { connection.close(); } finally { if (driver != null) driver.close(); } }
        }
    }
}

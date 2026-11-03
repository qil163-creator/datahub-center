package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.data.util.ContentDigests;
import cn.iocoder.yudao.framework.dbswitch.data.util.MigrationWriteModes;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T05–T12：PostgreSQL→PostgreSQL 严格复制。仅连接本测试容器。 */
@Testcontainers(disabledWithoutDocker = true)
class StrictCopyPostgreSqlTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void createNewCopiesToIndependentTargetsWithoutTouchingSourceOrPriorArchive() throws Exception {
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            sql.execute("DROP SCHEMA IF EXISTS src CASCADE");
            sql.execute("DROP SCHEMA IF EXISTS dst CASCADE");
            sql.execute("CREATE SCHEMA src");
            sql.execute("CREATE SCHEMA dst");
            sql.execute("""
                    CREATE TABLE src.orders(
                      id int PRIMARY KEY,
                      amount numeric(12,2),
                      note text,
                      paid_at timestamptz
                    )
                    """);
            sql.execute("INSERT INTO src.orders VALUES (1, 1.10, 'a', '2024-01-01 00:00:00+00')");
            sql.execute("INSERT INTO src.orders VALUES (2, 1.10, 'a', '2024-01-01 00:00:00+00')");
            sql.execute("INSERT INTO src.orders VALUES (3, NULL, NULL, NULL)");
            String sourceDigest = digest(connection, "SELECT id,amount,note,paid_at FROM src.orders");

            copy("orders", "orders_1_arc");
            assertThat(count(sql, "dst.orders_1_arc")).isEqualTo(3);
            assertThat(digest(connection, "SELECT id,amount,note,paid_at FROM dst.orders_1_arc")).isEqualTo(sourceDigest);
            assertThat(digest(connection, "SELECT id,amount,note,paid_at FROM src.orders")).isEqualTo(sourceDigest);

            sql.execute("INSERT INTO src.orders VALUES (4, 2.00, 'new', '2024-02-01 00:00:00+00')");
            copy("orders", "orders_2_arc");
            assertThat(count(sql, "dst.orders_2_arc")).isEqualTo(4);
            assertThat(digest(connection, "SELECT id,amount,note,paid_at FROM dst.orders_1_arc")).isEqualTo(sourceDigest);
            assertThat(count(sql, "src.orders")).isEqualTo(4);
        }
    }

    @Test
    void createNewRefusesExistingTargetAndSamePhysicalObject() throws Exception {
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            sql.execute("DROP SCHEMA IF EXISTS src CASCADE");
            sql.execute("DROP SCHEMA IF EXISTS dst CASCADE");
            sql.execute("CREATE SCHEMA src");
            sql.execute("CREATE SCHEMA dst");
            sql.execute("CREATE TABLE src.orders(id int PRIMARY KEY, note text)");
            sql.execute("INSERT INTO src.orders VALUES (1, 'keep')");
            sql.execute("CREATE TABLE dst.orders_arc(id int PRIMARY KEY, note text)");
            sql.execute("INSERT INTO dst.orders_arc VALUES (9, 'existing')");

            String sourceBefore = digest(connection, "SELECT id,note FROM src.orders");
            String targetBefore = digest(connection, "SELECT id,note FROM dst.orders_arc");
            assertThatThrownBy(() -> copy("orders", "orders_arc"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("externalWriteUnconfirmed=false")
                    .hasMessageNotContaining("orders_arc");
            assertThat(digest(connection, "SELECT id,note FROM dst.orders_arc")).isEqualTo(targetBefore);
            assertThat(digest(connection, "SELECT id,note FROM src.orders")).isEqualTo(sourceBefore);
            assertThat(one(sql, "SELECT note FROM dst.orders_arc")).isEqualTo("existing");

            assertThatThrownBy(() -> copyTo("src", "orders", "src", "orders"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("externalWriteUnconfirmed=false")
                    .hasMessageNotContaining("src.orders");
            assertThat(digest(connection, "SELECT id,note FROM src.orders")).isEqualTo(sourceBefore);
            assertThat(one(sql, "SELECT note FROM src.orders")).isEqualTo("keep");
        }
    }

    @Test
    void emptyTableArchivesAndMissingTableFails() throws Exception {
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            sql.execute("DROP SCHEMA IF EXISTS src CASCADE");
            sql.execute("DROP SCHEMA IF EXISTS dst CASCADE");
            sql.execute("CREATE SCHEMA src");
            sql.execute("CREATE SCHEMA dst");
            sql.execute("CREATE TABLE src.empty_orders(id int PRIMARY KEY, note text)");
            copy("empty_orders", "empty_orders_arc");
            assertThat(count(sql, "dst.empty_orders_arc")).isEqualTo(0);

            assertThatThrownBy(() -> copy("missing_orders", "missing_orders_arc"))
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("未匹配到唯一源对象");
        }
    }

    @Test
    void restoreToNewTableLeavesOriginalUnchanged() throws Exception {
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            sql.execute("DROP SCHEMA IF EXISTS src CASCADE");
            sql.execute("DROP SCHEMA IF EXISTS dst CASCADE");
            sql.execute("CREATE SCHEMA src");
            sql.execute("CREATE SCHEMA dst");
            sql.execute("CREATE TABLE src.orders(id int PRIMARY KEY, amount numeric(12,2))");
            sql.execute("INSERT INTO src.orders VALUES (1, 10.00),(2, 20.50)");
            copy("orders", "orders_arc");
            sql.execute("UPDATE src.orders SET amount = 99.99 WHERE id = 1");
            String originalAfterChange = digest(connection, "SELECT id,amount FROM src.orders");
            copyTo("dst", "orders_arc", "dst", "orders_restored");
            assertThat(digest(connection, "SELECT id,amount FROM dst.orders_restored"))
                    .isEqualTo(digest(connection, "SELECT id,amount FROM dst.orders_arc"));
            assertThat(digest(connection, "SELECT id,amount FROM src.orders")).isEqualTo(originalAfterChange);
            assertThat(one(sql, "SELECT amount::text FROM src.orders WHERE id=1")).startsWith("99.99");
        }
    }

    @Test
    void sameRowCountDifferentContentHasDifferentDigest() throws Exception {
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            sql.execute("DROP TABLE IF EXISTS t1");
            sql.execute("DROP TABLE IF EXISTS t2");
            sql.execute("CREATE TABLE t1(id int, n numeric(10,2))");
            sql.execute("CREATE TABLE t2(id int, n numeric(10,2))");
            sql.execute("INSERT INTO t1 VALUES (1,1.10),(1,1.10)");
            sql.execute("INSERT INTO t2 VALUES (1,1.10),(1,1.11)");
            assertThat(digest(connection, "SELECT id,n FROM t1"))
                    .isNotEqualTo(digest(connection, "SELECT id,n FROM t2"));
        }
    }

    @Test
    void sameTableNameInDifferentSchemasAreIndependent() throws Exception {
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            sql.execute("DROP SCHEMA IF EXISTS src CASCADE");
            sql.execute("DROP SCHEMA IF EXISTS other CASCADE");
            sql.execute("DROP SCHEMA IF EXISTS dst CASCADE");
            sql.execute("CREATE SCHEMA src");
            sql.execute("CREATE SCHEMA other");
            sql.execute("CREATE SCHEMA dst");
            sql.execute("CREATE TABLE src.orders(id int PRIMARY KEY, note text)");
            sql.execute("CREATE TABLE other.orders(id int PRIMARY KEY, note text)");
            sql.execute("INSERT INTO src.orders VALUES (1, 'src')");
            sql.execute("INSERT INTO other.orders VALUES (1, 'keep-other')");
            copy("orders", "orders_arc");
            assertThat(one(sql, "SELECT note FROM dst.orders_arc")).isEqualTo("src");
            assertThat(one(sql, "SELECT note FROM other.orders")).isEqualTo("keep-other");
            assertThat(one(sql, "SELECT note FROM src.orders")).isEqualTo("src");
        }
    }

    @Test
    void supportedTypesRoundTripIncludingNullAndBinary() throws Exception {
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            sql.execute("DROP SCHEMA IF EXISTS src CASCADE");
            sql.execute("DROP SCHEMA IF EXISTS dst CASCADE");
            sql.execute("CREATE SCHEMA src");
            sql.execute("CREATE SCHEMA dst");
            sql.execute("""
                    CREATE TABLE src.typed(
                      id bigint PRIMARY KEY,
                      amount numeric(20,8),
                      note text,
                      payload bytea,
                      paid_at timestamptz
                    )
                    """);
            sql.execute("INSERT INTO src.typed VALUES (9007199254740993, 1.1000, 'a', decode('deadbeef','hex'), '2024-01-01 00:00:00+00')");
            sql.execute("INSERT INTO src.typed VALUES (2, NULL, NULL, NULL, NULL)");
            String sourceDigest = digest(connection, "SELECT id,amount,note,payload,paid_at FROM src.typed");
            copy("typed", "typed_arc");
            assertThat(digest(connection, "SELECT id,amount,note,payload,paid_at FROM dst.typed_arc"))
                    .isEqualTo(sourceDigest);
            assertThat(one(sql, "SELECT id::text FROM dst.typed_arc WHERE id=9007199254740993"))
                    .isEqualTo("9007199254740993");
            assertThat(one(sql, "SELECT amount IS NULL FROM dst.typed_arc WHERE id=2")).isEqualTo("t");
        }
    }

    private void copy(String sourceTable, String targetTable) throws Exception {
        copyTo("src", sourceTable, "dst", targetTable);
    }

    private void copyTo(String sourceSchema, String sourceTable, String targetSchema, String targetTable)
            throws Exception {
        MigrationRequest properties = new MigrationRequest();
        SourceDataSourceProperties source = new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.POSTGRESQL);
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());
        source.setUsername(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        source.setSourceSchema(sourceSchema);
        source.setSourceIncludes(sourceTable);
        source.setFetchSize(50);
        source.setRegexTableMapper(List.of(
                new PatternMapper("^" + Pattern.quote(sourceTable) + "$", targetTable)));
        properties.getSource().add(source);

        TargetDataSourceProperties target = properties.getTarget();
        target.setTargetProductType(ProductTypeEnum.POSTGRESQL);
        target.setUrl(POSTGRES.getJdbcUrl());
        target.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());
        target.setUsername(POSTGRES.getUsername());
        target.setPassword(POSTGRES.getPassword());
        target.setTargetSchema(targetSchema);
        target.setTargetTable(targetTable);
        target.setWriteMode(MigrationWriteModes.STRICT_COPY);
        target.setTargetDrop(true);
        target.setTargetTruncateData(true);
        target.setSyncExist(true);

        StringBuilder errors = new StringBuilder();
        DbSwitchResult result = new MigrationService(properties, table -> {
            if (table != null && table.getErrorMsg() != null && !table.getErrorMsg().isBlank()) {
                errors.append(table.getErrorMsg());
            }
        }).run();
        if (result.getIfAllSuccess() != null && !result.getIfAllSuccess().get()) {
            throw new IllegalStateException(errors.length() == 0 ? "migration reported failure" : errors.toString());
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static int count(Statement sql, String table) throws SQLException {
        try (ResultSet result = sql.executeQuery("SELECT count(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String one(Statement sql, String query) throws SQLException {
        try (ResultSet result = sql.executeQuery(query)) {
            result.next();
            return result.getString(1);
        }
    }

    private static String digest(Connection connection, String query) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(query)) {
            return ContentDigests.ofResultSet(result);
        }
    }

}

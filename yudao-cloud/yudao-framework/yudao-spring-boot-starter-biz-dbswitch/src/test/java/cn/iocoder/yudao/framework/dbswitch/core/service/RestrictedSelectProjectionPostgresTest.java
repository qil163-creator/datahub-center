package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 真实 PostgreSQL 执行：受限投影产出的 SQL 必须能执行，且只返回授权列；
 * 未授权的过滤/排序在到达数据库之前就被拒绝。只连接本任务显式指定的本机临时实例。
 */
@EnabledIfEnvironmentVariable(named = "SHARING_TEST_JDBC_URL", matches = ".+")
class RestrictedSelectProjectionPostgresTest {

    private static final List<String> PHYSICAL = List.of("id", "name", "phone", "salary");

    private Connection connection;
    private String controlUrl;
    private String databaseName;
    private DbSwitchRuntime runtime;

    private IMetaDataByJdbcService metadata() {
        return runtime.jdbcMetadata(ProductTypeEnum.POSTGRESQL);
    }

    private String testJdbc() {
        return controlUrl.substring(0, controlUrl.lastIndexOf('/') + 1) + databaseName;
    }

    @Test
    void exactListDoesNotTreatSchemaWildcardsAsPatterns() throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"gov_%\"");
            statement.execute("CREATE SCHEMA gov_extra");
            statement.execute("CREATE TABLE \"gov_%\".citizen(id bigint)");
            statement.execute("CREATE TABLE gov_extra.hidden(secret text)");
            statement.execute("CREATE TABLE \"gov_%\".\"Citizen\"(id bigint)");
        }
        var tables = metadata().queryExactTableList(testJdbc(), "sharing_test", "", "gov_%", 10, 5000);
        assertEquals(List.of("Citizen", "citizen"), tables.stream().map(t -> t.getTableName()).toList());
        assertEquals(List.of("gov_%", "gov_%"), tables.stream().map(t -> t.getSchemaName()).toList());
        assertThrows(IllegalArgumentException.class, () -> metadata().queryExactTableList(
                testJdbc(), "sharing_test", "", "gov_%", 1, 5000));
    }

    @Test
    void exactColumnsPreserveSpecialIdentifiersAndNeverFallBackToAnotherSchema() throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"Gov.Schema\"");
            statement.execute("CREATE TABLE \"Gov.Schema\".\"citizen;\"\"\"(\" Id.\"\" \" bigint, secret text)");
        }
        var columns = metadata().queryExactTableColumns(testJdbc() + "?currentSchema=public", "sharing_test", "",
                "Gov.Schema", "citizen;\"", List.of(" Id.\" "), 5000);
        assertEquals(List.of(" Id.\" "), columns.stream().map(c -> c.getFieldName()).toList());
        assertThrows(IllegalStateException.class, () -> metadata().queryExactTableColumns(
                testJdbc(), "sharing_test", "", "missing", "citizen", null, 5000));
    }

    @Test
    void emptyMissingOrDuplicateColumnProjectionCannotWidenMetadata() {
        assertThrows(IllegalArgumentException.class, () -> metadata().queryExactTableColumns(
                testJdbc(), "sharing_test", "", "public", "citizen", List.of(), 5000));
        assertThrows(IllegalArgumentException.class, () -> metadata().queryExactTableColumns(
                testJdbc(), "sharing_test", "", "public", "citizen", List.of("id", "id"), 5000));
        assertThrows(IllegalStateException.class, () -> metadata().queryExactTableColumns(
                testJdbc(), "sharing_test", "", "public", "citizen", List.of("missing"), 5000));
        assertEquals(4, metadata().queryExactTableColumns(testJdbc(), "sharing_test", "", "public", "citizen", null, 5000).size());
    }

    @BeforeEach
    void createDatabase() throws Exception {
        controlUrl = System.getenv("SHARING_TEST_JDBC_URL");
        if (controlUrl == null || !controlUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/postgres")) {
            throw new IllegalStateException("Explicit local isolated SHARING_TEST_JDBC_URL is required");
        }
        runtime = DbSwitchRuntime.standalone();
        try (var control = DriverManager.getConnection(controlUrl, "sharing_test", "");
             var statement = control.createStatement()) {
            databaseName = "sharing_test_" + UUID.randomUUID().toString().replace("-", "");
            statement.execute("CREATE DATABASE " + databaseName);
        }
        connection = DriverManager.getConnection(
                controlUrl.substring(0, controlUrl.lastIndexOf('/') + 1) + databaseName, "sharing_test", "");
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE public.citizen(id bigint, name text, phone text, salary numeric)");
            statement.execute("INSERT INTO public.citizen VALUES (1,'甲','13800000001',9000),"
                    + "(2,'乙','13800000002',21000)");
        }
    }

    @AfterEach
    void dropDatabase() throws Exception {
        if (runtime != null) {
            runtime.close();
        }
        if (connection != null) {
            connection.close();
        }
        if (databaseName != null && databaseName.matches("sharing_test_[0-9a-f]{32}")) {
            try (var control = DriverManager.getConnection(controlUrl, "sharing_test", "");
                 var statement = control.createStatement()) {
                statement.execute("DROP DATABASE " + databaseName);
            }
        }
    }

    private RestrictedSelectProjection restrict(String sql, Set<String> allowed) {
        return RestrictedSelectGuard.restrictProjection(ProductTypeEnum.POSTGRESQL, sql,
                "public", "citizen", PHYSICAL, allowed);
    }

    private List<String> executeColumns(String sql) throws Exception {
        try (var statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            ResultSetMetaData metadata = rs.getMetaData();
            List<String> labels = new ArrayList<>();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                labels.add(metadata.getColumnLabel(index));
            }
            return labels;
        }
    }

    private List<String> executeFirstColumn(String sql) throws Exception {
        try (var statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (rs.next()) {
                values.add(rs.getString(1));
            }
            return values;
        }
    }

    @Test
    void wildcardIsExecutedAsTheApprovedProjectionOnly() throws Exception {
        RestrictedSelectProjection projection = restrict("select * from public.citizen", Set.of("id", "phone"));
        assertEquals(List.of("id", "phone"), executeColumns(projection.sql()));
    }

    @Test
    void approvedFilterAndOrderExecuteAgainstTheRealDatabase() throws Exception {
        RestrictedSelectProjection projection =
                restrict("select id from citizen where name = '乙' order by name desc", Set.of("id", "name"));
        assertEquals(List.of("2"), executeFirstColumn(projection.sql()));
        assertEquals(List.of("id"), executeColumns(projection.sql()));
    }

    @Test
    void unapprovedFilterNeverReachesTheDatabase() {
        assertThrows(IllegalArgumentException.class,
                () -> restrict("select id from public.citizen where salary > 10000", Set.of("id", "name")));
        assertThrows(IllegalArgumentException.class,
                () -> restrict("select id from public.citizen order by salary", Set.of("id", "name")));
    }

    @Test
    void quotedMixedCaseColumnsExecuteWithTheirRealIdentifiers() throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE public.mixed(\"Id\" bigint, \"Phone\" text)");
            statement.execute("INSERT INTO public.mixed VALUES (7,'13900000000')");
        }
        RestrictedSelectProjection projection = RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.POSTGRESQL, "select \"Id\" from public.mixed where \"Phone\" = '13900000000'",
                "public", "mixed", List.of("Id", "Phone"), Set.of("Id", "Phone"));
        assertEquals(List.of("7"), executeFirstColumn(projection.sql()));
        assertEquals(List.of("Id"), executeColumns(projection.sql()));
    }

    @Test
    void aliasedTableReferencesAreRewrittenToTheBoundTable() throws Exception {
        RestrictedSelectProjection projection =
                restrict("select c.id from public.citizen c where c.name = '甲'", Set.of("id", "name"));
        assertEquals(List.of("1"), executeFirstColumn(projection.sql()));
    }

    @Test
    void configuredSchemaWinsOverSearchPathWithAnotherSameNamedTable() throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA other");
            statement.execute("CREATE TABLE other.citizen(id bigint)");
            statement.execute("INSERT INTO other.citizen VALUES (99)");
            statement.execute("SET search_path TO other, public");
        }
        assertEquals(List.of("99"), executeFirstColumn("SELECT id FROM citizen"));
        var plan = RestrictedSelectGuard.parseBound(ProductTypeEnum.POSTGRESQL,
                "select c.ID from Citizen c order by c.ID", "public");
        assertEquals(List.of("1", "2"), executeFirstColumn(plan.getNormalizedSql()));
        var other = RestrictedSelectGuard.parseBound(ProductTypeEnum.POSTGRESQL,
                "select id from other.citizen", "public");
        assertEquals(List.of("99"), executeFirstColumn(other.getNormalizedSql()));
    }

    @Test
    void quotedDotsQuotesSpacesAndBackslashesRoundTripThroughBothStages() throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"S\"\".One\"");
            statement.execute("CREATE TABLE \"S\"\".One\".\"T\\. Two\" (\"C\"\".Id\" bigint)");
            statement.execute("INSERT INTO \"S\"\".One\".\"T\\. Two\" VALUES (17)");
        }
        var plan = RestrictedSelectGuard.parseBound(ProductTypeEnum.POSTGRESQL,
                "select \"C\"\".Id\" from \"S\"\".One\".\"T\\. Two\"", null);
        assertEquals("S\".One", plan.getSchemaName());
        assertEquals("T\\. Two", plan.getTableName());
        assertEquals(List.of("17"), executeFirstColumn(plan.getNormalizedSql()));
        var restricted = RestrictedSelectGuard.restrictProjection(ProductTypeEnum.POSTGRESQL,
                plan.getNormalizedSql(), plan.getSchemaName(), plan.getTableName(),
                List.of("C\".Id"), Set.of("C\".Id"));
        assertEquals(List.of("17"), executeFirstColumn(restricted.sql()));
        assertEquals(List.of("C\".Id"), executeColumns(restricted.sql()));
    }

    @Test
    void quotedAndUnquotedCaseTargetDifferentRealTables() throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE public.\"Citizen\"(id bigint)");
            statement.execute("INSERT INTO public.\"Citizen\" VALUES (55)");
        }
        var lower = RestrictedSelectGuard.parseBound(ProductTypeEnum.POSTGRESQL,
                "select id from Citizen order by id", "public");
        var upper = RestrictedSelectGuard.parseBound(ProductTypeEnum.POSTGRESQL,
                "select id from \"Citizen\"", "public");
        assertEquals(List.of("1", "2"), executeFirstColumn(lower.getNormalizedSql()));
        assertEquals(List.of("55"), executeFirstColumn(upper.getNormalizedSql()));
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                ProductTypeEnum.POSTGRESQL, upper.getNormalizedSql(), "public", "citizen",
                List.of("id"), Set.of("id")));
    }
}

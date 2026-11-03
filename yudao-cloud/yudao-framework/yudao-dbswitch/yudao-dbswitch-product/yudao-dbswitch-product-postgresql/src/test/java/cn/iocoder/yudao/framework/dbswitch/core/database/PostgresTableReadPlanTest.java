package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableReadPlan.Relation;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.sql.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(40)
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class PostgresTableReadPlanTest {
    private final String schema = "graph_" + UUID.randomUUID().toString().replace("-", "");
    private final String target = schema + "_copy";
    private Connection connection;
    @BeforeEach void setup() throws Exception {
        connection = DriverManager.getConnection(url(), user(), "");
        sql("CREATE SCHEMA " + schema); sql("CREATE SCHEMA " + target);
        sql("CREATE TABLE " + schema + ".root(id int) PARTITION BY RANGE(id)");
        sql("CREATE TABLE " + schema + ".branch PARTITION OF " + schema + ".root FOR VALUES FROM (0) TO (100) PARTITION BY RANGE(id)");
        sql("CREATE TABLE " + schema + ".leaf PARTITION OF " + schema + ".branch FOR VALUES FROM (0) TO (100)");
        sql("CREATE TABLE " + schema + ".other_leaf PARTITION OF " + schema + ".root FOR VALUES FROM (100) TO (200)");
        sql("INSERT INTO " + schema + ".root VALUES (1),(101)");
        sql("CREATE TABLE " + schema + ".base(id int)");
        sql("CREATE TABLE " + schema + ".child() INHERITS (" + schema + ".base)");
        sql("INSERT INTO " + schema + ".base VALUES(2)"); sql("INSERT INTO " + schema + ".child VALUES(3)");
    }
    @AfterEach void cleanup() throws Exception {
        if (connection != null) try (var owned = connection) { sql("DROP SCHEMA " + schema + " CASCADE"); sql("DROP SCHEMA " + target + " CASCADE"); }
    }

    @Test void metadataIsCompleteWhilePlannedReadsNeverDoubleCountDescendants() throws Exception {
        var database = new DatabasePostgresImpl();
        var tables = database.queryTableList(connection, schema);
        assertEquals(Set.of("root", "branch", "leaf", "other_leaf", "base", "child"),
                tables.stream().map(table -> table.getTableName()).collect(Collectors.toSet()));
        var selected = tables.stream().map(table -> new Relation(schema, table.getTableName())).toList();
        var include = database.planTableReads(connection, selected, TableReadPlan.ReadMode.INCLUDE_DESCENDANTS);
        assertEquals(2, include.reads().size()); assertEquals(4, include.coveredSelections().size());
        assertEquals(4, count(include));
        var only = database.planTableReads(connection, selected, TableReadPlan.ReadMode.ONLY);
        assertEquals(6, only.reads().size()); assertTrue(only.reads().stream().allMatch(read -> read.fromSql().startsWith("ONLY ")));
        assertEquals(4, count(only));
        assertFalse(connection.isClosed()); assertTrue(connection.getAutoCommit());
    }

    @Test void traditionalMultipleParentsWithSharedDescendantRequireOnlyReads() throws Exception {
        sql("CREATE TABLE " + schema + ".second_parent(id int)");
        sql("CREATE TABLE " + schema + ".shared() INHERITS (" + schema + ".base," + schema + ".second_parent)");
        var selected = List.of(new Relation(schema, "base"), new Relation(schema, "second_parent"), new Relation(schema, "shared"));
        var database = new DatabasePostgresImpl();
        assertThrows(UnsupportedOperationException.class, () -> database.planTableReads(connection, selected, TableReadPlan.ReadMode.INCLUDE_DESCENDANTS));
        assertEquals(3, database.planTableReads(connection, selected, TableReadPlan.ReadMode.ONLY).reads().size());
    }

    @Test void migrationServiceConsumesSelectionAndOnlySqlInsteadOfReschedulingCoveredChildren() throws Exception {
        sql("CREATE TABLE " + target + ".root(id int)"); sql("CREATE TABLE " + target + ".base(id int)");
        var properties = properties("root,branch,leaf,other_leaf,base,child", TableReadPlan.ReadMode.INCLUDE_DESCENDANTS);
        try (var runtime = DbSwitchRuntime.standalone()) {
            var result = new MigrationService(properties, ignored -> { }, null, runtime).run();
            assertTrue(result.getIfAllSuccess().get()); assertEquals(2, result.getTableResultList().size());
            assertEquals(4, countTarget("root") + countTarget("base"));
        }
        sql("TRUNCATE " + target + ".base"); sql("CREATE TABLE " + target + ".child(id int)");
        try (var runtime = DbSwitchRuntime.standalone()) {
            var result = new MigrationService(properties("base,child", TableReadPlan.ReadMode.ONLY), ignored -> { }, null, runtime).run();
            assertTrue(result.getIfAllSuccess().get()); assertEquals(2, result.getTableResultList().size());
            assertEquals(1, countTarget("base")); assertEquals(1, countTarget("child"));
        }
    }

    private MigrationRequest properties(String includes, TableReadPlan.ReadMode mode) {
        var properties = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.POSTGRESQL); source.setSourceSchema(schema); source.setSourceIncludes(includes);
        source.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());
        source.setTableReadMode(mode); source.setUrl(url()); source.setUsername(user()); source.setPassword(""); source.setPoolSize(2);
        properties.getSource().add(source); var destination = properties.getTarget();
        destination.setTargetProductType(ProductTypeEnum.POSTGRESQL); destination.setTargetSchema(target);
        destination.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());
        destination.setUrl(url()); destination.setUsername(user()); destination.setPassword(""); destination.setPoolSize(2);
        destination.setWriterEngineInsert(true); destination.setTargetDrop(false); return properties;
    }
    private long count(TableReadPlan plan) throws Exception {
        long result = 0;
        for (var read : plan.reads()) try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM " + read.fromSql())) {
            assertTrue(rows.next()); result += rows.getLong(1);
        }
        return result;
    }
    private long countTarget(String table) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM " + target + "." + table)) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }
    private void sql(String value) throws Exception { try (var statement = connection.createStatement()) { statement.execute(value); } }
    private String url() { return System.getProperty("datahub.test.postgres.url"); }
    private String user() { return System.getProperty("datahub.test.postgres.user", "dataforge_test"); }
}

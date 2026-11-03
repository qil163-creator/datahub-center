package cn.iocoder.yudao.module.dataforge.service.access;

import cn.iocoder.yudao.module.dataforge.service.access.config.AccessTaskConfigCodec;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 平台配置迁移在独立随机模式中执行；不触及共享平台数据。 */
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
@Timeout(45)
class AccessConfigMigrationPostgreSqlTest {
    private final String schema = "access_cfg_" + UUID.randomUUID().toString().replace("-", "");
    private Connection connection;
    private String migration;

    @BeforeEach void setup() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
        connection = DriverManager.getConnection(url, System.getProperty("datahub.test.postgres.user", "dataforge_test"), "");
        execute("CREATE SCHEMA " + schema);
        execute("SET search_path TO " + schema + ",public");
        execute("CREATE TABLE dh_dataforge_access(id bigint PRIMARY KEY,data_access_json jsonb,published_config_json jsonb)");
        execute("CREATE TABLE dh_dataforge_access_task(id bigint PRIMARY KEY,config_json jsonb,occupies_slot boolean NOT NULL DEFAULT false,sync_evidence jsonb)");
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("sql/postgresql/migrations"))) root = root.getParent();
        assertNotNull(root);
        try (var files = Files.list(root.resolve("sql/postgresql/migrations"))) {
            Path file = files.filter(path -> path.getFileName().toString().endsWith("_dataforge_access-config-v1.sql"))
                    .findFirst().orElseThrow();
            migration = Files.readString(file).replace("\"datahub-center\"", "\"" + schema + "\"");
        }
    }

    @AfterEach void cleanup() throws Exception {
        if (connection != null) try (var owned = connection) {
            execute("ROLLBACK");
            assertTrue(schema.matches("access_cfg_[a-f0-9]{32}"));
            execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test void convertsAllThreeStoresPreservesRulesAndReplaysWithoutChangingContent() throws Exception {
        String legacy = """
                {"source":[{"sourceType":1,"includeOrExclude":0,"sourceExcludes":"secret_table","fetchSize":1200,
                "mapperType":"SINGLE","password":"discard-fixture-only","url":"jdbc:discard-fixture-only",
                "driverPath":"discard-fixture-only","increaseStartVal":"9007199254740993",
                "configMap":{"orders":{"sourcePrimaryKeys":["tenant","id"],"increaseColumnName":"modified_at",
                "increaseColumn":{"fieldName":"modified_at"},"increaseStartVal":"9007199254740993",
                "regexTableMapper":[{"fromPattern":"^orders$","toValue":"purchases"}]}}}],
                "target":{"targetDrop":false,"targetTruncateData":true,"syncExist":false,"onlyCreate":true,
                "indexCreate":true,"syncDefaultVal":true,"lowercase":true,"createTableAutoIncrement":true,
                "targetSyncOption":"INSERT_UPDATE","password":"discard-fixture-only"}}
                """;
        insert(legacy, false, false);
        execute(migration);
        String converted = one("SELECT data_access_json::text FROM dh_dataforge_access WHERE id=1");
        assertFalse(converted.contains("discard-fixture-only")); assertFalse(converted.contains("increaseStartVal"));
        assertFalse(converted.contains("\"increaseColumn\""));
        var config = AccessTaskConfigCodec.read(converted);
        assertEquals(1, config.getSchemaVersion()); assertFalse(config.getSource().getFirst().getCursorBootstrapAllowed());
        assertEquals("secret_table", config.getSource().getFirst().getSourceExcludes());
        assertEquals("modified_at", config.getSource().getFirst().getConfigMap().get("orders").getIncreaseColumnName());
        assertEquals("purchases", config.getSource().getFirst().getConfigMap().get("orders").getRegexTableMapper().getFirst().getToValue());
        assertTrue(config.getTarget().isTargetTruncateData()); assertFalse(config.getTarget().isSyncExist());
        assertTrue(config.getTarget().isOnlyCreate()); assertTrue(config.getTarget().isIndexCreate());
        assertTrue(config.getTarget().isSyncDefaultVal()); assertTrue(config.getTarget().isCreateTableAutoIncrement());
        assertEquals("INSERT_UPDATE", config.getTarget().getTargetSyncOption());
        assertEquals(converted, one("SELECT (published_config_json->'dataAccessJson')::text FROM dh_dataforge_access WHERE id=1"));
        assertEquals(converted, one("SELECT (config_json->'dataAccessJson')::text FROM dh_dataforge_access_task WHERE id=1"));
        execute(migration);
        assertEquals(converted, one("SELECT data_access_json::text FROM dh_dataforge_access WHERE id=1"));
        assertThrows(SQLException.class, () -> execute("UPDATE dh_dataforge_access SET data_access_json='{}'::jsonb"));
    }

    @Test void occupiedRunOrExistingEffectEvidencePreventsAnyRewrite() throws Exception {
        insert("{\"source\":[{}],\"target\":{}}", true, false);
        assertThrows(SQLException.class, () -> execute(migration)); execute("ROLLBACK");
        assertNull(one("SELECT data_access_json->>'schemaVersion' FROM dh_dataforge_access WHERE id=1"));
        execute("UPDATE dh_dataforge_access_task SET occupies_slot=false,sync_evidence='{}'::jsonb");
        assertThrows(SQLException.class, () -> execute(migration)); execute("ROLLBACK");
        assertNull(one("SELECT data_access_json->>'schemaVersion' FROM dh_dataforge_access WHERE id=1"));
    }

    @Test void unrecognizedExtensionsFailAtomicallyInsteadOfLosingBehavior() throws Exception {
        insert("{\"source\":[{}],\"target\":{\"customDdlMap\":{\"orders\":\"CREATE TABLE orders(id int)\"}}}", false, false);
        assertThrows(SQLException.class, () -> execute(migration)); execute("ROLLBACK");
        assertEquals("CREATE TABLE orders(id int)", one("SELECT data_access_json->'target'->'customDdlMap'->>'orders' FROM dh_dataforge_access WHERE id=1"));
        assertNull(one("SELECT data_access_json->>'schemaVersion' FROM dh_dataforge_access WHERE id=1"));
    }

    private void insert(String config, boolean occupied, boolean evidence) throws SQLException {
        try (var sql = connection.prepareStatement("INSERT INTO dh_dataforge_access VALUES (1,?::jsonb,jsonb_build_object('dataAccessJson',?::jsonb,'sourceDatabaseId',11,'targetDatabaseId',22))")) {
            sql.setString(1, config); sql.setString(2, config); sql.executeUpdate();
        }
        try (var sql = connection.prepareStatement("INSERT INTO dh_dataforge_access_task VALUES (1,jsonb_build_object('dataAccessJson',?::jsonb),?,?::jsonb)")) {
            sql.setString(1, config); sql.setBoolean(2, occupied); sql.setString(3, evidence ? "{}" : null); sql.executeUpdate();
        }
    }
    private void execute(String sql) throws SQLException { try (var statement = connection.createStatement()) { statement.execute(sql); } }
    private String one(String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { assertTrue(rows.next()); return rows.getString(1); }
    }
}

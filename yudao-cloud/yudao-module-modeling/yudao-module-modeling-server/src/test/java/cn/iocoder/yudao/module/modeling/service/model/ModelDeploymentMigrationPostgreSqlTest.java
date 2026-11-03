package cn.iocoder.yudao.module.modeling.service.model;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 持久占用的真实数据库边界：跨租户唯一、并发插入、未知占用保留、围栏失效以及迁移重放。 */
@Testcontainers(disabledWithoutDocker = true)
class ModelDeploymentMigrationPostgreSqlTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    private Connection open() throws SQLException {
        var connection = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        try (var sql = connection.createStatement()) { sql.execute("SET search_path TO \"datahub-center\",pg_catalog"); }
        return connection;
    }
    @BeforeEach void migration() throws Exception {
        try (var connection = open(); var sql = connection.createStatement()) {
            sql.execute("DROP SCHEMA IF EXISTS \"datahub-center\" CASCADE");
            sql.execute("CREATE SCHEMA \"datahub-center\"");
            sql.execute("CREATE TABLE \"datahub-center\".dh_modeling_model_materialized(id bigint PRIMARY KEY,status varchar(32),message varchar(2000),updater varchar(64),update_time timestamp,deleted smallint DEFAULT 0)");
            sql.execute("CREATE TABLE \"datahub-center\".dh_modeling_model_materialized_log(id bigint PRIMARY KEY)");
            migrate(connection);
        }
    }
    private void migrate(Connection connection) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("sql/postgresql/migrations"))) root = root.getParent();
        assertNotNull(root);
        try (var sql = connection.createStatement()) {
            for (String file : java.util.List.of("0165_modeling_materialized-deployment-safety.sql", "0166_modeling_remove-legacy-recovery.sql")) {
                sql.execute(Files.readString(root.resolve("sql/postgresql/migrations/" + file)));
            }
        }
    }
    private void claim(Connection connection, long id, long tenant, String target, String owner) throws SQLException {
        try (var insert = connection.prepareStatement("""
                INSERT INTO dh_modeling_deployment_attempt(id,materialized_id,version_id,datasource_id,target_key,schema_name,table_name,
                configuration_revision,publish_mode,lease_owner,lease_until,before_observation_json,ddl_sql,fields_json,model_snapshot_json,tenant_id)
                VALUES(?,1,20,? ,?,'public','customer','r1','REBUILD',?,CURRENT_TIMESTAMP+INTERVAL '5 minutes','{}','CREATE TABLE customer(id bigint)','[]','{}',?)
                """)) {
            insert.setLong(1, id); insert.setLong(2, id); insert.setString(3, target); insert.setString(4, owner); insert.setLong(5, tenant); insert.executeUpdate();
        }
    }

    @Test void concurrentClaimsOfTheSamePhysicalTargetSerializeAcrossTenantAndDatasourceIds() throws Exception {
        try (var holder = open(); var pool = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false); claim(holder, 1, 1, "same-physical-target", "first");
            var attempted = new CountDownLatch(1);
            var result = pool.submit(() -> {
                try (var contender = open()) { attempted.countDown(); claim(contender, 2, 2, "same-physical-target", "second"); return "accepted"; }
                catch (SQLException conflict) { return conflict.getSQLState(); }
            });
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> result.get(200, TimeUnit.MILLISECONDS));
            holder.commit(); assertEquals("23505", result.get(10, TimeUnit.SECONDS));
        }
    }

    @Test void unknownResultsKeepTheUniqueOccupationUntilReconciledTerminal() throws Exception {
        try (var connection = open(); var sql = connection.createStatement()) {
            claim(connection, 1, 1, "target", "first");
            sql.execute("UPDATE dh_modeling_deployment_attempt SET status='UNKNOWN' WHERE id=1");
            assertEquals("23505", assertThrows(SQLException.class, () -> claim(connection, 2, 2, "target", "second")).getSQLState());
            sql.execute("UPDATE dh_modeling_deployment_attempt SET status='SUCCESS',phase='FINISHED' WHERE id=1");
            claim(connection, 2, 2, "target", "second");
        }
    }

    @Test void recoveryFencesTheOldOwnerAndAnExpiredLeaseDoesNotAuthorizeNewDdl() throws Exception {
        try (var connection = open(); var sql = connection.createStatement()) {
            claim(connection, 1, 1, "target", "stale");
            sql.execute("UPDATE dh_modeling_deployment_attempt SET phase='EXECUTING',lease_until=CURRENT_TIMESTAMP-INTERVAL '1 minute' WHERE id=1");
            assertEquals(1, sql.executeUpdate("UPDATE dh_modeling_deployment_attempt SET status='UNKNOWN',lease_owner='recovery',lease_until=CURRENT_TIMESTAMP+INTERVAL '5 minutes' WHERE id=1 AND status IN ('RUNNING','UNKNOWN') AND lease_until<=CURRENT_TIMESTAMP"));
            assertEquals(0, sql.executeUpdate("UPDATE dh_modeling_deployment_attempt SET status='SUCCESS' WHERE id=1 AND lease_owner='stale' AND lease_until>CURRENT_TIMESTAMP"));
            assertEquals("23505", assertThrows(SQLException.class, () -> claim(connection, 2, 1, "target", "new-ddl")).getSQLState());
            assertEquals(1, sql.executeUpdate("UPDATE dh_modeling_deployment_attempt SET status='FAIL',phase='FINISHED' WHERE id=1 AND lease_owner='recovery' AND lease_until>CURRENT_TIMESTAMP"));
        }
    }

    @Test void migrationReplayPreservesFrozenAttemptsAndCurrentExecutionState() throws Exception {
        try (var connection = open(); var sql = connection.createStatement()) {
            claim(connection, 1, 1, "target", "first");
            sql.execute("INSERT INTO dh_modeling_model_materialized(id,status,attempt_id,version_id) VALUES(2,'RUNNING',1,20)");
            migrate(connection);
            try (var rows = sql.executeQuery("SELECT count(*) FROM dh_modeling_deployment_attempt WHERE target_key='target' AND lease_owner='first'")) {
                assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
            }
            try (var rows = sql.executeQuery("SELECT status,attempt_id,version_id FROM dh_modeling_model_materialized WHERE id=2")) {
                assertTrue(rows.next()); assertEquals("RUNNING", rows.getString(1));
                assertEquals(1L, rows.getLong(2)); assertEquals(20L, rows.getLong(3));
            }
            try (var rows = sql.executeQuery("SELECT count(*) FROM pg_attribute WHERE attrelid='dh_modeling_model_materialized_log'::regclass AND attname='audit_evidence_json' AND NOT attisdropped")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
        }
    }
}

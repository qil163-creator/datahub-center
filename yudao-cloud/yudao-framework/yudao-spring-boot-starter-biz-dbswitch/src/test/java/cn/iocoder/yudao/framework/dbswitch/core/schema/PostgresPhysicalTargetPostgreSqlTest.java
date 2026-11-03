package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.sql.*;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实服务器身份与锁回归：账号、客户端别名和重建不能分裂同一个物理目标。 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresPhysicalTargetPostgreSqlTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");
    private final cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase database = DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL);

    private Connection open() throws SQLException { return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()); }
    private String key(Connection connection, String table) throws SQLException {
        return PostgresSchemaChanges.physicalTargetFingerprint(connection, database, "public", table, MetadataReadOptions.fresh());
    }

    @Test void identityExcludesUserAndIsStableAcrossAbsenceCreateAndRebuild() throws Exception {
        try (var connection = open(); var sql = connection.createStatement()) {
            String key = key(connection, "identity_example");
            sql.execute("CREATE TABLE public.identity_example(id bigint)");
            try {
                assertEquals(key, key(connection, "identity_example"));
                String originalScope = PostgresSchemaChanges.observe(connection, database, "public", "identity_example", MetadataReadOptions.fresh()).scopeFingerprint();
                sql.execute("GRANT EXECUTE ON FUNCTION pg_catalog.pg_control_system() TO pg_read_all_data");
                sql.execute("SET ROLE pg_read_all_data");
                assertEquals(key, key(connection, "identity_example"));
                assertNotEquals(originalScope, PostgresSchemaChanges.observe(connection, database, "public", "identity_example", MetadataReadOptions.fresh()).scopeFingerprint());
                sql.execute("RESET ROLE");
                sql.execute("DROP TABLE public.identity_example");
                assertEquals(key, key(connection, "identity_example"));
                sql.execute("CREATE TABLE public.identity_example(id text)");
                assertEquals(key, key(connection, "identity_example"));
                assertNotEquals(key, key(connection, "another_identity"));
            } finally { sql.execute("RESET ROLE"); sql.execute("DROP TABLE IF EXISTS public.identity_example"); }
        }
    }

    @Test void ordinaryLoginNeedsExplicitControlIdentityGrantAndThenMatchesTheSameTarget() throws Exception {
        String reader = "target_reader_" + java.util.UUID.randomUUID().toString().replace("-", "");
        try (var admin = open(); var sql = admin.createStatement()) {
            sql.execute("CREATE ROLE " + reader + " LOGIN PASSWORD 'isolated-fixture-only'");
            try (var ordinary = DriverManager.getConnection(PG.getJdbcUrl(), reader, "isolated-fixture-only")) {
                // 官方镜像默认 PUBLIC 可执行；权限被平台收紧后的路径也必须明确拒绝。
                assertEquals(key(admin, "ordinary_absent_target"), key(ordinary, "ordinary_absent_target"));
                sql.execute("REVOKE EXECUTE ON FUNCTION pg_catalog.pg_control_system() FROM PUBLIC");
                assertThrows(SQLException.class, () -> key(ordinary, "ordinary_absent_target"));
                sql.execute("GRANT EXECUTE ON FUNCTION pg_catalog.pg_control_system() TO " + reader);
                assertEquals(key(admin, "ordinary_absent_target"), key(ordinary, "ordinary_absent_target"));
                // 客户端主机别名不会参与服务器控制标识摘要。
                String alias = PG.getJdbcUrl().replace("localhost", "127.0.0.1");
                try (var aliasConnection = DriverManager.getConnection(alias, reader, "isolated-fixture-only")) {
                    assertEquals(key(admin, "ordinary_absent_target"), key(aliasConnection, "ordinary_absent_target"));
                }
            } finally {
                sql.execute("GRANT EXECUTE ON FUNCTION pg_catalog.pg_control_system() TO PUBLIC");
                sql.execute("DROP OWNED BY " + reader); sql.execute("DROP ROLE " + reader);
            }
        }
    }

    @Test void physicalNameLockSerializesTwoConnectionsBeforeTheTableExists() throws Exception {
        try (var first = open(); var second = open(); var pool = Executors.newSingleThreadExecutor()) {
            first.setAutoCommit(false); second.setAutoCommit(false);
            first.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            second.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            PostgresSchemaChanges.lockAndObserve(first, database, "public", "absent_serial_target", MetadataReadOptions.fresh());
            var entered = new CountDownLatch(1);
            var waiting = pool.submit(() -> { entered.countDown(); return PostgresSchemaChanges.lockAndObserve(second, database,
                    "public", "absent_serial_target", MetadataReadOptions.fresh()); });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> waiting.get(200, TimeUnit.MILLISECONDS));
            first.rollback();
            assertFalse(waiting.get(10, TimeUnit.SECONDS).precondition().tableExists());
            second.rollback();
        }
    }

    @Test void truncatedAsciiAndMultibyteNamesCannotCreateDifferentKeysForTheSamePhysicalName() throws Exception {
        try (var connection = open()) {
            assertEquals(64, key(connection, "a".repeat(63)).length());
            assertThrows(IllegalArgumentException.class, () -> key(connection, "a".repeat(64)));
            assertEquals(64, key(connection, "表".repeat(21)).length());
            assertThrows(IllegalArgumentException.class, () -> key(connection, "表".repeat(22)));
            assertThrows(IllegalArgumentException.class, () -> PostgresSchemaChanges.physicalTargetFingerprint(connection, database,
                    "s".repeat(64), "target", MetadataReadOptions.fresh()));
        }
    }

    @Test void failedAtomicRebuildRestoresOriginalTableAndData() throws Exception {
        try (var connection = open(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE public.atomic_rebuild(id bigint)");
            sql.execute("INSERT INTO public.atomic_rebuild VALUES(42)");
            try {
                var before = PostgresSchemaChanges.observe(connection, database, "public", "atomic_rebuild", MetadataReadOptions.fresh());
                var plan = PostgresSchemaChanges.rebuildPlan("public", "atomic_rebuild",
                        List.of("CREATE TABLE public.atomic_rebuild(id bigint)", "ALTER TABLE public.atomic_rebuild ADD COLUMN id bigint"), true);
                connection.setAutoCommit(false); connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                assertThrows(SQLException.class, () -> PostgresSchemaChanges.execute(connection, database, plan,
                        before.precondition(), MetadataReadOptions.fresh(), ignored -> { }));
                connection.rollback(); connection.setAutoCommit(true);
                try (var rows = sql.executeQuery("SELECT id FROM public.atomic_rebuild")) { assertTrue(rows.next()); assertEquals(42, rows.getLong(1)); }
            } finally { if (!connection.getAutoCommit()) connection.rollback(); connection.setAutoCommit(true); sql.execute("DROP TABLE IF EXISTS public.atomic_rebuild"); }
        }
    }
}

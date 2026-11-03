package cn.iocoder.yudao.module.dataforge.service.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.*;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 在显式指定的隔离 PostgreSQL 测试库验证，所有测试写入均回滚。 */
@EnabledIfEnvironmentVariable(named = "DATAFORGE_TEST_PG_URL", matches = ".+")
class IntegrationPostgreSqlContractTest {
    @Test void categoryTreeLockSerializesIndependentTransactions() throws Exception {
        try (var first = connection(); var second = connection()) {
            assertTrue(bool(first, "SELECT true FROM (SELECT pg_advisory_xact_lock(215030)) tree_lock"));
            assertFalse(bool(second, "SELECT pg_try_advisory_xact_lock(215030)"));
            first.rollback();
            assertTrue(bool(second, "SELECT pg_try_advisory_xact_lock(215030)"));
            second.rollback();
        }
    }

    @Test void partialIndexAllowsDeletedCategoryNamesButRejectsTwoActiveSiblings() throws Exception {
        try (var connection = connection()) {
            String name = "类目回归_" + UUID.randomUUID().toString().substring(0, 8);
            try (var insert = connection.prepareStatement(
                    "INSERT INTO dh_dataforge_integration_category(id,parent_id,name,deleted) VALUES(?,0,?,?)")) {
                insert.setLong(1, 9000000001L); insert.setString(2, name); insert.setInt(3, 1); assertEquals(1, insert.executeUpdate());
                insert.setLong(1, 9000000002L); insert.setInt(3, 0); assertEquals(1, insert.executeUpdate());
                insert.setLong(1, 9000000003L);
                var duplicate = assertThrows(SQLException.class, insert::executeUpdate);
                assertEquals("23505", duplicate.getSQLState());
            } finally { connection.rollback(); }
        }
    }

    @Test void revisionCompareAndSetRejectsAnAlreadyConsumedRevision() throws Exception {
        try (var connection = connection(); var select = connection.createStatement()) {
            try (var rows = select.executeQuery("SELECT id,revision FROM dh_dataforge_integration_task ORDER BY id LIMIT 1")) {
                assertTrue(rows.next()); long id = rows.getLong(1); int revision = rows.getInt(2);
                try (var update = connection.prepareStatement(
                        "UPDATE dh_dataforge_integration_task SET revision=revision+1 WHERE id=? AND revision=?")) {
                    update.setLong(1, id); update.setInt(2, revision);
                    assertEquals(1, update.executeUpdate()); assertEquals(0, update.executeUpdate());
                }
            } finally { connection.rollback(); }
        }
    }

    private static Connection connection() throws SQLException {
        var connection = DriverManager.getConnection(System.getenv("DATAFORGE_TEST_PG_URL"),
                System.getenv().getOrDefault("DATAFORGE_TEST_PG_USER", "codex"),
                System.getenv().getOrDefault("DATAFORGE_TEST_PG_PASSWORD", ""));
        if (!connection.getCatalog().endsWith("_test")) {
            connection.close(); throw new IllegalArgumentException("仅允许在名称以 _test 结尾的隔离库验证");
        }
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) { statement.execute("SET LOCAL search_path TO \"datahub-center\",public"); }
        return connection;
    }

    private static boolean bool(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getBoolean(1);
        }
    }
}

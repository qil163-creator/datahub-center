package cn.iocoder.yudao.framework.etl.core.engine;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.etl.core.connection.*;
import cn.iocoder.yudao.framework.etl.core.model.EtlNodeDef;
import cn.iocoder.yudao.framework.etl.core.runtime.EtlExecutionControl;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class EtlPreviewCancellationPostgreSqlTest {
    static PostgreSQLContainer<?> container;
    static String url, user, password;
    @BeforeAll static void postgres() {
        url = System.getProperty("datahub.test.postgres.url");
        if (url == null) {
            container = new PostgreSQLContainer<>("postgres:16-alpine"); container.start();
            url = container.getJdbcUrl(); user = container.getUsername(); password = container.getPassword();
        } else { user = System.getProperty("datahub.test.postgres.user"); password = System.getProperty("datahub.test.postgres.password", ""); }
    }
    @AfterAll static void stop() { if (container != null) container.stop(); }
    @Test void explicitCancellationStopsBlockedJdbcAndConfirmsWorkersAndPoolExit() throws Exception {
        String schema = "preview_cancel_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection owner = DriverManager.getConnection(url, user, password); var runtime = DbSwitchRuntime.standalone()) {
            try (var statement = owner.createStatement()) {
                statement.execute("CREATE SCHEMA " + schema);
                statement.execute("CREATE TABLE " + schema + ".sample(id integer)");
            }
            try {
                owner.setAutoCommit(false);
                try (var lock = owner.createStatement()) { lock.execute("LOCK TABLE " + schema + ".sample IN ACCESS EXCLUSIVE MODE"); }
                var info = new EtlConnectionInfo(); info.setDatabaseId(1L); info.setSchema(schema); info.setJdbcUrl(url);
                info.setUsername(user); info.setPassword(password); info.setProductType(ProductTypeEnum.POSTGRESQL);
                var source = new EtlPreviewEngine.DatabaseSource(runtime, id -> info, new EtlReadPolicyPort() {
                    public String rewrite(String node, Long database, String sql) { return sql; }
                    public List<Map<String, Object>> filter(String node, Long database, String sql, List<Map<String, Object>> rows) { return rows; }
                }, "preview-cancel-fixture", task -> task);
                var node = new EtlNodeDef(); node.setNo("source"); node.setName("source"); node.setToolType(1);
                node.setToolCode("read-db"); node.setNodeJson("{\"databaseId\":1,\"tableName\":\"sample\",\"ifSql\":0}");
                var control = new EtlExecutionControl();
                ExecutorService pool = Executors.newSingleThreadExecutor();
                try {
                    var future = pool.submit(() -> new EtlPreviewEngine().preview(List.of(node), List.of(), "source", 10, Map.of(), null, source, control));
                    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                    boolean blocked = false;
                    try (var monitor = DriverManager.getConnection(url, user, password);
                         var query = monitor.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE ?")) {
                        query.setString(1, "%" + schema + "%");
                        while (!blocked && System.nanoTime() < deadline) {
                            try (var rows = query.executeQuery()) { rows.next(); blocked = rows.getInt(1) > 0; }
                            if (!blocked) Thread.sleep(10);
                        }
                    }
                    assertTrue(blocked, "Preview JDBC must reach a real PostgreSQL lock wait before cancellation");
                    assertEquals(1, runtime.queries().activeOperations());
                    long started = System.nanoTime(); control.requestStop();
                    var result = future.get(15, TimeUnit.SECONDS);
                    assertFalse(result.success()); assertTrue(result.rows().isEmpty());
                    while (!control.resourcesExited() && System.nanoTime() - started < Duration.ofSeconds(15).toNanos()) Thread.sleep(10);
                    assertTrue(control.resourcesExited(), "Cancellation must confirm both node and JDBC cleanup");
                    assertEquals(0, runtime.queries().activeOperations());
                    assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 15);
                } finally { pool.shutdownNow(); }
            } finally {
                owner.rollback(); owner.setAutoCommit(true);
                try (var statement = owner.createStatement()) { statement.execute("DROP SCHEMA " + schema + " CASCADE"); }
            }
        }
    }
}

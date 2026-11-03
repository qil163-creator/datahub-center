package cn.iocoder.yudao.framework.dbswitch.data.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.domain.MigrationTermination;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.handler.MigrationHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 只连接本测试的一次性 PostgreSQL；不用 H2 代替 JDBC 阻塞/关闭语义。 */
@Timeout(30)
class MigrationTerminationPostgreSqlTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static String jdbcUrl;
    private static String username;
    private static String password;

    @BeforeAll
    static void database() {
        jdbcUrl = System.getProperty("datahub.test.postgres.url");
        if (jdbcUrl != null) {
            // 仅接受显式配置的回环测试库；用例只写自己创建的随机命名空间。
            assertTrue(jdbcUrl.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
            username = System.getProperty("datahub.test.postgres.user", "dataforge_test");
            password = "";
        } else {
            Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                    "需要 Docker 或显式配置的独立本地 PostgreSQL 测试实例");
            POSTGRES.start();
            jdbcUrl = POSTGRES.getJdbcUrl();
            username = POSTGRES.getUsername();
            password = POSTGRES.getPassword();
        }
    }

    @AfterAll
    static void stopContainer() {
        if (POSTGRES.isRunning()) POSTGRES.stop();
    }

    @Test
    void physicalExitNeedsRealPoolReceiptsAndTheLateTableWorkerExit() throws Exception {
        String application = "physical_exit_" + java.util.UUID.randomUUID().toString().replace("-", "");
        String url = jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "ApplicationName=" + application + "&socketTimeout=10";
        var properties = new MigrationRequest();
        var target = properties.getTarget();
        target.setTargetProductType(ProductTypeEnum.POSTGRESQL);
        target.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());
        target.setUrl(url); target.setUsername(username); target.setPassword(password); target.setPoolSize(1);
        var source = new SourceDataSourceProperties();
        source.setSourceType(2); source.setSourceProductType(ProductTypeEnum.POSTGRESQL);
        source.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());
        source.setUrl(url); source.setUsername(username); source.setPassword(password); source.setPoolSize(1);
        source.setSourceSchema("public"); source.setSourceSql("SELECT 1 AS id");
        properties.setSource(List.of(source));
        var handlerEntered = new CountDownLatch(1);
        var releaseHandler = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var service = new MigrationService(properties, ignored -> {
            entered.countDown();
            try {
                while (release.getCount() != 0) {
                    try { release.await(); } catch (InterruptedException ignoredInterrupt) { }
                }
            } finally { exited.countDown(); }
        });
        // Real PostgreSQL pools and controlled-driver receipts; only the table body is replaced.
        var runner = new Thread(() -> {
            try (var handlers = mockStatic(MigrationHandler.class)) {
                var handler = mock(MigrationHandler.class);
                when(handler.get()).thenAnswer(call -> {
                    handlerEntered.countDown();
                    assertTrue(releaseHandler.await(5, SECONDS));
                    return new DbSwitchTableResult();
                });
                handlers.when(() -> MigrationHandler.createInstance(any(TableDescription.class), any(MigrationRequest.class), eq(0),
                        any(), any(), isNull(), any(), isNull())).thenReturn(handler);
                service.run();
            } catch (Throwable t) { failure.set(t); }
        }, "migration-physical-exit-jdbc-test");
        try (var control = DriverManager.getConnection(jdbcUrl, username, password); var statement = control.createStatement()) {
            runner.start();
            assertTrue(handlerEntered.await(10, SECONDS));
            org.awaitility.Awaitility.await().atMost(5, SECONDS).until(() ->
                    java.util.Arrays.stream(runner.getStackTrace()).anyMatch(frame ->
                            frame.getClassName().equals("java.util.concurrent.CompletableFuture") && frame.getMethodName().equals("get")));
            releaseHandler.countDown();
            assertTrue(entered.await(5, SECONDS));
            service.interrupt();
            runner.join(15000);
            assertFalse(runner.isAlive());
            assertNotNull(failure.get());
            assertEquals(MigrationTermination.UNCONFIRMED, service.termination());
            org.awaitility.Awaitility.await().atMost(10, SECONDS).until(() -> {
                try (var rs = statement.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE application_name='" + application + "'")) {
                    rs.next();
                    return rs.getInt(1) == 0;
                }
            });
            // Reflection lets the original RED execute as a missing-contract assertion, not a compilation error.
            var receipt = ((java.util.concurrent.CompletionStage<?>) MigrationService.class.getMethod("physicalExit").invoke(service)).toCompletableFuture();
            assertFalse(receipt.isDone(), "closed PostgreSQL sessions do not prove the still-running table worker exited");
            release.countDown();
            receipt.get(5, SECONDS);
            assertEquals(MigrationTermination.UNCONFIRMED, service.termination(), "the cached conservative termination is never rewritten");
        } finally {
            releaseHandler.countDown();
            release.countDown();
            runner.interrupt();
            runner.join(15000);
            if (entered.getCount() == 0) assertTrue(exited.await(5, SECONDS));
        }
    }

    @Test
    void blockedJdbcIsNotDeclaredTerminatedBeforeItsSessionExits() throws Exception {
        String schema = "termination_" + java.util.UUID.randomUUID().toString().replace("-", "");
        String application = schema;
        long lock = java.util.concurrent.ThreadLocalRandom.current().nextLong();
        var properties = new MigrationRequest();
        var target = properties.getTarget();
        target.setTargetProductType(ProductTypeEnum.POSTGRESQL);
        target.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());
        target.setUrl(jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?")
                + "ApplicationName=" + application + "&currentSchema=" + schema + "&socketTimeout=10");
        target.setUsername(username);
        target.setPassword(password);
        target.setPoolSize(1);
        target.setBeforeSqlScripts("SELECT pg_advisory_lock(" + lock + "); INSERT INTO termination_probe VALUES (1);");
        var service = new MigrationService(properties, ignored -> { });
        var result = new AtomicReference<Throwable>();
        var worker = new Thread(() -> {
            try { service.run(); } catch (Throwable t) { result.set(t); }
        }, "migration-jdbc-test");
        try (var control = DriverManager.getConnection(jdbcUrl, username, password);
             var statement = control.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            statement.execute("CREATE TABLE termination_probe(id integer)");
            statement.execute("SELECT pg_advisory_lock(" + lock + ")");
            try {
                worker.start();
                org.awaitility.Awaitility.await().atMost(10, SECONDS).until(() -> {
                    try (var rs = statement.executeQuery("SELECT count(*) FROM pg_stat_activity "
                            + "WHERE application_name='" + application + "' AND wait_event='advisory'")) {
                        rs.next();
                        return rs.getInt(1) == 1;
                    }
                });
                service.interrupt();
                assertTrue(worker.isAlive());
                assertEquals(MigrationTermination.RUNNING, service.termination());
            } finally {
                statement.execute("SELECT pg_advisory_unlock(" + lock + ")");
                worker.join(15000);
            }
            assertFalse(worker.isAlive());
            assertInstanceOf(java.util.concurrent.CancellationException.class, result.get());
            try (var rs = statement.executeQuery("SELECT count(*) FROM termination_probe")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "取消后不得再提交脚本中的后续写入");
            }
            assertEquals(MigrationTermination.CONFIRMED, service.termination(), () -> String.valueOf(result.get()));
            var evidence = service.scriptExecutions().getFirst();
            assertTrue(evidence.state() == MigrationService.ScriptState.CANCELLED
                    || evidence.state() == MigrationService.ScriptState.PARTIAL);
            assertTrue(evidence.acknowledgedStatements() <= 1);
            org.awaitility.Awaitility.await().atMost(5, SECONDS).until(() -> {
                try (var rs = statement.executeQuery("SELECT count(*) FROM pg_stat_activity "
                        + "WHERE application_name='" + application + "'")) {
                    rs.next();
                    return rs.getInt(1) == 0;
                }
            });
        } finally {
            if (worker.isAlive()) {
                service.interrupt();
                worker.join(15000);
            }
            try (var cleanup = DriverManager.getConnection(jdbcUrl, username, password);
                 var statement = cleanup.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}

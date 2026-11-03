package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.MariaDbSourceGeneration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.*;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Each test owns its schemas/principal. No production source is initialized or altered. */
@EnabledIfSystemProperty(named = "datahub.test.mariadb.url", matches = "jdbc:mariadb://127[.]0[.]0[.]1:[0-9]+/")
class MariaDbManagedGenerationMariaDbTest {
    private Connection admin;
    private Statement sql;
    private String schema, metadataSchema, reader, password;
    private SourceGenerationPin pin;
    private final IDatabaseInterface database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.MARIADB);

    @BeforeEach void setup() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        schema = "m26_data_" + suffix; metadataSchema = "m26_meta_" + suffix; reader = "m26_" + suffix;
        password = UUID.randomUUID().toString();
        admin = open(); sql = admin.createStatement(); sql.setQueryTimeout(10);
        sql.execute("CREATE DATABASE " + schema); sql.execute("CREATE DATABASE " + metadataSchema);
        sql.execute("CREATE TABLE " + schema + ".sample(id BIGINT PRIMARY KEY, value_col BIGINT) ENGINE=InnoDB");
        sql.execute("INSERT INTO " + schema + ".sample VALUES(1,NULL)");
        pin = MariaDbSourceGeneration.initialize(admin, metadataSchema);
        sql.execute("CREATE USER '" + reader + "'@'%' IDENTIFIED BY '" + password + "'");
        sql.execute("GRANT SELECT ON " + schema + ".* TO '" + reader + "'@'%'");
        sql.execute("GRANT SELECT ON " + metadataSchema + ".* TO '" + reader + "'@'%'");
        sql.execute("GRANT PROCESS ON *.* TO '" + reader + "'@'%'");
    }

    @AfterEach void cleanup() throws Exception {
        try {
            if (admin != null && !admin.isClosed()) {
                if (!admin.getAutoCommit()) { admin.rollback(); admin.setAutoCommit(true); }
                try (var cleanup = admin.createStatement()) {
                    cleanup.setQueryTimeout(10);
                    cleanup.execute("DROP DATABASE IF EXISTS " + schema);
                    cleanup.execute("DROP DATABASE IF EXISTS " + metadataSchema);
                    cleanup.execute("DROP USER IF EXISTS '" + reader + "'@'%'");
                }
            }
        } finally { if (sql != null) sql.close(); if (admin != null) admin.close(); }
    }

    @Test void initializationIsIdempotentAndNativeTableChangesInvalidateIdentity() throws Exception {
        assertEquals(pin, MariaDbSourceGeneration.initialize(admin, metadataSchema));
        String before = fingerprint(admin, pin);
        sql.execute("UPDATE " + schema + ".sample SET value_col=0"); assertEquals(before, fingerprint(admin, pin));
        sql.execute("TRUNCATE TABLE " + schema + ".sample"); assertNotEquals(before, fingerprint(admin, pin));
        String truncated = fingerprint(admin, pin);
        sql.execute("DROP TABLE " + schema + ".sample");
        sql.execute("CREATE TABLE " + schema + ".sample(id BIGINT PRIMARY KEY,value_col BIGINT) ENGINE=InnoDB");
        assertNotEquals(truncated, fingerprint(admin, pin));
        assertTrue(admin.getAutoCommit()); assertFalse(admin.isClosed());
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.queryRelationFingerprint(admin, schema + ".sample"));
    }

    @Test void readOnlyPrincipalCannotInitializeFenceRotateOrWriteButCanReadProof() throws Exception {
        try (var runtime = readOnly(); var query = runtime.createStatement()) {
            String before = fingerprint(runtime, pin); assertTrue(before.matches("[0-9a-f]{64}"));
            assertNotEquals(fingerprint(admin, pin), before, "effective principal belongs to the identity");
            assertThrows(SQLException.class, () -> query.execute("UPDATE " + schema + ".sample SET value_col=0"));
            assertThrows(SQLException.class, () -> MariaDbSourceGeneration.fence(runtime, pin));
            assertThrows(SQLException.class, () -> MariaDbSourceGeneration.rotate(runtime, pin, UUID.randomUUID().toString()));
            assertEquals(before, fingerprint(runtime, pin));
            sql.execute("REVOKE PROCESS ON *.* FROM '" + reader + "'@'%'");
            // MariaDB global privileges are session-cached: revocation is proved on a new connection.
            try (var reconnected = readOnly()) { assertThrows(SQLException.class, () -> fingerprint(reconnected, pin)); }
            sql.execute("GRANT PROCESS ON *.* TO '" + reader + "'@'%'");
            sql.execute("REVOKE SELECT ON " + metadataSchema + ".* FROM '" + reader + "'@'%'");
            assertThrows(SQLException.class, () -> fingerprint(runtime, pin));
            sql.execute("GRANT SELECT ON " + metadataSchema + ".* TO '" + reader + "'@'%'");
            assertEquals(before, fingerprint(runtime, pin));
        }
    }

    @Test void fenceAndRotationRejectStalePinsAndAreIdempotentWithoutGenerationReuse() throws Exception {
        String before = fingerprint(admin, pin);
        String next = UUID.randomUUID().toString();
        assertThrows(SQLException.class, () -> MariaDbSourceGeneration.rotate(admin, pin, next));
        MariaDbSourceGeneration.fence(admin, pin); MariaDbSourceGeneration.fence(admin, pin);
        assertThrows(SQLException.class, () -> fingerprint(admin, pin));
        var updated = MariaDbSourceGeneration.rotate(admin, pin, next);
        assertEquals(updated, MariaDbSourceGeneration.rotate(admin, pin, next));
        assertNotEquals(before, fingerprint(admin, updated));
        assertThrows(SQLException.class, () -> fingerprint(admin, pin));
        assertThrows(SQLException.class, () -> MariaDbSourceGeneration.fence(admin, pin));
        MariaDbSourceGeneration.fence(admin, updated);
        assertThrows(SQLException.class, () -> MariaDbSourceGeneration.rotate(admin, updated, pin.generation()));
        var third = MariaDbSourceGeneration.rotate(admin, updated, UUID.randomUUID().toString());
        assertThrows(SQLException.class, () -> MariaDbSourceGeneration.rotate(admin, pin, next));
        assertNotNull(fingerprint(admin, third)); assertTrue(admin.getAutoCommit());
    }

    @Test void concurrentRotationHasOneWinnerAndDoesNotLeaveTheLoserHistory() throws Exception {
        MariaDbSourceGeneration.fence(admin, pin);
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<SourceGenerationPin> a = executor.submit(() -> rotateOnNewConnection(start, first));
            Future<SourceGenerationPin> b = executor.submit(() -> rotateOnNewConnection(start, second));
            start.countDown();
            var aa = a.get(20, TimeUnit.SECONDS); var bb = b.get(20, TimeUnit.SECONDS);
            assertNotEquals(aa == null, bb == null, "exactly one CAS must win");
            var winner = aa == null ? bb : aa; String loser = aa == null ? first : second;
            assertNotNull(fingerprint(admin, winner));
            MariaDbSourceGeneration.fence(admin, winner);
            assertNotNull(fingerprint(admin, MariaDbSourceGeneration.rotate(admin, winner, loser)));
        }
    }

    @Test void explicitTransactionIsRejectedEvenWhenJdbcAutoCommitRemainsTrue() throws Exception {
        try (var runtime = readOnly(); var query = runtime.createStatement()) {
            assertTrue(runtime.getAutoCommit());
            query.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT");
            assertTrue(runtime.getAutoCommit());
            assertThrows(SQLException.class, () -> fingerprint(runtime, pin));
            MariaDbSourceGeneration.fence(admin, pin);
            var next = MariaDbSourceGeneration.rotate(admin, pin, UUID.randomUUID().toString());
            assertThrows(SQLException.class, () -> fingerprint(runtime, pin));
            try (var state = query.executeQuery("SELECT @@in_transaction")) { assertTrue(state.next()); assertEquals(1, state.getInt(1)); }
            query.execute("ROLLBACK"); assertNotNull(fingerprint(runtime, next));
        }
        admin.setAutoCommit(false);
        assertThrows(SQLException.class, () -> fingerprint(admin, pin));
        assertFalse(admin.getAutoCommit());
    }

    @Test void temporaryShadowsViewsAndPartitionsNeverBorrowPermanentIdentity() throws Exception {
        sql.execute("CREATE TEMPORARY TABLE " + schema + ".sample(id BIGINT,value_col BIGINT)");
        assertThrows(SQLException.class, () -> fingerprint(admin, pin));
        sql.execute("DROP TEMPORARY TABLE " + schema + ".sample");
        sql.execute("CREATE VIEW " + schema + ".sample_view AS SELECT * FROM " + schema + ".sample");
        assertThrows(SQLException.class, () -> database.queryRelationFingerprint(admin, schema + ".sample_view", pin));
        sql.execute("CREATE TABLE " + schema + ".partitioned(id BIGINT PRIMARY KEY) PARTITION BY HASH(id) PARTITIONS 2");
        assertThrows(SQLException.class, () -> database.queryRelationFingerprint(admin, schema + ".partitioned", pin));
        assertNotNull(fingerprint(admin, pin));
    }

    @Test void sourcePinMismatchAndUnverifiedProductsCannotFallBackToUnmanagedProof() throws Exception {
        var foreign = new SourceGenerationPin(pin.schema(), UUID.randomUUID().toString(), pin.generation());
        assertThrows(SQLException.class, () -> fingerprint(admin, foreign));
        for (var product : new ProductTypeEnum[]{ProductTypeEnum.MYSQL, ProductTypeEnum.POSTGRESQL, ProductTypeEnum.HIVE}) {
            assertThrows(SQLFeatureNotSupportedException.class,
                    () -> cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(product).queryRelationFingerprint(admin, schema + ".sample", pin));
        }
    }

    @Test void metadataShadowOrCorruptionCannotBorrowAValidPin() throws Exception {
        String marker = metadataSchema + "." + MariaDbSourceGeneration.MARKER_TABLE;
        String history = metadataSchema + "." + MariaDbSourceGeneration.HISTORY_TABLE;
        sql.execute("CREATE TEMPORARY TABLE " + marker + "(singleton_id TINYINT, schema_version SMALLINT,instance_id CHAR(36),generation CHAR(36),state VARCHAR(6))");
        assertThrows(SQLException.class, () -> fingerprint(admin, pin));
        sql.execute("DROP TEMPORARY TABLE " + marker);
        assertNotNull(fingerprint(admin, pin));
        sql.execute("DELETE FROM " + history);
        assertThrows(SQLException.class, () -> fingerprint(admin, pin));
        assertThrows(SQLException.class, () -> MariaDbSourceGeneration.initialize(admin, metadataSchema));
        try (var count = sql.executeQuery("SELECT COUNT(*) FROM " + history)) { assertTrue(count.next()); assertEquals(0, count.getInt(1)); }
    }

    @Test void successfulCommitWithLostResponseReconcilesTheSameRotationToken() throws Exception {
        MariaDbSourceGeneration.fence(admin, pin);
        String next = UUID.randomUUID().toString();
        var uncertain = org.mockito.Mockito.spy(admin);
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new SQLException("Fixture lost commit response", "08007");
        }).when(uncertain).commit();
        assertThrows(SQLException.class, () -> MariaDbSourceGeneration.rotate(uncertain, pin, next));
        var recovered = MariaDbSourceGeneration.rotate(admin, pin, next);
        assertEquals(next, recovered.generation()); assertNotNull(fingerprint(admin, recovered));
    }

    @Test void relationParsingDoesNotWidenSqlAuthorizationOrAcceptExtraContent() throws Exception {
        assertEquals(fingerprint(admin, pin), database.queryRelationFingerprint(admin, "`" + schema + "`.`sample`", pin));
        for (String invalid : new String[]{schema + ".sample;SELECT 1", schema + ".sample s", schema + ".sample/*x*/", "'sample'"}) {
            assertThrows(IllegalArgumentException.class, () -> database.queryRelationFingerprint(admin, invalid, pin));
        }
        var bound = cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard
                .parseBound(ProductTypeEnum.MARIADB, "SELECT id FROM " + schema + ".sample", "other_db");
        assertEquals(schema, bound.getSchemaName());
        assertEquals("sample", bound.getTableName());
        assertTrue(bound.getNormalizedSql().contains("`" + schema + "`.`sample`"), bound.getNormalizedSql());
    }

    @Test void driverPropertiesAndHaModesCannotBypassTheConnectionProfile() throws Exception {
        var properties = new java.util.Properties(); properties.setProperty("user", "root"); properties.setProperty("password", "");
        properties.setProperty("permitRedirect", "false"); properties.setProperty("transactionReplay", "true");
        try (var replay = DriverManager.getConnection(System.getProperty("datahub.test.mariadb.url"), properties)) {
            assertThrows(SQLFeatureNotSupportedException.class, () -> fingerprint(replay, pin));
        }
        properties.setProperty("transactionReplay", "false"); properties.setProperty("permitRedirect", "true");
        try (var redirect = DriverManager.getConnection(System.getProperty("datahub.test.mariadb.url"), properties)) {
            assertThrows(SQLFeatureNotSupportedException.class, () -> fingerprint(redirect, pin));
        }
        String sequential = verifiedUrl().replace("jdbc:mariadb://", "jdbc:mariadb:sequential://");
        try (var ha = DriverManager.getConnection(sequential, "root", "")) {
            assertThrows(SQLFeatureNotSupportedException.class, () -> fingerprint(ha, pin));
        }
    }

    @Test void nativeAndIsolatedDriverBothWorkThroughHikariMetadataProxy() throws Exception {
        var driverJar = org.mariadb.jdbc.Driver.class.getProtectionDomain().getCodeSource().getLocation();
        var artifact = java.nio.file.Path.of(driverJar.toURI());
        var specification = new cn.iocoder.yudao.framework.dbswitch.driver.VerifiedDriverTestHandle.Spec(
                artifact.toString(), cn.iocoder.yudao.framework.dbswitch.driver.VerifiedDriverTestHandle.sha256(artifact),
                ProductTypeEnum.MARIADB.getDriveClassName());
        try (var deployed = new cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers(
                java.util.Map.of(ProductTypeEnum.MARIADB, specification));
             var application = new cn.iocoder.yudao.framework.dbswitch.driver.DriverRuntimeRegistry()) {
            for (boolean isolated : new boolean[]{false, true}) {
                var properties = new java.util.Properties();
                var lease = isolated ? deployed.find(ProductTypeEnum.MARIADB).orElseThrow().acquire()
                        : application.acquireApplication(ProductTypeEnum.MARIADB, ProductTypeEnum.MARIADB.getDriveClassName());
                try (var source = new cn.iocoder.yudao.framework.dbswitch.common.entity.InvisibleDataSource(
                        lease, verifiedUrl(), reader, password, properties)) {
                var config = new com.zaxxer.hikari.HikariConfig();
                config.setDataSource(source); config.setMaximumPoolSize(1); config.setMinimumIdle(0);
                config.setConnectionTimeout(5000); config.setPoolName("managed-maria-fixture");
                try (var pool = new com.zaxxer.hikari.HikariDataSource(config); var connection = pool.getConnection()) {
                    assertTrue(connection.getMetaData().getClass().getName().contains("HikariProxy"));
                    assertSame(source.getClassLoader(), connection.getMetaData().unwrap(DatabaseMetaData.class).getClass().getClassLoader());
                    assertEquals(isolated, source.getClassLoader() != org.mariadb.jdbc.Driver.class.getClassLoader());
                    assertNotNull(fingerprint(connection, pin)); assertFalse(connection.isClosed());
                }
                }
            }
        }
    }

    private SourceGenerationPin rotateOnNewConnection(CountDownLatch start, String next) throws Exception {
        try (var connection = open()) {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            try { return MariaDbSourceGeneration.rotate(connection, pin, next); }
            catch (SQLException rejected) { assertTrue(connection.getAutoCommit()); return null; }
        }
    }
    private String verifiedUrl() { return cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredSourceGenerations.managedJdbcUrl(System.getProperty("datahub.test.mariadb.url")); }
    private Connection open() throws SQLException { return DriverManager.getConnection(verifiedUrl(), "root", ""); }
    private Connection readOnly() throws SQLException { return DriverManager.getConnection(verifiedUrl(), reader, password); }
    private String fingerprint(Connection connection, SourceGenerationPin binding) throws SQLException {
        return database.queryRelationFingerprint(connection, schema + ".sample", binding);
    }
}

package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.config.*;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.core.model.SourceGenerationPin;
import cn.iocoder.yudao.framework.dbswitch.core.util.OceanbaseSourceGeneration;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbcommon.domain.CursorReadRequest;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Typed cursor and native UPSERT on a real OceanBase CE MySQL tenant through the isolated verified driver. */
@Timeout(180)
@EnabledIfSystemProperty(named = "datahub.test.oceanbase.url", matches = "jdbc:oceanbase://127[.]0[.]0[.]1:[0-9]+/")
@EnabledIfSystemProperty(named = "datahub.test.oceanbase.driver", matches = ".+")
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class MigrationCursorOceanbaseTest {
    private static final ProductTypeEnum OB = ProductTypeEnum.OCEANBASE, PG = ProductTypeEnum.POSTGRESQL;
    private static final String MAX38 = "99999999999999999999999999999999999999";
    private final List<String> fields = List.of("wm", "k1", "k2", "v");
    private final List<String> keys = List.of("k2", "k1");
    private final BatchLimits limits = new BatchLimits(2, 65536, 16384, 65535, 1000);
    private final AtomicReference<CursorCheckpoint> saved = new AtomicReference<>();
    private final List<CursorCheckpoint> history = new ArrayList<>();
    private ConfiguredJdbcDrivers drivers;
    private DbSwitchRuntime runtime;
    private String schema;
    private Connection ob, pg;
    private SourceGenerationPin pin;

    @BeforeEach void setup() throws Exception {
        Path jar = Path.of(System.getProperty("datahub.test.oceanbase.driver")).toRealPath();
        var driver = new DbSwitchProperties.Driver(); driver.setPath(jar.toString());
        driver.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        drivers = new ConfiguredJdbcDrivers(Map.of(OB, driver));
        runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), DbSwitchOptions.defaults(), drivers);
        ob = open(OB); pg = open(PG);
        schema = "ob_cursor_" + UUID.randomUUID().toString().replace("-", "");
        sql(ob, "CREATE DATABASE " + q(schema)); sql(pg, "CREATE SCHEMA " + schema);
        // The deployed pin names the tenant's persisted server_uuid and is resolved through the deployment configuration.
        var deployed = OceanbaseSourceGeneration.initialize(ob, schema, 15);
        var generation = new DbSwitchProperties.SourceGeneration(); generation.setProduct(OB); generation.setRestoreRotationRequired(true);
        generation.setTargetFingerprint(ConfiguredSourceGenerations.targetFingerprint(OB, url(OB), user(OB)));
        generation.setSchema(deployed.schema()); generation.setInstanceId(deployed.instanceId()); generation.setGeneration(deployed.generation());
        pin = new ConfiguredSourceGenerations(Map.of("oceanbase", generation)).find(OB, url(OB), user(OB)).orElseThrow();
    }
    @AfterEach void cleanup() throws Exception {
        try { if (pg != null) try (var c = pg) { sql(c, "DROP SCHEMA IF EXISTS " + schema + " CASCADE"); } }
        finally {
            try { if (runtime != null && schema != null) { reopen(); try (var c = ob) { sql(c, "DROP DATABASE IF EXISTS " + q(schema)); } } }
            finally { if (runtime != null) runtime.close(); if (drivers != null) drivers.close(); }
        }
    }

    // ---- public service, three directions --------------------------------------------------------------

    @ParameterizedTest @CsvSource({"OCEANBASE,OCEANBASE", "OCEANBASE,POSTGRESQL", "POSTGRESQL,OCEANBASE"})
    void publicServiceMovesEqualWatermarkRowsAndRewindsThemOnResume(ProductTypeEnum source, ProductTypeEnum target) throws Exception {
        create(source, "s"); create(target, "t"); rows(source, 5);
        var request = request(source, target);
        var first = new MigrationService(request, ignored -> { }, null, runtime).run();
        assertTrue(first.getIfAllSuccess().get(), () -> first.getTableResultList().toString());
        assertEquals(5, first.getTotalRowCount().get()); assertEquals(5, count(target, "t")); assertTrue(saved.get().complete());
        assertEquals(source, saved.get().sourceProduct()); assertEquals(target, saved.get().targetProduct()); assertEquals(2, saved.get().version());
        assertEquals(target == OB ? CommitAcknowledgement.JDBC_COMMIT_RETURNED : CommitAcknowledgement.POSTGRESQL_XID8_VERIFIED, saved.get().lastCommitAcknowledgement());
        if (target == OB) assertNull(saved.get().lastCommit());
        // A row committed later with the already applied watermark and a smaller key must not be skipped.
        sql(control(source), "INSERT INTO " + t(source, "s") + " VALUES(10,0,0,'late equal watermark')");
        sql(control(source), "UPDATE " + t(source, "s") + " SET v='updated' WHERE k1=5");
        request.getTarget().setCursorRunBindings(Map.of("s", binding(this::save)));
        var resumed = new MigrationService(request, ignored -> { }, null, runtime).run();
        assertTrue(resumed.getIfAllSuccess().get(), () -> resumed.getTableResultList().toString());
        assertEquals(6, count(target, "t"));
        assertEquals(texts(source, "SELECT k1,v FROM " + t(source, "s") + " ORDER BY k1"), texts(target, "SELECT k1,v FROM " + t(target, "t") + " ORDER BY k1"));
    }

    // ---- recovery contract ---------------------------------------------------------------------------

    @ParameterizedTest @CsvSource({"OCEANBASE,OCEANBASE", "OCEANBASE,POSTGRESQL", "POSTGRESQL,OCEANBASE"})
    void commitBeforeCheckpointCrashReplaysTheFrozenWindowThenAdvances(ProductTypeEnum source, ProductTypeEnum target) throws Exception {
        create(source, "s"); create(target, "t"); rows(source, 5);
        var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> transfer(source, target, ds(target), next -> calls.incrementAndGet() != 3 && save(next)));
        // Both batches were committed; only the first one was recorded.
        assertEquals(4, count(target, "t")); assertEquals(1, saved.get().sequence()); assertFalse(saved.get().complete());
        sql(control(source), "INSERT INTO " + t(source, "s") + " VALUES(10,0,0,'late'),(11,99,99,'next window')");
        transfer(source, target, ds(target), this::save);
        assertEquals(6, count(target, "t")); assertTrue(saved.get().complete());
        transfer(source, target, ds(target), this::save);
        assertEquals(7, count(target, "t"));
    }

    @Test void actualCommitWithLostAcknowledgementNeverAdvancesTheCheckpoint() throws Exception {
        create(OB, "s"); create(OB, "t"); rows(OB, 2);
        DataSource fault = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection actual = open(OB);
                return (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    try {
                        Object value = method.invoke(actual, arguments);
                        if (method.getName().equals("commit")) throw new SQLException("fixture lost acknowledgement");
                        return value;
                    } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
            @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        };
        assertThrows(UnconfirmedWriteException.class, () -> transfer(OB, OB, fault, this::save));
        reopen();
        assertEquals(2, count(OB, "t")); assertNull(saved.get().applied()); assertEquals(0, saved.get().sequence()); assertFalse(saved.get().complete());
        transfer(OB, OB, ds(OB), this::save);
        assertTrue(saved.get().complete()); assertEquals(2, count(OB, "t"));
    }

    @Test void frozenEmptyWindowRemainsEmptyUntilTheNextCompletedRun() throws Exception {
        create(OB, "s"); create(OB, "t"); var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> transfer(OB, OB, ds(OB), next -> calls.incrementAndGet() == 1 && save(next)));
        assertNull(saved.get().fixedUpper()); assertFalse(saved.get().complete());
        rows(OB, 1);
        transfer(OB, OB, ds(OB), this::save);
        assertEquals(0, count(OB, "t")); assertTrue(saved.get().complete());
        transfer(OB, OB, ds(OB), this::save);
        assertEquals(1, count(OB, "t"));
    }

    @ParameterizedTest @ValueSource(strings = {"TARGET_RECREATE", "TARGET_TRUNCATE", "SOURCE_RECREATE", "SOURCE_TRUNCATE", "ROTATED_GENERATION"})
    void resumeRejectsChangedTabletsOrDeployedGenerationBeforeAnyWrite(String mode) throws Exception {
        create(OB, "s"); create(OB, "t"); rows(OB, 1);
        transfer(OB, OB, ds(OB), this::save); assertTrue(saved.get().complete());
        switch (mode) {
            case "TARGET_RECREATE" -> { sql(ob, "DROP TABLE " + t(OB, "t")); create(OB, "t"); }
            case "TARGET_TRUNCATE" -> sql(ob, "TRUNCATE TABLE " + t(OB, "t"));
            case "SOURCE_RECREATE" -> { sql(ob, "DROP TABLE " + t(OB, "s")); create(OB, "s"); rows(OB, 1); }
            case "SOURCE_TRUNCATE" -> { sql(ob, "TRUNCATE TABLE " + t(OB, "s")); rows(OB, 1); }
            default -> pin = new SourceGenerationPin(pin.schema(), pin.instanceId(), UUID.randomUUID().toString());
        }
        long before = count(OB, "t"); var checkpoint = saved.get();
        assertThrows(IllegalArgumentException.class, () -> transfer(OB, OB, ds(OB), this::save));
        assertEquals(before, count(OB, "t")); assertSame(checkpoint, saved.get());
    }

    @Test void pinMustNameTheActualTenantBeforeTheFirstRead() throws Exception {
        create(OB, "s"); create(OB, "t"); rows(OB, 1);
        pin = new SourceGenerationPin(pin.schema(), UUID.randomUUID().toString(), pin.generation());
        assertThrows(IllegalArgumentException.class, () -> transfer(OB, OB, ds(OB), this::save));
        assertNull(saved.get()); assertEquals(0, count(OB, "t"));
        var reader = runtime.reader(ds(OB), OB);
        assertThrows(IllegalArgumentException.class, () -> reader.openCursor(new CursorReadRequest(schema, "s", fields, "wm", keys, null, null, null, limits)));
    }

    // ---- snapshot ------------------------------------------------------------------------------------

    @Test void repeatableReadSnapshotIsStableAcrossPagesUnderConcurrentDml() throws Exception {
        create(OB, "s"); rows(OB, 3);
        var reader = runtime.reader(ds(OB), OB); reader.setFetchSize(1);
        try (var cursor = reader.openCursor(new CursorReadRequest(schema, "s", fields, "wm", keys, null, null, null, limits, false, pin))) {
            assertTrue(cursor.next());
            sql(ob, "UPDATE " + t(OB, "s") + " SET v='changed'");
            sql(ob, "DELETE FROM " + t(OB, "s") + " WHERE k1=3");
            sql(ob, "INSERT INTO " + t(OB, "s") + " VALUES(10,0,0,'late equal watermark'),(11,9,9,'after snapshot')");
            int read = 1;
            while (cursor.next()) { assertEquals("original", cursor.row()[3]); read++; }
            assertEquals(3, read);
        }
        assertEquals(4, count(OB, "s"));
    }

    @Test void catalogInspectionAndDataShareTheSnapshotTakenByTheFirstRead() throws Exception {
        create(OB, "s"); rows(OB, 3); var injected = new AtomicInteger();
        // A row with a higher watermark is committed after the catalog inspection, right before the first data statement.
        DataSource racing = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection actual = open(OB);
                return (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    try {
                        Object value = method.invoke(actual, arguments);
                        if (!method.getName().equals("createStatement")) return value;
                        var statement = (java.sql.Statement) value;
                        return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.Statement.class}, (ignored, call, parameters) -> {
                            try {
                                if (call.getName().equals("executeQuery") && String.valueOf(parameters[0]).endsWith("DESC LIMIT 1") && injected.incrementAndGet() == 1)
                                    sql(ob, "INSERT INTO " + t(OB, "s") + " VALUES(99,99,99,'committed after the snapshot')");
                                return call.invoke(statement, parameters);
                            } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                        });
                    } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
            @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        };
        var reader = runtime.reader(racing, OB); reader.setFetchSize(1); int read = 0;
        try (var cursor = reader.openCursor(new CursorReadRequest(schema, "s", fields, "wm", keys, null, null, null, limits, false, pin))) {
            assertEquals(1, injected.get()); assertEquals(4, count(OB, "s"));
            // The fixed upper bound and every page predate the injected commit.
            assertEquals("10.000000000000000000", cursor.upperBound().values().getFirst().value());
            while (cursor.next()) { assertEquals("original", cursor.row()[3]); read++; }
        }
        assertEquals(3, read);
        try (var cursor = runtime.reader(ds(OB), OB).openCursor(new CursorReadRequest(schema, "s", fields, "wm", keys, null, null, null, limits, false, pin))) {
            assertEquals("99.000000000000000000", cursor.upperBound().values().getFirst().value());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"TRUNCATE", "DROP_CREATE", "TRUNCATE_PARTITION"})
    void truncateDuringTheSnapshotFailsClosedAndTheOldPlanIsRejected(String mode) throws Exception {
        String definition = "(wm DECIMAL(38,18) NOT NULL,k1 BIGINT NOT NULL,k2 DECIMAL(38,0) NOT NULL,v VARCHAR(80),PRIMARY KEY(k2,k1))";
        sql(ob, "CREATE TABLE " + t(OB, "s") + definition + (mode.equals("TRUNCATE_PARTITION")
                ? " PARTITION BY RANGE(k1)(PARTITION p1 VALUES LESS THAN (4),PARTITION p2 VALUES LESS THAN (MAXVALUE))" : ""));
        create(OB, "t"); rows(OB, 5);
        var calls = new AtomicInteger();
        // OceanBase does not block the DDL; the next snapshot page must fail instead of returning other data.
        assertThrows(SQLException.class, () -> transfer(OB, OB, ds(OB), next -> {
            if (calls.incrementAndGet() == 2) {
                try {
                    switch (mode) {
                        case "TRUNCATE" -> sql(ob, "TRUNCATE TABLE " + t(OB, "s"));
                        case "DROP_CREATE" -> { sql(ob, "DROP TABLE " + t(OB, "s")); sql(ob, "CREATE TABLE " + t(OB, "s") + definition); }
                        default -> sql(ob, "ALTER TABLE " + t(OB, "s") + " TRUNCATE PARTITION p1");
                    }
                    sql(ob, "INSERT INTO " + t(OB, "s") + " VALUES(10,1,1,'reloaded'),(10,3,1,'reloaded')" + (mode.equals("TRUNCATE_PARTITION") ? "" : ",(10,5,1,'reloaded')"));
                } catch (SQLException failure) { throw new IllegalStateException(failure); }
            }
            return save(next);
        }));
        assertEquals(2, count(OB, "t")); assertEquals(1, saved.get().sequence()); assertFalse(saved.get().complete());
        assertEquals(List.of("original", "original"), texts(OB, "SELECT v FROM " + t(OB, "t") + " ORDER BY k2,k1"));
        var checkpoint = saved.get();
        assertThrows(IllegalArgumentException.class, () -> transfer(OB, OB, ds(OB), this::save));
        assertEquals(2, count(OB, "t")); assertSame(checkpoint, saved.get());
    }

    // ---- ordering, keys and precision ----------------------------------------------------------------

    @ParameterizedTest @CsvSource({"DATETIME,OCEANBASE", "DATETIME,POSTGRESQL", "TIMESTAMP,OCEANBASE", "TIMESTAMP,POSTGRESQL"})
    void subSecondWatermarksPageInsideOneSecondWithoutTruncation(String type, ProductTypeEnum target) throws Exception {
        sql(ob, "CREATE TABLE " + t(OB, "s") + "(wm " + type + "(6) NOT NULL,id BIGINT PRIMARY KEY,v VARCHAR(40))");
        sql(control(target), "CREATE TABLE " + t(target, "t") + "(wm " + (target == OB ? type + "(6)" : type.equals("DATETIME") ? "TIMESTAMP(6)" : "TIMESTAMPTZ(6)")
                + " NOT NULL,id BIGINT PRIMARY KEY,v VARCHAR(40))");
        // 1988-04-17 02:30 does not exist in the JVM default zone of this fixture host (Asia/Shanghai DST gap).
        sql(ob, "INSERT INTO " + t(OB, "s") + " VALUES('1988-04-17 02:30:00.123456',1,'civil gap'),('2026-03-08 02:30:00.000001',2,'a'),"
                + "('2026-03-08 02:30:00.000002',3,'b'),('2026-03-08 02:30:00.000002',4,'c'),('2026-03-08 02:30:00.999999',5,'d'),('2026-03-08 02:30:01.000000',6,'e')");
        var names = List.of("wm", "id", "v");
        transfer(OB, target, names, List.of("id"), this::save);
        sql(ob, "SET SESSION time_zone='+00:00'");
        String sourceText = "SELECT id,CAST(wm AS CHAR),v FROM " + t(OB, "s") + " ORDER BY id";
        String targetText = target == OB ? "SELECT id,CAST(wm AS CHAR),v FROM " + t(OB, "t") + " ORDER BY id"
                : "SELECT id,to_char(wm" + (type.equals("TIMESTAMP") ? " AT TIME ZONE 'UTC'" : "") + ",'YYYY-MM-DD HH24:MI:SS.US'),v FROM " + t(PG, "t") + " ORDER BY id";
        assertEquals(6, count(target, "t")); assertEquals(texts(OB, sourceText), texts(target, targetText));
        if (type.equals("DATETIME")) assertTrue(texts(target, targetText).contains("1|1988-04-17 02:30:00.123456|civil gap"));
        // Applied positions keep microseconds: the page boundary falls between two rows of the same microsecond.
        var applied = history.stream().filter(checkpoint -> checkpoint.applied() != null && !checkpoint.complete())
                .map(checkpoint -> checkpoint.applied().values().get(0).value() + "/" + checkpoint.applied().values().get(1).value()).toList();
        assertEquals(type.equals("DATETIME")
                ? List.of("2026-03-08T02:30:00.000001/2", "2026-03-08T02:30:00.000002/4", "2026-03-08T02:30:01/6")
                : List.of("2026-03-07T18:30:00.000001Z/2", "2026-03-07T18:30:00.000002Z/4", "2026-03-07T18:30:01Z/6"), applied);
        sql(ob, "SET SESSION time_zone='+08:00'");
        sql(ob, "INSERT INTO " + t(OB, "s") + " VALUES('2026-03-08 02:30:01.000000',0,'late equal microsecond'),('2026-03-08 02:30:00.999999',7,'older')");
        transfer(OB, target, names, List.of("id"), this::save);
        sql(ob, "SET SESSION time_zone='+00:00'");
        // Only the equal-watermark group is rewound; an older late row violates the declared monotonic contract.
        assertEquals(7, count(target, "t"));
        assertEquals(texts(OB, sourceText.replace(" ORDER BY", " WHERE id<>7 ORDER BY")), texts(target, targetText));
    }

    @Test void lexicographicPagesKeepNegativeDecimal38AndSignedIntegerBoundaries() throws Exception {
        create(OB, "s");
        sql(ob, "INSERT INTO " + t(OB, "s") + " VALUES(-0.000000000000000001,-9223372036854775808,-" + MAX38 + ",'first'),"
                + "(-0.000000000000000001,9223372036854775807,-" + MAX38 + ",'second'),(-0.000000000000000001,0," + MAX38 + ",'third'),(0,-1,-" + MAX38 + ",'fourth')");
        var reader = runtime.reader(ds(OB), OB); reader.setFetchSize(1); var seen = new ArrayList<String>();
        try (var cursor = reader.openCursor(new CursorReadRequest(schema, "s", fields, "wm", keys, null, null, null, limits, false, pin))) {
            assertEquals(List.of("0.000000000000000000", "-" + MAX38, "-1"), cursor.upperBound().values().stream().map(value -> value.value()).toList());
            while (cursor.next()) seen.add((String) cursor.row()[3]);
        }
        assertEquals(List.of("first", "second", "third", "fourth"), seen);
    }

    @Test void unsignedBigintKeyAndWatermarkSurviveEncodingAndPostgresReplay() throws Exception {
        sql(ob, "CREATE TABLE " + t(OB, "s") + "(wm BIGINT UNSIGNED NOT NULL,id BIGINT UNSIGNED PRIMARY KEY,v VARCHAR(20))");
        sql(pg, "CREATE TABLE " + t(PG, "t") + "(wm NUMERIC(20,0) NOT NULL,id NUMERIC(20,0) PRIMARY KEY,v VARCHAR(20))");
        sql(ob, "INSERT INTO " + t(OB, "s") + " VALUES(18446744073709551615,18446744073709551615,'max'),(9223372036854775808,9223372036854775808,'above signed'),(0,0,'zero')");
        transfer(OB, PG, List.of("wm", "id", "v"), List.of("id"), this::save);
        assertEquals("18446744073709551615", saved.get().fixedUpper().values().getFirst().value());
        assertEquals(java.sql.Types.NUMERIC, saved.get().fixedUpper().values().getFirst().jdbcType());
        assertEquals(List.of("0|0|zero", "9223372036854775808|9223372036854775808|above signed", "18446744073709551615|18446744073709551615|max"),
                texts(PG, "SELECT wm::text AS w,id::text AS i,v FROM " + t(PG, "t") + " ORDER BY id"));
        // A signed 64-bit PostgreSQL key cannot hold the source domain: refused before the first write.
        sql(pg, "CREATE TABLE " + t(PG, "narrow") + "(wm NUMERIC(20,0) NOT NULL,id BIGINT PRIMARY KEY,v VARCHAR(20))");
        saved.set(null);
        assertThrows(IllegalArgumentException.class, () -> transfer(OB, PG, "s", "narrow", List.of("wm", "id", "v"), List.of("id"), ds(PG), this::save));
        assertNull(saved.get()); assertEquals(0, count(PG, "narrow"));
    }

    @Test void smallAndUnsignedIntegerKeysKeepTheirExactDomainsAcrossProducts() throws Exception {
        sql(ob, "CREATE TABLE " + t(OB, "s") + "(wm TINYINT NOT NULL,a TINYINT UNSIGNED NOT NULL,b SMALLINT UNSIGNED NOT NULL,c MEDIUMINT NOT NULL,d INT UNSIGNED NOT NULL,v VARCHAR(20),PRIMARY KEY(a,b,c,d))");
        sql(pg, "CREATE TABLE " + t(PG, "t") + "(wm SMALLINT NOT NULL,a SMALLINT NOT NULL,b INTEGER NOT NULL,c INTEGER NOT NULL,d BIGINT NOT NULL,v VARCHAR(20),PRIMARY KEY(a,b,c,d))");
        sql(pg, "CREATE TABLE " + t(PG, "narrow") + "(wm SMALLINT NOT NULL,a SMALLINT NOT NULL,b INTEGER NOT NULL,c INTEGER NOT NULL,d INTEGER NOT NULL,v VARCHAR(20),PRIMARY KEY(a,b,c,d))");
        sql(ob, "INSERT INTO " + t(OB, "s") + " VALUES(-128,255,65535,-8388608,4294967295,'first'),(-128,255,65535,8388607,0,'second'),(127,0,0,0,2147483648,'third')");
        var names = List.of("wm", "a", "b", "c", "d", "v"); var key = List.of("a", "b", "c", "d");
        var reader = runtime.reader(ds(OB), OB); reader.setFetchSize(1); var seen = new ArrayList<String>();
        try (var cursor = reader.openCursor(new CursorReadRequest(schema, "s", names, "wm", key, null, null, null, limits, false, pin))) {
            assertEquals(List.of(java.sql.Types.TINYINT, java.sql.Types.TINYINT, java.sql.Types.INTEGER, java.sql.Types.INTEGER, java.sql.Types.BIGINT),
                    cursor.upperBound().values().stream().map(value -> value.jdbcType()).toList());
            assertEquals(List.of("127", "0", "0", "0", "2147483648"), cursor.upperBound().values().stream().map(value -> value.value()).toList());
            while (cursor.next()) seen.add((String) cursor.row()[5]);
        }
        assertEquals(List.of("first", "second", "third"), seen);
        transfer(OB, PG, names, key, this::save);
        assertEquals(texts(OB, "SELECT wm,a,b,c,d,v FROM " + t(OB, "s") + " ORDER BY wm,a,b,c,d"), texts(PG, "SELECT wm,a,b,c,d,v FROM " + t(PG, "t") + " ORDER BY wm,a,b,c,d"));
        // INT UNSIGNED does not fit a signed 32-bit PostgreSQL key: refused before the first write.
        saved.set(null);
        assertThrows(IllegalArgumentException.class, () -> transfer(OB, PG, "s", "narrow", names, key, ds(PG), this::save));
        assertNull(saved.get()); assertEquals(0, count(PG, "narrow"));
    }

    @Test void temporalKeysAndDateWatermarksPageExactlyWithinOceanbaseAndAreRefusedAcrossProducts() throws Exception {
        String shape = "(wm DATE NOT NULL,created DATETIME(6) NOT NULL,slot TIMESTAMP(3) NOT NULL,id INT UNSIGNED NOT NULL,v VARCHAR(20),PRIMARY KEY(created,slot,id))";
        sql(ob, "CREATE TABLE " + t(OB, "s") + shape); sql(ob, "CREATE TABLE " + t(OB, "t") + shape);
        sql(pg, "CREATE TABLE " + t(PG, "t") + "(wm DATE NOT NULL,created TIMESTAMP(6) NOT NULL,slot TIMESTAMPTZ(3) NOT NULL,id BIGINT NOT NULL,v VARCHAR(20),PRIMARY KEY(created,slot,id))");
        sql(ob, "INSERT INTO " + t(OB, "s") + " VALUES('2026-03-08','1988-04-17 02:30:00.123456','2026-03-08 02:30:00.001',4294967295,'a'),"
                + "('2026-03-08','1988-04-17 02:30:00.123456','2026-03-08 02:30:00.001',0,'b'),('2026-03-08','1988-04-17 02:30:00.123456','2026-03-08 02:30:00.002',1,'c'),"
                + "('2026-03-08','1988-04-17 02:30:00.123457','2026-03-08 02:30:00.001',1,'d'),('2026-03-09','0001-01-01 00:00:00.000000','1970-01-01 08:00:00.000',7,'e')");
        var names = List.of("wm", "created", "slot", "id", "v"); var key = List.of("created", "slot", "id");
        var reader = runtime.reader(ds(OB), OB); reader.setFetchSize(1); var seen = new ArrayList<String>();
        try (var cursor = reader.openCursor(new CursorReadRequest(schema, "s", names, "wm", key, null, null, null, limits, false, pin))) {
            assertEquals(List.of("2026-03-09", "0001-01-01T00:00", "1970-01-01T00:00Z", "7"), cursor.upperBound().values().stream().map(value -> value.value()).toList());
            while (cursor.next()) seen.add((String) cursor.row()[4]);
        }
        // Every page boundary compares a civil microsecond, an instant millisecond and an unsigned integer exactly.
        assertEquals(List.of("b", "a", "c", "d", "e"), seen);
        transfer(OB, OB, names, key, this::save);
        String read = "SELECT CAST(wm AS CHAR),CAST(created AS CHAR),CAST(slot AS CHAR),id,v FROM ";
        assertEquals(texts(OB, read + t(OB, "s") + " ORDER BY wm,created,slot,id"), texts(OB, read + t(OB, "t") + " ORDER BY wm,created,slot,id"));
        assertEquals(5, count(OB, "t")); assertTrue(saved.get().complete());
        // A late row of the already applied day is rewound; civil and instant keys have no verified cross-product equality.
        sql(ob, "INSERT INTO " + t(OB, "s") + " VALUES('2026-03-09','0001-01-01 00:00:00.000000','1970-01-01 08:00:00.000',6,'late same day')");
        transfer(OB, OB, names, key, this::save);
        assertEquals(6, count(OB, "t"));
        saved.set(null);
        assertThrows(IllegalArgumentException.class, () -> transfer(OB, PG, "s", "t", names, key, ds(PG), this::save));
        assertNull(saved.get()); assertEquals(0, count(PG, "t"));
    }

    @Test void textKeysPageInNativeCollationOrderAndReplayOnlyUnderTheIdenticalCollation() throws Exception {
        String shape = "(wm BIGINT NOT NULL,k VARCHAR(20) COLLATE %s NOT NULL,v VARCHAR(20),PRIMARY KEY(k))";
        sql(ob, "CREATE TABLE " + t(OB, "s") + shape.formatted("utf8mb4_general_ci"));
        sql(ob, "CREATE TABLE " + t(OB, "t") + shape.formatted("utf8mb4_general_ci"));
        sql(ob, "CREATE TABLE " + t(OB, "binary_keys") + shape.formatted("utf8mb4_bin"));
        sql(pg, "CREATE TABLE " + t(PG, "t") + "(wm BIGINT NOT NULL,k VARCHAR(20) PRIMARY KEY,v VARCHAR(20))");
        try (var insert = ob.prepareStatement("INSERT INTO " + t(OB, "s") + " VALUES(1,?,?)")) {
            for (String key : List.of("a", "B", "b c", "Z", "🙂", "汉字")) { insert.setString(1, key); insert.setString(2, key); insert.executeUpdate(); }
        }
        var names = List.of("wm", "k", "v"); var key = List.of("k");
        var reader = runtime.reader(ds(OB), OB); reader.setFetchSize(1); var seen = new ArrayList<String>();
        try (var cursor = reader.openCursor(new CursorReadRequest(schema, "s", names, "wm", key, null, null, null, limits, false, pin))) {
            while (cursor.next()) seen.add((String) cursor.row()[1]);
        }
        // The server's own collation decides both the order and the keyset comparison.
        assertEquals(texts(OB, "SELECT k FROM " + t(OB, "s") + " ORDER BY wm,k"), seen); assertEquals(6, seen.size());
        transfer(OB, OB, names, key, this::save);
        assertEquals(texts(OB, "SELECT k,v FROM " + t(OB, "s") + " ORDER BY HEX(k)"), texts(OB, "SELECT k,v FROM " + t(OB, "t") + " ORDER BY HEX(k)"));
        // 'a' and 'A' are distinct under utf8mb4_bin but one key under utf8mb4_general_ci: no cross-collation replay.
        saved.set(null);
        assertThrows(IllegalArgumentException.class, () -> transfer(OB, OB, "s", "binary_keys", names, key, ds(OB), this::save));
        assertThrows(IllegalArgumentException.class, () -> transfer(OB, PG, "s", "t", names, key, ds(PG), this::save));
        assertNull(saved.get()); assertEquals(0, count(OB, "binary_keys")); assertEquals(0, count(PG, "t"));
        // The binary collation orders by code point and keeps 'a' and 'A' apart; the same collation replays exactly.
        sql(ob, "CREATE TABLE " + t(OB, "binary_source") + shape.formatted("utf8mb4_bin"));
        try (var insert = ob.prepareStatement("INSERT INTO " + t(OB, "binary_source") + " VALUES(1,?,?)")) {
            for (String text : List.of("a", "A", "b", "B", "ä", "🙂", "😀")) { insert.setString(1, text); insert.setString(2, text); insert.executeUpdate(); }
        }
        seen.clear();
        try (var cursor = reader.openCursor(new CursorReadRequest(schema, "binary_source", names, "wm", key, null, null, null, limits, false, pin))) {
            while (cursor.next()) seen.add((String) cursor.row()[1]);
        }
        assertEquals(List.of("A", "B", "a", "b", "ä", "😀", "🙂"), seen);
        transfer(OB, OB, "binary_source", "binary_keys", names, key, ds(OB), this::save);
        assertEquals(texts(OB, "SELECT k,v FROM " + t(OB, "binary_source") + " ORDER BY k"), texts(OB, "SELECT k,v FROM " + t(OB, "binary_keys") + " ORDER BY k"));
        assertEquals(7, count(OB, "binary_keys"));
    }

    @ParameterizedTest @ValueSource(strings = {"DATETIME", "TIME", "DECIMAL"})
    void lowerPrecisionTargetRejectsTheWholeBatchBeforeChangingRows(String kind) throws Exception {
        String sourceType = switch (kind) { case "DATETIME" -> "DATETIME(6)"; case "TIME" -> "TIME(6)"; default -> "DECIMAL(38,18)"; };
        String targetType = switch (kind) { case "DATETIME" -> "DATETIME(3)"; case "TIME" -> "TIME(3)"; default -> "DECIMAL(38,8)"; };
        String exact = switch (kind) { case "DATETIME" -> "'2026-10-03 12:34:56.123000'"; case "TIME" -> "'-123:45:56.123000'"; default -> "1.123000000000000000"; };
        String lossy = switch (kind) { case "DATETIME" -> "'2026-10-03 12:34:56.123456'"; case "TIME" -> "'-123:45:56.123456'"; default -> "1.123000000000000001"; };
        sql(ob, "CREATE TABLE " + t(OB, "s") + "(wm BIGINT NOT NULL,id BIGINT PRIMARY KEY,val " + sourceType + ")");
        sql(ob, "CREATE TABLE " + t(OB, "t") + "(wm BIGINT NOT NULL,id BIGINT PRIMARY KEY,val " + targetType + ")");
        sql(ob, "INSERT INTO " + t(OB, "s") + " VALUES(10,1," + exact + "),(10,2," + exact + "),(10,3," + exact + "),(10,4," + lossy + ")");
        sql(ob, "INSERT INTO " + t(OB, "t") + " VALUES(1,3,NULL),(1,4,NULL)");
        var names = List.of("wm", "id", "val");
        assertThrows(BatchRejectedException.class, () -> transfer(OB, OB, names, List.of("id"), this::save));
        // The second batch contains one unrepresentable value: neither of its rows was changed or rounded.
        assertEquals(List.of("1|10", "2|10", "3|1", "4|1"), texts(OB, "SELECT id,wm FROM " + t(OB, "t") + " ORDER BY id"));
        assertEquals(1, saved.get().sequence()); assertFalse(saved.get().complete());
        sql(ob, "UPDATE " + t(OB, "s") + " SET val=" + exact + " WHERE id=4");
        transfer(OB, OB, names, List.of("id"), this::save);
        assertTrue(saved.get().complete());
        assertEquals(texts(OB, "SELECT id,wm,CAST(val AS CHAR) FROM " + t(OB, "s") + " ORDER BY id").stream().map(text -> text.replaceAll("0{3}$|0{10}$", "")).toList(),
                texts(OB, "SELECT id,wm,CAST(val AS CHAR) FROM " + t(OB, "t") + " ORDER BY id"));
    }

    @ParameterizedTest @EnumSource(value = ProductTypeEnum.class, names = {"OCEANBASE", "POSTGRESQL"})
    void publicCursorPreservesExactPayloadDomains(ProductTypeEnum target) throws Exception {
        sql(ob, "CREATE TABLE " + t(OB, "s") + "(wm BIGINT NOT NULL,id BIGINT PRIMARY KEY,flag TINYINT(1),small TINYINT UNSIGNED,ub BIGINT UNSIGNED,amount DECIMAL(38,18),"
                + "note VARCHAR(80),fixed CHAR(4),payload VARBINARY(16),day DATE,happened DATETIME(6),instant TIMESTAMP(6) NULL" + (target == OB ? ",duration TIME(6),body TEXT,image BLOB" : "") + ")");
        sql(control(target), target == OB ? "CREATE TABLE " + t(OB, "t") + " LIKE " + t(OB, "s")
                : "CREATE TABLE " + t(PG, "t") + "(wm BIGINT NOT NULL,id BIGINT PRIMARY KEY,flag SMALLINT,small SMALLINT,ub NUMERIC(20,0),amount NUMERIC(38,18),"
                + "note VARCHAR(80),fixed CHAR(4),payload BYTEA,day DATE,happened TIMESTAMP(6),instant TIMESTAMPTZ(6))");
        String tail = target == OB ? ",?,?,?" : "";
        try (var insert = ob.prepareStatement("INSERT INTO " + t(OB, "s") + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?" + tail + ")")) {
            insert.setLong(1, 10); insert.setLong(2, 1); insert.setLong(3, 5); insert.setLong(4, 255);
            insert.setBigDecimal(5, new BigDecimal("18446744073709551615")); insert.setBigDecimal(6, new BigDecimal("-0.000000000000000001"));
            insert.setString(7, "汉字'\\\"; 🙂  "); insert.setString(8, "ab"); insert.setBytes(9, new byte[]{0, 39, 92, (byte) 255});
            insert.setString(10, "0001-01-01"); insert.setString(11, "1988-04-17 02:30:00.123456"); insert.setString(12, "2026-03-08 02:30:00.000001");
            if (target == OB) { insert.setString(13, "-838:59:59.000000"); insert.setString(14, "正文\n" + "x".repeat(4000)); insert.setBytes(15, new byte[]{0, 1, 2, (byte) 254}); }
            insert.executeUpdate();
            insert.setLong(2, 2); insert.setLong(3, -128); insert.setLong(4, 0);
            insert.setBigDecimal(5, BigDecimal.ZERO); insert.setBigDecimal(6, new BigDecimal("12345678901234567890.123456789012345678"));
            insert.setString(7, ""); insert.setString(8, ""); insert.setBytes(9, new byte[0]);
            insert.setString(10, "9999-12-31"); insert.setString(11, "9999-12-31 23:59:59.999999"); insert.setString(12, "1970-01-01 08:00:00.000000");
            if (target == OB) { insert.setString(13, "123:45:56.654321"); insert.setString(14, ""); insert.setBytes(15, new byte[0]); }
            insert.executeUpdate();
            insert.setLong(2, 3);
            for (int i = 3; i <= (target == OB ? 15 : 12); i++) insert.setNull(i, java.sql.Types.NULL);
            insert.executeUpdate();
        }
        var request = request(OB, target);
        var result = new MigrationService(request, ignored -> { }, null, runtime).run();
        assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString());
        sql(ob, "SET SESSION time_zone='+00:00'");
        String obColumns = "id,CAST(flag AS CHAR),CAST(small AS CHAR),CAST(ub AS CHAR),CAST(amount AS CHAR),note,CONCAT('[',fixed,']'),HEX(payload),CAST(day AS CHAR),CAST(happened AS CHAR),CAST(instant AS CHAR)";
        var expected = texts(OB, "SELECT " + obColumns + (target == OB ? ",CAST(duration AS CHAR),body,HEX(image)" : "") + " FROM " + t(OB, "s") + " ORDER BY id");
        var actual = target == OB ? texts(OB, "SELECT " + obColumns + ",CAST(duration AS CHAR),body,HEX(image) FROM " + t(OB, "t") + " ORDER BY id")
                : texts(PG, "SELECT id,flag::text,small::text,ub::text,amount::text,note,'['||rtrim(fixed)||']',upper(encode(payload,'hex')),to_char(day,'YYYY-MM-DD'),"
                + "to_char(happened,'YYYY-MM-DD HH24:MI:SS.US'),to_char(instant AT TIME ZONE 'UTC','YYYY-MM-DD HH24:MI:SS.US') FROM " + t(PG, "t") + " ORDER BY id");
        assertEquals(3, expected.size()); assertEquals(expected, actual);
        // TINYINT(1) is a number, not the boolean this driver reports; the civil time is not shifted by the JVM zone.
        assertTrue(expected.get(0).startsWith("1|5|255|18446744073709551615|-0.000000000000000001|"), expected.get(0));
        assertTrue(expected.get(0).contains("|0001-01-01|1988-04-17 02:30:00.123456|2026-03-07 18:30:00.000001"), expected.get(0));
    }

    // ---- native UPSERT ----------------------------------------------------------------------------------

    @Test void upsertAcknowledgesInputRowsWhateverAffectedCountOceanbaseReports() throws Exception {
        sql(ob, "CREATE TABLE " + t(OB, "t") + "(k BIGINT PRIMARY KEY,v VARCHAR(20))");
        sql(ob, "CREATE TABLE " + t(OB, "only_keys") + "(a BIGINT NOT NULL,b DECIMAL(38,0) NOT NULL,PRIMARY KEY(a,b))");
        var writer = runtime.upsertWriter(ds(OB), OB); var names = List.of("k", "v");
        writer.prepareUpsert(schema, "t", names, List.of("k"));
        // insert (1), change (2), unchanged (1 with this driver), and one key twice in the same batch: always the input count.
        for (var batch : List.of(List.<Object[]>of(new Object[]{1L, "new"}, new Object[]{2L, "new"}), List.<Object[]>of(new Object[]{1L, "changed"}, new Object[]{2L, "new"}),
                List.<Object[]>of(new Object[]{1L, "changed"}, new Object[]{2L, "new"}), List.<Object[]>of(new Object[]{3L, "first"}, new Object[]{3L, "last"}))) {
            var receipt = writer.writeWithReceipt(names, batch);
            assertEquals(2, receipt.acknowledgedRows()); assertEquals(CommitAcknowledgement.JDBC_COMMIT_RETURNED, receipt.commitAcknowledgement()); assertNull(receipt.receipt());
        }
        assertEquals(List.of("1|changed", "2|new", "3|last"), texts(OB, "SELECT k,v FROM " + t(OB, "t") + " ORDER BY k"));
        var keysOnly = runtime.upsertWriter(ds(OB), OB); var pair = List.of("a", "b");
        keysOnly.prepareUpsert(schema, "only_keys", pair, List.of("b", "a"));
        for (int i = 0; i < 2; i++) assertEquals(1, keysOnly.writeWithReceipt(pair, Collections.singletonList(new Object[]{Long.MIN_VALUE, new BigDecimal("-" + MAX38)})).acknowledgedRows());
        assertEquals(List.of("-9223372036854775808|-" + MAX38), texts(OB, "SELECT a,CAST(b AS CHAR) FROM " + t(OB, "only_keys")));
    }

    @Test void exactValuesSurviveInsertUpdateAndIdenticalReplay() throws Exception {
        sql(ob, "CREATE TABLE " + t(OB, "t") + "(k DECIMAL(38,0) PRIMARY KEY,flag TINYINT(1),ub BIGINT UNSIGNED,amount DECIMAL(38,18),note VARCHAR(8),"
                + "payload BINARY(3),day DATE,happened DATETIME(6),instant TIMESTAMP(6) NULL,duration TIME(6))");
        // One reused physical session proves that the UTC write zone and autocommit are restored after every batch.
        Connection shared = open(OB); String zone = query(shared, "SELECT @@session.time_zone").getFirst();
        DataSource reusing = new AbstractDataSource() {
            @Override public Connection getConnection() {
                return (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("close")) return null;
                    try { return method.invoke(shared, arguments); } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
            @Override public Connection getConnection(String user, String password) { return getConnection(); }
        };
        var writer = runtime.upsertWriter(reusing, OB);
        var names = List.of("k", "flag", "ub", "amount", "note", "payload", "day", "happened", "instant", "duration");
        writer.prepareUpsert(schema, "t", names, List.of("k"));
        BigDecimal key = new BigDecimal(MAX38);
        Object[] first = {key, 5, new java.math.BigInteger("18446744073709551615"), new BigDecimal("-1E-18"), "汉字🙂'\\", new byte[]{0, 39, (byte) 255},
                LocalDate.parse("0001-01-01"), LocalDateTime.parse("1988-04-17T02:30:00.123456"), OffsetDateTime.parse("2026-03-08T02:30:00.000001+08:00"), "-838:59:59.000000"};
        Object[] second = {key, (short) -128, 0L, new BigDecimal("12345678901234567890.123456789012345678"), "", new byte[]{1, 2, 3},
                java.sql.Date.valueOf("9999-12-31"), LocalDateTime.parse("9999-12-31T23:59:59.999999"), OffsetDateTime.parse("1970-01-01T00:00:00Z"), java.time.LocalTime.parse("23:59:59.999999")};
        String read = "SELECT CAST(k AS CHAR),CAST(flag AS CHAR),CAST(ub AS CHAR),CAST(amount AS CHAR),note,HEX(payload),CAST(day AS CHAR),CAST(happened AS CHAR),CAST(instant AS CHAR),CAST(duration AS CHAR) FROM " + t(OB, "t");
        sql(ob, "SET SESSION time_zone='+00:00'");
        for (int i = 0; i < 2; i++) assertEquals(1, writer.writeWithReceipt(names, Collections.singletonList(first)).acknowledgedRows());
        assertEquals(List.of(MAX38 + "|5|18446744073709551615|-0.000000000000000001|汉字🙂'\\|0027FF|0001-01-01|1988-04-17 02:30:00.123456|2026-03-07 18:30:00.000001|-838:59:59.000000"), texts(OB, read));
        for (int i = 0; i < 2; i++) assertEquals(1, writer.writeWithReceipt(names, Collections.singletonList(second)).acknowledgedRows());
        assertEquals(List.of(MAX38 + "|-128|0|12345678901234567890.123456789012345678||010203|9999-12-31|9999-12-31 23:59:59.999999|1970-01-01 00:00:00.000000|23:59:59.999999"), texts(OB, read));
        try (shared) { assertNotEquals("+00:00", zone); assertEquals(List.of(zone + "|1"), query(shared, "SELECT @@session.time_zone,@@autocommit")); }
    }

    @ParameterizedTest @ValueSource(strings = {"DECIMAL_SCALE", "DECIMAL_RANGE", "INT_FRACTION", "INT_RANGE", "TINYINT_RANGE", "UNSIGNED_NEGATIVE", "BOOLEAN_NUMBER", "DOUBLE_NUMBER",
            "VARCHAR_OVERLONG", "CHAR_TRAILING_SPACE", "BINARY_SHORT", "VARBINARY_OVERLONG", "DATETIME_NANOS", "DATETIME_INSTANT", "TIMESTAMP_CIVIL", "DATE_WITH_TIME",
            "TIME_PRECISION", "TIME_RANGE", "TIME_MILLISECOND_CARRIER", "NOT_NULL", "YEAR_ZERO"})
    void exactValuePreflightRejectsTheWholeBatchBeforeAnyWrite(String kind) throws Exception {
        sql(ob, "CREATE TABLE " + t(OB, "t") + "(k BIGINT PRIMARY KEY,d DECIMAL(10,2),i INT,ti TINYINT(1),ub BIGINT UNSIGNED,v VARCHAR(3),c CHAR(3),b BINARY(3),vb VARBINARY(4),"
                + "dt DATETIME(3),ts TIMESTAMP(6) NULL,d0 DATE,tm TIME(3),nn INT NOT NULL)");
        var names = List.of("k", "d", "i", "ti", "ub", "v", "c", "b", "vb", "dt", "ts", "d0", "tm", "nn");
        var writer = runtime.upsertWriter(ds(OB), OB); writer.prepareUpsert(schema, "t", names, List.of("k"));
        Object[] good = {1L, new BigDecimal("1.23"), 1, 1, 1L, "abc", "ab", new byte[]{1, 2, 3}, new byte[]{1}, LocalDateTime.parse("2026-01-02T03:04:05.123"),
                OffsetDateTime.parse("2026-01-02T03:04:05.123456Z"), LocalDate.parse("2026-01-02"), "01:02:03.123", 1};
        Object[] bad = good.clone(); bad[0] = 2L;
        switch (kind) {
            case "DECIMAL_SCALE" -> bad[1] = new BigDecimal("1.235");
            case "DECIMAL_RANGE" -> bad[1] = new BigDecimal("123456789.10");
            case "INT_FRACTION" -> bad[2] = new BigDecimal("1.5");
            case "INT_RANGE" -> bad[2] = 2147483648L;
            case "TINYINT_RANGE" -> bad[3] = 128;
            case "UNSIGNED_NEGATIVE" -> bad[4] = -1L;
            case "BOOLEAN_NUMBER" -> bad[3] = Boolean.TRUE;
            case "DOUBLE_NUMBER" -> bad[1] = 1.5d;
            case "VARCHAR_OVERLONG" -> bad[5] = "a🙂汉b";
            case "CHAR_TRAILING_SPACE" -> bad[6] = "ab ";
            case "BINARY_SHORT" -> bad[7] = new byte[]{1};
            case "VARBINARY_OVERLONG" -> bad[8] = new byte[]{1, 2, 3, 4, 5};
            case "DATETIME_NANOS" -> bad[9] = LocalDateTime.parse("2026-01-02T03:04:05.1234");
            case "DATETIME_INSTANT" -> bad[9] = OffsetDateTime.parse("2026-01-02T03:04:05.123Z");
            case "TIMESTAMP_CIVIL" -> bad[10] = LocalDateTime.parse("2026-01-02T03:04:05.123456");
            case "DATE_WITH_TIME" -> bad[11] = LocalDateTime.parse("2026-01-02T03:04:05");
            case "TIME_PRECISION" -> bad[12] = "01:02:03.1234";
            case "TIME_RANGE" -> bad[12] = "839:00:00.000";
            case "TIME_MILLISECOND_CARRIER" -> bad[12] = java.sql.Time.valueOf("01:02:03");
            case "NOT_NULL" -> bad[13] = null;
            case "YEAR_ZERO" -> bad[11] = LocalDate.of(0, 1, 1);
            default -> throw new AssertionError(kind);
        }
        assertThrows(BatchRejectedException.class, () -> writer.writeWithReceipt(names, List.of(good, bad)));
        assertEquals(0, count(OB, "t"));
        assertEquals(1, writer.writeWithReceipt(names, Collections.singletonList(good)).acknowledgedRows());
        assertEquals(1, count(OB, "t"));
    }

    @ParameterizedTest @ValueSource(strings = {"SECOND_UNIQUE", "NO_UNIQUE", "NULLABLE_UNIQUE", "KEY_SUBSET", "PREFIX_UNIQUE", "GENERATED_FIELD", "FLOAT_FIELD", "DOUBLE_FIELD",
            "JSON_FIELD", "BIT_FIELD", "YEAR_FIELD", "ENUM_FIELD", "LATIN1_TEXT", "COLUMN_STORE", "VIEW", "MISSING_FIELD"})
    void upsertRefusesAmbiguousConflictTargetsAndUnverifiedColumnsBeforeWriting(String kind) throws Exception {
        String definition = switch (kind) {
            case "SECOND_UNIQUE" -> "(k BIGINT PRIMARY KEY,v INT NOT NULL,UNIQUE KEY other(v))";
            case "NO_UNIQUE" -> "(k BIGINT NOT NULL,v INT)";
            case "NULLABLE_UNIQUE" -> "(k BIGINT NULL,v INT,UNIQUE KEY nullable_key(k))";
            case "KEY_SUBSET" -> "(k BIGINT NOT NULL,v INT NOT NULL,PRIMARY KEY(k,v))";
            case "PREFIX_UNIQUE" -> "(k VARCHAR(20) NOT NULL,v INT,UNIQUE KEY prefix_key(k(4)))";
            case "GENERATED_FIELD" -> "(k BIGINT PRIMARY KEY,v BIGINT GENERATED ALWAYS AS (k+1) VIRTUAL)";
            case "FLOAT_FIELD" -> "(k BIGINT PRIMARY KEY,v FLOAT)";
            case "DOUBLE_FIELD" -> "(k BIGINT PRIMARY KEY,v DOUBLE)";
            case "JSON_FIELD" -> "(k BIGINT PRIMARY KEY,v JSON)";
            case "BIT_FIELD" -> "(k BIGINT PRIMARY KEY,v BIT(8))";
            case "YEAR_FIELD" -> "(k BIGINT PRIMARY KEY,v YEAR)";
            case "ENUM_FIELD" -> "(k BIGINT PRIMARY KEY,v ENUM('a','b'))";
            case "LATIN1_TEXT" -> "(k BIGINT PRIMARY KEY,v VARCHAR(20) CHARACTER SET latin1)";
            case "COLUMN_STORE" -> "(k BIGINT PRIMARY KEY,v INT) WITH COLUMN GROUP(each column)";
            default -> "(k BIGINT PRIMARY KEY,v INT)";
        };
        sql(ob, "CREATE TABLE " + t(OB, "base") + definition);
        if (kind.equals("VIEW")) sql(ob, "CREATE VIEW " + t(OB, "viewed") + " AS SELECT k,v FROM " + t(OB, "base"));
        String table = kind.equals("VIEW") ? "viewed" : "base";
        var names = kind.equals("MISSING_FIELD") ? List.of("k", "absent") : List.of("k", "v");
        var writer = runtime.upsertWriter(ds(OB), OB);
        assertThrows(IllegalArgumentException.class, () -> writer.prepareUpsert(schema, table, names, List.of("k")));
        assertThrows(RuntimeException.class, () -> writer.write(names, Collections.singletonList(new Object[]{1L, 1})));
        assertEquals(0, count(OB, "base"));
    }

    @Test void triggerSideEffectsRefuseCursorReplayBeforeTheFirstTargetWrite() throws Exception {
        create(OB, "s"); create(OB, "t"); rows(OB, 1);
        sql(ob, "CREATE TABLE " + t(OB, "audit") + "(k1 BIGINT)");
        sql(ob, "USE " + q(schema));
        sql(ob, "CREATE TRIGGER side_effect AFTER INSERT ON t FOR EACH ROW INSERT INTO audit VALUES(NEW.k1)");
        assertThrows(IllegalArgumentException.class, () -> transfer(OB, OB, ds(OB), this::save));
        assertNull(saved.get()); assertEquals(0, count(OB, "t")); assertEquals(0, count(OB, "audit"));
    }

    @Test void serverConstraintFailureRollsBackTheWholeBatchAndIsNeverAcknowledged() throws Exception {
        sql(ob, "CREATE TABLE " + t(OB, "t") + "(k BIGINT PRIMARY KEY,v INT,CONSTRAINT positive_value CHECK (v > 0))");
        var writer = runtime.upsertWriter(ds(OB), OB); var names = List.of("k", "v");
        writer.prepareUpsert(schema, "t", names, List.of("k"));
        assertThrows(UnconfirmedWriteException.class, () -> writer.writeWithReceipt(names, List.of(new Object[]{1L, 1}, new Object[]{2L, -1})));
        reopen();
        assertEquals(0, count(OB, "t"));
    }

    @ParameterizedTest @ValueSource(strings = {"BEFORE_TRANSACTION", "BETWEEN_PRECHECK_AND_DML"})
    void targetTruncateIsDetectedBeforeTheBatchCanCommit(String moment) throws Exception {
        sql(ob, "CREATE TABLE " + t(OB, "t") + "(k BIGINT PRIMARY KEY,v VARCHAR(20))");
        sql(ob, "INSERT INTO " + t(OB, "t") + " VALUES(1,'existing')");
        var names = List.of("k", "v"); var truncated = new AtomicInteger();
        // A plain catalog read does not lock an OceanBase table: the fixture truncates right before the DML is prepared.
        DataSource racing = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection actual = open(OB);
                return (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    try {
                        if (moment.equals("BETWEEN_PRECHECK_AND_DML") && method.getName().equals("prepareStatement") && String.valueOf(arguments[0]).startsWith("INSERT INTO")
                                && truncated.incrementAndGet() == 1) sql(ob, "TRUNCATE TABLE " + t(OB, "t"));
                        return method.invoke(actual, arguments);
                    } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
            @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        };
        var writer = runtime.upsertWriter(racing, OB); writer.prepareUpsert(schema, "t", names, List.of("k"));
        if (moment.equals("BEFORE_TRANSACTION")) {
            sql(ob, "TRUNCATE TABLE " + t(OB, "t"));
            // Refused by the pre-DML observation: no data statement was sent.
            assertThrows(IllegalStateException.class, () -> writer.writeWithReceipt(names, Collections.singletonList(new Object[]{2L, "new"})));
        } else {
            assertThrows(UnconfirmedWriteException.class, () -> writer.writeWithReceipt(names, Collections.singletonList(new Object[]{2L, "new"})));
            assertEquals(1, truncated.get());
        }
        reopen();
        assertEquals(0, count(OB, "t"));
    }

    // ---- refusals before reading ---------------------------------------------------------------------

    @ParameterizedTest @ValueSource(strings = {"NO_PIN", "KEY_ORDER", "KEY_SUBSET", "NULLABLE_WATERMARK", "TEXT_WATERMARK", "TIME_KEY", "DOUBLE_PAYLOAD", "FLOAT_PAYLOAD", "JSON_PAYLOAD",
            "BIT_PAYLOAD", "COLUMN_STORE", "VIEW", "NO_PRIMARY_KEY", "TABLE_CASE", "MISSING_FIELD"})
    void unverifiedSourceShapesAreRefusedBeforeReadingOrWriting(String kind) throws Exception {
        String definition = switch (kind) {
            case "NULLABLE_WATERMARK" -> "(wm BIGINT NULL,k1 BIGINT NOT NULL,k2 BIGINT NOT NULL,v VARCHAR(20),PRIMARY KEY(k2,k1))";
            case "TEXT_WATERMARK" -> "(wm VARCHAR(20) NOT NULL,k1 BIGINT NOT NULL,k2 BIGINT NOT NULL,v VARCHAR(20),PRIMARY KEY(k2,k1))";
            case "TIME_KEY" -> "(wm BIGINT NOT NULL,k1 TIME(6) NOT NULL,k2 BIGINT NOT NULL,v VARCHAR(20),PRIMARY KEY(k2,k1))";
            case "DOUBLE_PAYLOAD" -> "(wm BIGINT NOT NULL,k1 BIGINT NOT NULL,k2 BIGINT NOT NULL,v DOUBLE,PRIMARY KEY(k2,k1))";
            case "FLOAT_PAYLOAD" -> "(wm BIGINT NOT NULL,k1 BIGINT NOT NULL,k2 BIGINT NOT NULL,v FLOAT,PRIMARY KEY(k2,k1))";
            case "JSON_PAYLOAD" -> "(wm BIGINT NOT NULL,k1 BIGINT NOT NULL,k2 BIGINT NOT NULL,v JSON,PRIMARY KEY(k2,k1))";
            case "BIT_PAYLOAD" -> "(wm BIGINT NOT NULL,k1 BIGINT NOT NULL,k2 BIGINT NOT NULL,v BIT(8),PRIMARY KEY(k2,k1))";
            case "COLUMN_STORE" -> "(wm BIGINT NOT NULL,k1 BIGINT NOT NULL,k2 BIGINT NOT NULL,v VARCHAR(20),PRIMARY KEY(k2,k1)) WITH COLUMN GROUP(each column)";
            case "NO_PRIMARY_KEY" -> "(wm BIGINT NOT NULL,k1 BIGINT NOT NULL,k2 BIGINT NOT NULL,v VARCHAR(20),UNIQUE KEY not_primary(k2,k1))";
            default -> "(wm BIGINT NOT NULL,k1 BIGINT NOT NULL,k2 BIGINT NOT NULL,v VARCHAR(20),PRIMARY KEY(k2,k1))";
        };
        sql(ob, "CREATE TABLE " + t(OB, "base") + definition);
        sql(ob, "INSERT INTO " + t(OB, "base") + "(wm,k1,k2) VALUES(" + (kind.equals("TEXT_WATERMARK") ? "'1'" : "1") + "," + (kind.equals("TIME_KEY") ? "'00:00:01'" : "1") + ",1)");
        if (kind.equals("VIEW")) sql(ob, "CREATE VIEW " + t(OB, "viewed") + " AS SELECT * FROM " + t(OB, "base"));
        String table = kind.equals("VIEW") ? "viewed" : kind.equals("TABLE_CASE") ? "BASE" : "base";
        var names = kind.equals("MISSING_FIELD") ? List.of("wm", "k1", "k2", "absent") : fields;
        var primary = kind.equals("KEY_ORDER") ? List.of("k1", "k2") : kind.equals("KEY_SUBSET") ? List.of("k2") : keys;
        var reader = runtime.reader(ds(OB), OB);
        assertThrows(IllegalArgumentException.class, () -> reader.openCursor(new CursorReadRequest(schema, table, names, "wm", primary, null, null, null, limits, false,
                kind.equals("NO_PIN") ? null : pin)));
        // The same connection profile reads the control shape, so the refusal is caused by the shape alone.
        if (!List.of("NO_PIN", "KEY_ORDER", "KEY_SUBSET", "TABLE_CASE", "MISSING_FIELD", "VIEW").contains(kind)) return;
        try (var cursor = reader.openCursor(new CursorReadRequest(schema, "base", fields, "wm", keys, null, null, null, limits, false, pin))) {
            assertTrue(cursor.next()); assertFalse(cursor.next());
        }
    }

    @Test void specialPhysicalNamesAreQuotedAndBoundExactly() throws Exception {
        String source = "s`; rows", target = "t`; rows", watermark = "w`;", key = "k'\"", value = "v ;";
        for (String table : List.of(source, target))
            sql(ob, "CREATE TABLE " + t(OB, table) + "(" + q(watermark) + " DECIMAL(38,18) NOT NULL," + q(key) + " BIGINT PRIMARY KEY," + q(value) + " VARCHAR(20))");
        try (var insert = ob.prepareStatement("INSERT INTO " + t(OB, source) + " VALUES(?,?,?)")) {
            insert.setBigDecimal(1, new BigDecimal("-1E-18")); insert.setLong(2, Long.MIN_VALUE); insert.setString(3, "quote'"); insert.executeUpdate();
        }
        var names = List.of(watermark, key, value); var result = new DbSwitchTableResult();
        CursorTransfer.execute(runtime, ds(OB), ds(OB), OB, OB, schema, source, schema, target, names, names, List.of(key), List.of(key), watermark, limits, 1, binding(this::save), result, () -> { });
        assertEquals(1, result.getSyncCount().get());
        assertEquals(List.of("-0.000000000000000001|-9223372036854775808|quote'"), texts(OB, "SELECT CAST(" + q(watermark) + " AS CHAR)," + q(key) + "," + q(value) + " FROM " + t(OB, target)));
    }

    @Test void timeValuesStayMicrosecondExactAcrossProductsOrAreRefusedBeforeWriting() throws Exception {
        sql(ob, "CREATE TABLE " + t(OB, "s") + "(wm BIGINT NOT NULL,id BIGINT PRIMARY KEY,duration TIME(6))");
        sql(pg, "CREATE TABLE " + t(PG, "t") + "(wm BIGINT NOT NULL,id BIGINT PRIMARY KEY,duration TIME(6))");
        sql(ob, "INSERT INTO " + t(OB, "s") + " VALUES(1,1,'00:00:00.000001'),(1,2,'12:34:56.123456'),(1,3,'23:59:59.999999')");
        var names = List.of("wm", "id", "duration"); var exact = List.of("1|00:00:00.000001", "2|12:34:56.123456", "3|23:59:59.999999");
        transfer(OB, PG, names, List.of("id"), this::save);
        // A clock time inside one day is carried as LocalTime: no millisecond java.sql.Time re-parse on the target driver.
        assertEquals(exact, texts(PG, "SELECT id,duration::text FROM " + t(PG, "t") + " ORDER BY id")); assertTrue(saved.get().complete());
        // Durations outside one day have no PostgreSQL time value: refused instead of being wrapped or truncated.
        sql(ob, "INSERT INTO " + t(OB, "s") + " VALUES(2,4,'123:45:56.000001'),(2,5,'-00:00:00.000001')");
        assertThrows(RuntimeException.class, () -> transfer(OB, PG, names, List.of("id"), this::save));
        assertEquals(exact, texts(PG, "SELECT id,duration::text FROM " + t(PG, "t") + " ORDER BY id")); assertFalse(saved.get().complete());
        // PostgreSQL's exact TIME text/LocalTime carrier preserves all six digits in the reverse direction too.
        sql(pg, "CREATE TABLE " + t(PG, "s") + "(wm BIGINT NOT NULL,id BIGINT PRIMARY KEY,duration TIME(6))");
        sql(ob, "CREATE TABLE " + t(OB, "t") + "(wm BIGINT NOT NULL,id BIGINT PRIMARY KEY,duration TIME(6))");
        sql(pg, "INSERT INTO " + t(PG, "s") + " VALUES(1,1,'00:00:00.000001'),(1,2,'12:34:56.123456'),(1,3,'23:59:59.999999'),(1,4,NULL)");
        saved.set(null);
        assertEquals(4, transfer(PG, OB, names, List.of("id"), this::save).getSyncCount().get());
        var withNull = new ArrayList<>(exact); withNull.add("4|null");
        assertEquals(withNull, texts(OB, "SELECT id,CAST(duration AS CHAR) FROM " + t(OB, "t") + " ORDER BY id"));
        assertEquals(texts(PG, "SELECT id,duration::text FROM " + t(PG, "s") + " ORDER BY id"),
                texts(OB, "SELECT id,CAST(duration AS CHAR) FROM " + t(OB, "t") + " ORDER BY id"));
        assertTrue(saved.get().complete());
    }

    @Test void postgresTime24BoundaryIsRejectedBeforeAnyOceanbaseWrite() throws Exception {
        sql(pg, "CREATE TABLE " + t(PG, "s") + "(wm BIGINT NOT NULL,id BIGINT PRIMARY KEY,duration TIME(6))");
        sql(ob, "CREATE TABLE " + t(OB, "t") + "(wm BIGINT NOT NULL,id BIGINT PRIMARY KEY,duration TIME(6))");
        sql(pg, "INSERT INTO " + t(PG, "s") + " VALUES(1,1,'24:00:00')");
        var rejected = assertThrows(SQLException.class, () -> transfer(PG, OB, List.of("wm", "id", "duration"), List.of("id"), this::save));
        assertTrue(rejected.getMessage().contains("24:00:00"));
        assertEquals(0, count(OB, "t")); assertNull(saved.get().applied());
    }

    @Test void keysetAndUpsertStatementsAreTheVerifiedDialectText() throws Exception {
        sql(ob, "CREATE TABLE " + t(OB, "s") + "(wm DATETIME(6) NOT NULL,id BIGINT PRIMARY KEY,v VARCHAR(20))");
        sql(ob, "CREATE TABLE " + t(OB, "t") + " LIKE " + t(OB, "s"));
        sql(ob, "INSERT INTO " + t(OB, "s") + " VALUES('2026-03-08 02:30:00.000001',1,'a'),('2026-03-08 02:30:00.000002',2,'b'),('2026-03-08 02:30:00.000002',3,'c')");
        var statements = new LinkedHashSet<String>();
        DataSource recording = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection actual = open(OB);
                return (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    try {
                        if (method.getName().equals("prepareStatement") && String.valueOf(arguments[0]).contains(q(schema)))
                            statements.add(String.valueOf(arguments[0]).replace(q(schema), "`db`"));
                        return method.invoke(actual, arguments);
                    } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
            @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        };
        var names = List.of("wm", "id", "v");
        for (int run = 0; run < 2; run++)
            CursorTransfer.execute(runtime, recording, recording, OB, OB, schema, "s", schema, "t", names, names, List.of("id"), List.of("id"), "wm", limits, 2,
                    binding(this::save), new DbSwitchTableResult(), () -> { });
        // Temporal values are projected and bound as server text; keys use native row comparison; data values are never inlined.
        String page = "SELECT CAST(`wm` AS CHAR),`id`,`v` FROM `db`.`s` WHERE (`wm`,`id`)<=(CAST(? AS DATETIME(6)),?)", order = " ORDER BY `wm` ASC,`id` ASC LIMIT 2";
        assertEquals(List.of(page + order,
                "INSERT INTO `db`.`t` (`wm`,`id`,`v`) VALUES (?,?,?) ON DUPLICATE KEY UPDATE `wm`=VALUES(`wm`),`v`=VALUES(`v`)",
                page + " AND (`wm`,`id`)>(CAST(? AS DATETIME(6)),?)" + order,
                "SELECT 1 FROM `db`.`s` WHERE `wm`>=CAST(? AS DATETIME(6)) LIMIT 1",
                page + " AND `wm`>=CAST(? AS DATETIME(6))" + order,
                page + " AND `wm`>=CAST(? AS DATETIME(6)) AND (`wm`,`id`)>(CAST(? AS DATETIME(6)),?)" + order), List.copyOf(statements));
        assertEquals(3, count(OB, "t")); assertTrue(saved.get().complete());
    }

    @Test void administratorHelpersOnlyReadTheTenantAndNeverWriteAMarker() throws Exception {
        String uuid = query(ob, "SELECT VALUE FROM oceanbase.DBA_OB_SYS_VARIABLES WHERE NAME='server_uuid'").getFirst();
        assertEquals(uuid, pin.instanceId());
        var again = OceanbaseSourceGeneration.initialize(ob, schema, 15);
        assertEquals(uuid, again.instanceId()); assertNotEquals(pin.generation(), again.generation());
        String next = UUID.randomUUID().toString();
        var rotated = OceanbaseSourceGeneration.rotate(ob, pin, next, 15);
        assertEquals(new SourceGenerationPin(schema, uuid, next), rotated); assertEquals(rotated, OceanbaseSourceGeneration.rotate(ob, pin, next, 15));
        assertThrows(SQLException.class, () -> OceanbaseSourceGeneration.rotate(ob, pin, pin.generation(), 15));
        assertThrows(SQLException.class, () -> OceanbaseSourceGeneration.rotate(ob, new SourceGenerationPin(schema, UUID.randomUUID().toString(), pin.generation()), next, 15));
        ob.setAutoCommit(false);
        try { assertThrows(SQLException.class, () -> OceanbaseSourceGeneration.initialize(ob, schema, 15)); } finally { ob.rollback(); ob.setAutoCommit(true); }
        assertEquals(List.of("0"), query(ob, "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='" + schema + "'"));
    }

    @Test void postgresCivilInstantAndBinaryPayloadsArriveExactlyInOceanbase() throws Exception {
        sql(pg, "CREATE TABLE " + t(PG, "s") + "(wm NUMERIC(38,18) NOT NULL,id BIGINT PRIMARY KEY,small SMALLINT,ub NUMERIC(20,0),amount NUMERIC(38,18),note VARCHAR(80),"
                + "payload BYTEA,day DATE,happened TIMESTAMP(6),instant TIMESTAMPTZ(6))");
        sql(ob, "CREATE TABLE " + t(OB, "t") + "(wm DECIMAL(38,18) NOT NULL,id BIGINT PRIMARY KEY,small SMALLINT,ub BIGINT UNSIGNED,amount DECIMAL(38,18),note VARCHAR(80),"
                + "payload VARBINARY(16),day DATE,happened DATETIME(6),instant TIMESTAMP(6) NULL)");
        sql(pg, "INSERT INTO " + t(PG, "s") + " VALUES(10,1,-32768,18446744073709551615,-0.000000000000000001,'汉字''\\\"; 🙂  ','\\x0027ff5c','0001-01-01',"
                + "'1988-04-17 02:30:00.123456','2026-03-08 02:30:00.000001+08'),(10,2,32767,0,12345678901234567890.123456789012345678,'','\\x','9999-12-31',"
                + "'9999-12-31 23:59:59.999999','1970-01-01 00:00:00+00'),(10,3,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL)");
        var result = new MigrationService(request(PG, OB), ignored -> { }, null, runtime).run();
        assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString());
        sql(ob, "SET SESSION time_zone='+00:00'");
        var expected = texts(PG, "SELECT id,small::text,ub::text,amount::text,note,upper(encode(payload,'hex')),to_char(day,'YYYY-MM-DD'),to_char(happened,'YYYY-MM-DD HH24:MI:SS.US'),"
                + "to_char(instant AT TIME ZONE 'UTC','YYYY-MM-DD HH24:MI:SS.US') FROM " + t(PG, "s") + " ORDER BY 1");
        assertEquals(expected, texts(OB, "SELECT id,CAST(small AS CHAR),CAST(ub AS CHAR),CAST(amount AS CHAR),note,HEX(payload),CAST(day AS CHAR),CAST(happened AS CHAR),CAST(instant AS CHAR) FROM "
                + t(OB, "t") + " ORDER BY id"));
        assertEquals(3, expected.size());
        assertEquals("1|-32768|18446744073709551615|-0.000000000000000001|汉字'\\\"; 🙂  |0027FF5C|0001-01-01|1988-04-17 02:30:00.123456|2026-03-07 18:30:00.000001", expected.get(0));
    }

    @ParameterizedTest @ValueSource(strings = {"WEAK_READ", "OPEN_TRANSACTION", "GBK_READER", "NO_BACKSLASH_ESCAPES", "NON_STRICT_WRITER", "GBK_WRITER"})
    void sessionProfileIsProvenOnTheActualConnectionBeforeReadingOrWriting(String kind) throws Exception {
        create(OB, "s"); create(OB, "t"); rows(OB, 1); var sessions = new AtomicInteger();
        DataSource altered = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection connection = open(OB);
                try {
                    switch (kind) {
                        case "WEAK_READ" -> sql(connection, "SET SESSION ob_read_consistency='WEAK'");
                        case "OPEN_TRANSACTION" -> { sql(connection, "BEGIN"); sql(connection, "INSERT INTO " + t(OB, "s") + " VALUES(99," + (100 + sessions.incrementAndGet()) + ",99,'uncommitted')"); }
                        case "GBK_READER", "GBK_WRITER" -> sql(connection, "SET NAMES gbk");
                        case "NO_BACKSLASH_ESCAPES" -> sql(connection, "SET SESSION sql_mode='STRICT_ALL_TABLES,NO_BACKSLASH_ESCAPES'");
                        default -> sql(connection, "SET SESSION sql_mode=''");
                    }
                    return connection;
                } catch (SQLException failure) { connection.close(); throw failure; }
            }
            @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        };
        boolean writer = kind.endsWith("_WRITER"); var result = new DbSwitchTableResult();
        assertThrows(IllegalArgumentException.class, () -> CursorTransfer.execute(runtime, writer ? ds(OB) : altered, writer ? altered : ds(OB), OB, OB, schema, "s", schema, "t",
                fields, fields, keys, keys, "wm", limits, 2, binding(this::save), result, () -> { }));
        assertNull(saved.get()); assertEquals(0, count(OB, "t")); assertEquals(1, count(OB, "s"));
        // The identical tables move through an unaltered session, so the deviation alone caused the refusal.
        transfer(OB, OB, ds(OB), this::save);
        assertEquals(1, count(OB, "t")); assertTrue(saved.get().complete());
    }

    @Test void civilClockDefaultsOutsideTheWrittenFieldsRefuseInstantWritesInsteadOfShiftingWithTheUtcSession() throws Exception {
        sql(ob, "CREATE TABLE " + t(OB, "t") + "(k BIGINT PRIMARY KEY,instant TIMESTAMP(6) NULL,v INT,created DATETIME DEFAULT CURRENT_TIMESTAMP,"
                + "stamped TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6))");
        var writer = runtime.upsertWriter(ds(OB), OB);
        // An instant is bound as UTC text in a temporary UTC session; the untouched civil default would shift by the zone offset.
        assertThrows(IllegalArgumentException.class, () -> writer.prepareUpsert(schema, "t", List.of("k", "instant"), List.of("k")));
        assertEquals(0, count(OB, "t"));
        // Without an instant field the session zone is never changed: the default keeps the session's own clock.
        writer.prepareUpsert(schema, "t", List.of("k", "v"), List.of("k"));
        assertEquals(1, writer.writeWithReceipt(List.of("k", "v"), Collections.singletonList(new Object[]{1L, 1})).acknowledgedRows());
        assertEquals(List.of("1"), texts(OB, "SELECT ABS(TIMESTAMPDIFF(SECOND,created,NOW()))<300 FROM " + t(OB, "t") + " WHERE k=1"));
        // Writing the civil column explicitly removes the dependency; the instant default column is zone independent.
        var explicit = List.of("k", "instant", "created");
        writer.prepareUpsert(schema, "t", explicit, List.of("k"));
        assertEquals(1, writer.writeWithReceipt(explicit, Collections.singletonList(new Object[]{2L, OffsetDateTime.parse("2026-03-08T02:30:00.000001+08:00"),
                LocalDateTime.parse("1988-04-17T02:30:00")})).acknowledgedRows());
        sql(ob, "SET SESSION time_zone='+00:00'");
        assertEquals(List.of("2026-03-07 18:30:00.000001|1988-04-17 02:30:00|1"),
                texts(OB, "SELECT CAST(instant AS CHAR),CAST(created AS CHAR),ABS(TIMESTAMPDIFF(SECOND,stamped,NOW()))<300 FROM " + t(OB, "t") + " WHERE k=2"));
    }

    // ---- fixture ---------------------------------------------------------------------------------------

    private MigrationRequest request(ProductTypeEnum source, ProductTypeEnum target) {
        var request = new MigrationRequest(); var input = new SourceDataSourceProperties();
        input.setSourceProductType(source); input.setUrl(url(source)); input.setUsername(user(source)); input.setPassword(password(source)); input.setPoolSize(1);
        input.setSourceSchema(schema); input.setSourceIncludes("s"); input.setFetchSize(2); input.setIncreaseColumnName("wm");
        input.setCursorWatermarkContract("MONOTONIC_WATERMARK_V1"); input.setRegexTableMapper(List.of(new PatternMapper("^s$", "t"))); request.getSource().add(input);
        var output = request.getTarget(); output.setTargetProductType(target); output.setUrl(url(target)); output.setUsername(user(target)); output.setPassword(password(target));
        output.setTargetSchema(schema); output.setPoolSize(1); output.setChangeDataSync(true); output.setChangeDataSyncType("INCREASE_COLUMN");
        output.setCursorRunBindings(Map.of("s", binding(this::save)));
        return request;
    }
    private CursorRunBinding binding(CursorRunBinding.CheckpointStore store) { return new CursorRunBinding("e".repeat(64), saved.get(), store, pin); }
    private boolean save(CursorCheckpoint checkpoint) { saved.set(checkpoint); history.add(checkpoint); return true; }
    private DbSwitchTableResult transfer(ProductTypeEnum source, ProductTypeEnum target, DataSource targetSource, CursorRunBinding.CheckpointStore store) throws Exception {
        return transfer(source, target, "s", "t", fields, keys, targetSource, store);
    }
    private DbSwitchTableResult transfer(ProductTypeEnum source, ProductTypeEnum target, List<String> names, List<String> key, CursorRunBinding.CheckpointStore store) throws Exception {
        return transfer(source, target, "s", "t", names, key, ds(target), store);
    }
    private DbSwitchTableResult transfer(ProductTypeEnum source, ProductTypeEnum target, String from, String to, List<String> names, List<String> key,
                                         DataSource targetSource, CursorRunBinding.CheckpointStore store) throws Exception {
        var result = new DbSwitchTableResult();
        CursorTransfer.execute(runtime, ds(source), targetSource, source, target, schema, from, schema, to, names, names, key, key, "wm", limits, 2, binding(store), result, () -> { });
        return result;
    }
    private void create(ProductTypeEnum product, String table) throws SQLException {
        sql(control(product), "CREATE TABLE " + t(product, table) + "(wm DECIMAL(38,18) NOT NULL,k1 BIGINT NOT NULL,k2 DECIMAL(38,0) NOT NULL,v VARCHAR(80),PRIMARY KEY(k2,k1))");
    }
    private void rows(ProductTypeEnum product, int count) throws SQLException {
        for (int i = 1; i <= count; i++) sql(control(product), "INSERT INTO " + t(product, "s") + " VALUES(10," + i + "," + (i % 2) + ",'original')");
    }
    private long count(ProductTypeEnum product, String table) throws SQLException { return Long.parseLong(query(control(product), "SELECT COUNT(*) FROM " + t(product, table)).getFirst()); }
    private List<String> texts(ProductTypeEnum product, String sql) throws SQLException { return query(control(product), sql); }
    private static List<String> query(Connection connection, String sql) throws SQLException {
        var values = new ArrayList<String>();
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(20);
            try (var rows = statement.executeQuery(sql)) {
                int columns = rows.getMetaData().getColumnCount();
                while (rows.next()) {
                    var line = new StringJoiner("|");
                    for (int i = 1; i <= columns; i++) line.add(String.valueOf(rows.getString(i)));
                    values.add(line.toString());
                }
            }
        }
        return values;
    }
    /** An aborted isolated-driver connection drains its runtime until older leases, including this control, exit. */
    private void reopen() throws SQLException {
        if (ob != null) try { ob.close(); } catch (SQLException ignored) { }
        ob = null; ob = open(OB);
    }
    private Connection control(ProductTypeEnum product) { return product == OB ? ob : pg; }
    private Connection open(ProductTypeEnum product) throws SQLException { return runtime.openConnection(product, null, url(product), user(product), password(product)); }
    private DataSource ds(ProductTypeEnum product) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { return open(product); }
            @Override public Connection getConnection(String user, String password) throws SQLException { throw new SQLException("Explicit fixture principal required"); }
        };
    }
    private String t(ProductTypeEnum product, String table) { return product == OB ? q(schema) + "." + q(table) : schema + "." + table; }
    private static String q(String name) { return '`' + name.replace("`", "``") + '`'; }
    private static void sql(Connection connection, String sql) throws SQLException { try (var statement = connection.createStatement()) { statement.setQueryTimeout(20); statement.execute(sql); } }
    private static String url(ProductTypeEnum product) { return System.getProperty(product == OB ? "datahub.test.oceanbase.url" : "datahub.test.postgres.url"); }
    private static String user(ProductTypeEnum product) { return product == OB ? System.getProperty("datahub.test.oceanbase.user", "root@test") : System.getProperty("datahub.test.postgres.user", "dataforge_test"); }
    private static String password(ProductTypeEnum product) { return System.getProperty(product == OB ? "datahub.test.oceanbase.password" : "datahub.test.postgres.password", ""); }
}

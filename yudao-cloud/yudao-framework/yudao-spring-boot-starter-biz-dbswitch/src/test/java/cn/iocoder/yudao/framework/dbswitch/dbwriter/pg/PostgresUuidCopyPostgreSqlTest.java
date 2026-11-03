package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.BatchRejectedException;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.driver.VerifiedDriverTestHandle;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.PGConnection;
import org.postgresql.util.PGobject;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Finite native uuid binary COPY, including the public FULL migration path on supplied PG16/18. */
@Timeout(45)
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/.*")
class PostgresUuidCopyPostgreSqlTest {
    private HikariDataSource pool;
    private Connection observer;
    private String schema;
    private String fault = "";
    private final AtomicInteger copies = new AtomicInteger();
    private final AtomicInteger borrowed = new AtomicInteger();

    @BeforeEach void setup() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
        String user = System.getProperty("datahub.test.postgres.user", "dataforge_test");
        pool = new HikariDataSource(); pool.setJdbcUrl(url + "?socketTimeout=5"); pool.setUsername(user);
        pool.setMaximumPoolSize(2); pool.setMinimumIdle(0); pool.setConnectionTimeout(3000);
        observer = DriverManager.getConnection(url, user, "");
        schema = "uuid73_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
        sql("CREATE TABLE " + schema + ".rows(id integer PRIMARY KEY,value uuid)");
    }

    @AfterEach void cleanup() throws Exception {
        Thread.interrupted();
        if (pool != null) pool.close();
        if (observer != null) try (var owned = observer) {
            assertTrue(schema.matches("uuid73_[a-f0-9]{32}"));
            sql("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test void everyUuidBitAndNullRoundTripsThroughNativeBinaryCopyWithoutMutatingInputs() throws Exception {
        var writer = writer();
        List<Object[]> input = new ArrayList<>();
        List<UUID> values = new ArrayList<>();
        values.add(new UUID(0, 0)); values.add(new UUID(-1, -1));
        values.add(new UUID(Long.MIN_VALUE, Long.MAX_VALUE));
        values.add(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"));
        for (int bit = 0; bit < 128; bit++) values.add(bit < 64 ? new UUID(1L << bit, 0) : new UUID(0, 1L << (bit - 64)));
        values.add(null);
        for (int index = 0; index < values.size(); index++) input.add(new Object[]{index, values.get(index)});
        var original = input.stream().map(Object[]::clone).toList();
        var result = writer.writeWithReceipt(List.of("id", "value"), input);
        assertEquals(values.size(), result.acknowledgedRows()); assertEquals(1, copies.get());
        if (observer.getMetaData().getDatabaseMajorVersion() == 18) assertNull(result.receipt());
        else assertNotNull(result.receipt());
        try (var statement = observer.createStatement(); var rows = statement.executeQuery("SELECT id,value FROM " + schema + ".rows ORDER BY id")) {
            for (int index = 0; index < values.size(); index++) {
                assertTrue(rows.next()); assertEquals(index, rows.getInt(1));
                assertEquals(values.get(index), rows.getObject(2, UUID.class));
                assertArrayEquals(original.get(index), input.get(index));
                assertSame(original.get(index)[1], input.get(index)[1]);
            }
            assertFalse(rows.next());
        }
        try (var statement = observer.createStatement(); var rows = statement.executeQuery("SELECT a.atttypid,a.atttypmod,a.attndims,t.typtype,n.nspname FROM pg_attribute a JOIN pg_type t ON t.oid=a.atttypid JOIN pg_namespace n ON n.oid=t.typnamespace WHERE a.attrelid='" + schema + ".rows'::regclass AND a.attname='value'")) {
            assertTrue(rows.next()); assertEquals(2950, rows.getInt(1)); assertEquals(-1, rows.getInt(2));
            assertEquals(0, rows.getInt(3)); assertEquals("b", rows.getString(4)); assertEquals("pg_catalog", rows.getString(5));
        }
        assertPoolUsable();
    }

    @ParameterizedTest @ValueSource(strings = {"string", "bytes", "number", "pgobject", "array", "object"})
    void wrongRuntimeValueRejectsTheWholeBatchBeforeBorrowingOrCopy(String kind) throws Exception {
        var writer = writer();
        PGobject pg = new PGobject(); pg.setType("uuid"); pg.setValue("00112233-4455-6677-8899-aabbccddeeff");
        Object wrong = switch (kind) {
            case "string" -> "00112233-4455-6677-8899-aabbccddeeff";
            case "bytes" -> new byte[16]; case "number" -> 0; case "pgobject" -> pg;
            case "array" -> new UUID[]{new UUID(0, 0)};
            default -> new Object() { @Override public String toString() { throw new AssertionError("UUID must not be guessed from toString"); } };
        };
        Object[] good = {1, new UUID(0, 0)}, bad = {2, wrong};
        int before = borrowed.get();
        var failure = assertThrows(BatchRejectedException.class, () -> writer.write(List.of("id", "value"), List.of(good, bad)));
        assertNull(failure.getCause()); assertFalse(failure.getMessage().contains("00112233"));
        assertEquals(before, borrowed.get()); assertEquals(0, copies.get()); assertEquals(0, count());
        assertSame(wrong, bad[1]); assertEquals(new UUID(0, 0), good[1]);
        assertEquals(1, writer.write(List.of("id", "value"), List.<Object[]>of(new Object[]{3, new UUID(0, 1)})));
        assertPoolUsable();
    }

    @ParameterizedTest @ValueSource(strings = {"domain", "array", "oid", "typmod", "namespace"})
    void domainArrayOrInconsistentNativeUuidFactsCannotOpenCopy(String kind) throws Exception {
        if (kind.equals("domain")) {
            sql("CREATE DOMAIN " + schema + ".uuid_alias AS uuid");
            sql("ALTER TABLE " + schema + ".rows ALTER COLUMN value TYPE " + schema + ".uuid_alias");
        } else if (kind.equals("array")) sql("ALTER TABLE " + schema + ".rows ALTER COLUMN value TYPE uuid[] USING ARRAY[value]");
        else fault = kind;
        var writer = new PostgresCopyWriterImpl(facade());
        assertThrows(SQLFeatureNotSupportedException.class, () -> writer.prepareWrite(schema, "rows", List.of("id", "value")));
        assertThrows(SQLFeatureNotSupportedException.class, () -> writer.write(List.of("id", "value"), List.<Object[]>of(new Object[]{1, new UUID(0, 0)})));
        assertEquals(0, copies.get()); assertEquals(0, count()); assertPoolUsable();
    }

    @Test void changedUuidOidAfterPreparationIsRejectedBeforeProtocol() throws Exception {
        var writer = writer(); fault = "oid";
        var failure = assertThrows(IllegalStateException.class, () -> writer.write(List.of("id", "value"), List.<Object[]>of(new Object[]{1, new UUID(0, 0)})));
        assertNull(failure.getCause()); assertEquals(0, copies.get()); assertEquals(0, count()); assertPoolUsable();
    }

    @Test void sameSpelledUuidDomainCannotGrantAPlannedBuiltinBeforeTargetCreation() throws Exception {
        sql("CREATE DOMAIN " + schema + ".uuid AS pg_catalog.uuid");
        pool.setConnectionInitSql("SET search_path=" + schema + ",pg_catalog");
        var writer = new PostgresCopyWriterImpl(facade());
        writer.preflightTargetDefinition(schema, "copied", List.of("CREATE TABLE " + schema + ".copied(value uuid)"), List.of("value"));
        assertThrows(SQLFeatureNotSupportedException.class, writer::preflightRuntimeWrite);
        assertEquals(0, copies.get());
        try (var query = observer.prepareStatement("SELECT pg_catalog.to_regclass(?)")) {
            query.setString(1, schema + ".copied"); try (var rows = query.executeQuery()) { assertTrue(rows.next()); assertNull(rows.getObject(1)); }
        }
        assertPoolUsable();
    }

    @Test void rejectedBudgetThenAcceptedBatchDoesNotRetainResources() throws Exception {
        var writer = writer(); writer.configureBatchLimits(new BatchLimits(1, 1024, 128, 2, 1000));
        int before = borrowed.get();
        assertThrows(BatchRejectedException.class, () -> writer.write(List.of("id", "value"), List.of(new Object[]{1, new UUID(0, 0)}, new Object[]{2, null})));
        assertEquals(before, borrowed.get()); assertEquals(0, copies.get()); assertEquals(0, count());
        assertEquals(1, writer.write(List.of("id", "value"), List.<Object[]>of(new Object[]{3, null})));
        assertPoolUsable();
    }

    @Test void constraintFailureKeepsUnknownWriteContractAndRollsBackEntireUuidBatch() throws Exception {
        var writer = writer();
        assertThrows(cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException.class,
                () -> writer.write(List.of("id", "value"), List.of(new Object[]{1, new UUID(0, 0)}, new Object[]{1, new UUID(-1, -1)})));
        assertEquals(0, count()); assertPoolUsable();
        assertEquals(1, writer.write(List.of("id", "value"), List.<Object[]>of(new Object[]{2, new UUID(-1, -1)})));
        assertEquals(2, copies.get()); assertPoolUsable();
    }

    @ParameterizedTest @ValueSource(strings = {"create", "drop", "truncate", "append", "constraint-failure"})
    void publicFullCopyMigrationPreservesUuidThroughAutoDdlAndExistingTargets(String mode) throws Exception {
        sql("CREATE TABLE " + schema + ".source(id integer PRIMARY KEY,value uuid)");
        sql("INSERT INTO " + schema + ".source VALUES(1,'00112233-4455-6677-8899-aabbccddeeff'),(2,'ffffffff-ffff-ffff-ffff-ffffffffffff'),(3,NULL)");
        if (!mode.equals("create")) {
            sql("CREATE TABLE " + schema + ".copied(id integer PRIMARY KEY,value uuid" + (mode.equals("constraint-failure") ? " NOT NULL" : "") + ")");
            sql("INSERT INTO " + schema + ".copied VALUES(9,'00000000-0000-0000-0000-000000000009')");
        }
        Path artifact = Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var spec = new VerifiedDriverTestHandle.Spec(artifact.toString(), VerifiedDriverTestHandle.sha256(artifact), "org.postgresql.Driver");
        var budget = new cn.iocoder.yudao.framework.dbswitch.driver.ConnectionBudget(8, 2);
        try (var drivers = new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.POSTGRESQL, spec), Map.of(), budget);
             var runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), DbSwitchOptions.defaults(), drivers)) {
            var request = new MigrationRequest(); var source = new SourceDataSourceProperties();
            source.setSourceProductType(ProductTypeEnum.POSTGRESQL); source.setUrl(System.getProperty("datahub.test.postgres.url"));
            source.setUsername(System.getProperty("datahub.test.postgres.user", "dataforge_test")); source.setPassword(""); source.setDriverClassName("org.postgresql.Driver");
            source.setSourceSchema(schema); source.setIncludeOrExclude(1); source.setSourceTableNames(List.of("source")); source.setPoolSize(1);
            source.setRegexTableMapper(List.of(new PatternMapper("^source$", "copied"))); request.getSource().add(source);
            var target = request.getTarget(); target.setTargetProductType(ProductTypeEnum.POSTGRESQL); target.setUrl(source.getUrl());
            target.setUsername(source.getUsername()); target.setPassword(""); target.setDriverClassName("org.postgresql.Driver");
            target.setTargetSchema(schema); target.setPoolSize(1); target.setWriterEngineInsert(false);
            target.setTargetDrop(mode.equals("drop")); target.setTargetTruncateData(mode.equals("truncate"));
            var service = new MigrationService(request, ignored -> { }, null, runtime);
            try {
                var result = service.run();
                if (mode.equals("constraint-failure")) assertFalse(result.getIfAllSuccess().get());
                else {
                    assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString());
                    assertEquals(3, result.getTableResultList().getFirst().getSyncCount().get());
                }
            } finally { service.physicalExit().toCompletableFuture().get(20, TimeUnit.SECONDS); }
        }
        assertEquals(new cn.iocoder.yudao.framework.dbswitch.driver.ConnectionBudget.Snapshot(8, 2, 0, 0, 0, 0, 0, 0), budget.snapshot());
        try (var statement = observer.createStatement(); var rows = statement.executeQuery("SELECT id,value FROM " + schema + ".copied ORDER BY id")) {
            if (mode.equals("constraint-failure")) {
                assertTrue(rows.next()); assertEquals(9, rows.getInt(1)); assertEquals(new UUID(0, 9), rows.getObject(2));
                assertFalse(rows.next()); return;
            }
            assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); assertEquals(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"), rows.getObject(2));
            assertTrue(rows.next()); assertEquals(2, rows.getInt(1)); assertEquals(new UUID(-1, -1), rows.getObject(2));
            assertTrue(rows.next()); assertEquals(3, rows.getInt(1)); assertNull(rows.getObject(2));
            if (mode.equals("append")) { assertTrue(rows.next()); assertEquals(9, rows.getInt(1)); }
            assertFalse(rows.next());
        }
    }

    private PostgresCopyWriterImpl writer() {
        var writer = new PostgresCopyWriterImpl(facade()); writer.preflightRuntimeWrite();
        writer.prepareWrite(schema, "rows", List.of("id", "value")); return writer;
    }
    private DataSource facade() {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) { borrowed.incrementAndGet(); return wrap(pool.getConnection()); }
                    return invoke(pool, method, args);
                });
    }
    private Connection wrap(Connection actual) throws Exception {
        PGConnection pg = actual.unwrap(PGConnection.class);
        PGConnection nativeFacade = (PGConnection) Proxy.newProxyInstance(PGConnection.class.getClassLoader(), new Class<?>[]{PGConnection.class},
                (proxy, method, args) -> { if (method.getName().equals("getCopyAPI")) copies.incrementAndGet(); return invoke(pg, method, args); });
        return (Connection) Proxy.newProxyInstance(PGConnection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("unwrap") && args[0] == PGConnection.class) return nativeFacade;
                    if (method.getName().equals("isWrapperFor") && args[0] == PGConnection.class) return true;
                    Object result = invoke(actual, method, args);
                    if (method.getName().equals("prepareStatement") && ((String) args[0]).contains("a.atttypmod")) {
                        var query = (PreparedStatement) result;
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                            Object value = invoke(query, m, a);
                            if (!m.getName().equals("executeQuery")) return value;
                            var rows = (ResultSet) value;
                            return Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class}, (rp, rm, ra) -> {
                                if ((rm.getName().equals("getString") || rm.getName().equals("getInt"))
                                        && ra != null && ra.length > 0 && ra[0] instanceof Integer column && "uuid".equals(rows.getString(8))) {
                                    if (rm.getName().equals("getString") && column == 14 && fault.equals("oid")) return "25";
                                    if (rm.getName().equals("getString") && column == 9 && fault.equals("namespace")) return schema;
                                    if (rm.getName().equals("getInt") && column == 10 && fault.equals("typmod")) return 1;
                                }
                                return invoke(rows, rm, ra);
                            });
                        });
                    }
                    return result;
                });
    }
    private void assertPoolUsable() throws Exception {
        try (var connection = pool.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT 1")) {
            assertTrue(connection.getAutoCommit()); assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
        }
        assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
    }
    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private void sql(String sql) throws Exception { try (var statement = observer.createStatement()) { statement.execute(sql); } }
    private long count() throws Exception {
        try (var statement = observer.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM " + schema + ".rows")) { assertTrue(rows.next()); return rows.getLong(1); }
    }
}

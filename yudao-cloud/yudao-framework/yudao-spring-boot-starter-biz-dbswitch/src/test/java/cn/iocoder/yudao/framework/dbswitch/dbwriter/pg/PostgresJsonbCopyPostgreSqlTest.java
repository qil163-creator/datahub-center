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
import cn.iocoder.yudao.framework.dbswitch.pgwriter.pgsql.constants.ObjectIdentifier;
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
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Finite builtin JSONB COPY with native PGobject input, never arbitrary OTHER/string inference. */
@Timeout(45)
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/.*")
class PostgresJsonbCopyPostgreSqlTest {
    private HikariDataSource pool;
    private Connection observer;
    private String schema;
    private String fault = "";
    private final AtomicInteger copies = new AtomicInteger();
    private final AtomicInteger borrowed = new AtomicInteger();
    private static final String EXACT = "{\"decimal\":123456789012345678901234567890.12345678901234567890,"
            + "\"unicode\":\"中文 😀 \\uD800\\uDC00\",\"array\":[true,false,null,{},[]],\"empty\":\"\"}";

    @BeforeEach void setup() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
        String user = System.getProperty("datahub.test.postgres.user", "dataforge_test");
        pool = new HikariDataSource(); pool.setJdbcUrl(url + "?socketTimeout=5"); pool.setUsername(user);
        pool.setMaximumPoolSize(2); pool.setMinimumIdle(0); pool.setConnectionTimeout(3000);
        observer = DriverManager.getConnection(url, user, "");
        schema = "jsonb74_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
        sql("CREATE TABLE " + schema + ".rows(id integer PRIMARY KEY,value jsonb)");
    }

    @AfterEach void cleanup() throws Exception {
        Thread.interrupted();
        if (pool != null) pool.close();
        if (observer != null) try (var owned = observer) {
            assertTrue(schema.matches("jsonb74_[a-f0-9]{32}"));
            sql("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test void builtinJsonAndJsonbHaveDistinctNativeOids() throws Exception {
        try (var query = observer.createStatement(); var rows = query.executeQuery(
                "SELECT 'pg_catalog.json'::regtype::oid,'pg_catalog.jsonb'::regtype::oid")) {
            assertTrue(rows.next()); assertEquals(114, rows.getInt(1)); assertEquals(3802, rows.getInt(2));
            assertEquals(rows.getInt(2), ObjectIdentifier.Jsonb); assertNotEquals(rows.getInt(1), ObjectIdentifier.Jsonb);
        }
    }

    @Test void validRootScalarsContainersAndDecimalSpellingsPreserveJsonbSemantics() throws Exception {
        var writer = writer(); var values = List.of("true", "false", "0", "-0.0000", "1.25e-30", "-1.25E+30",
                "\"\"", "\"中文 😀\"", "[]", "{}", "[1,true,null,{\"a\":2}]", "{\"duplicate\":1,\"duplicate\":2}");
        var input = new java.util.ArrayList<Object[]>();
        for (int index = 0; index < values.size(); index++) input.add(new Object[]{index, jsonb(values.get(index))});
        assertEquals(values.size(), writer.write(List.of("id", "value"), input));
        try (var query = observer.prepareStatement("SELECT value=?::jsonb FROM " + schema + ".rows WHERE id=?")) {
            for (int index = 0; index < values.size(); index++) {
                query.setString(1, values.get(index)); query.setInt(2, index);
                try (var rows = query.executeQuery()) { assertTrue(rows.next()); assertTrue(rows.getBoolean(1)); assertFalse(rows.next()); }
                assertEquals(values.get(index), ((PGobject) input.get(index)[1]).getValue());
            }
        }
        assertPoolUsable();
    }

    @Test void sameNamedCarrierFromAlteredDriverArtifactCannotGrantNativeJsonb() throws Exception {
        var writer = writer();
        Path original = Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path altered = Files.createTempFile("jsonb74-driver-", ".jar");
        try {
            Files.copy(original, altered, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.write(altered, new byte[]{0}, StandardOpenOption.APPEND);
            try (var loader = new URLClassLoader(new java.net.URL[]{altered.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                Class<?> type = Class.forName("org.postgresql.util.PGobject", true, loader);
                Object value = type.getConstructor().newInstance(); type.getMethod("setType", String.class).invoke(value, "jsonb");
                type.getMethod("setValue", String.class).invoke(value, "{}");
                try { rejectBeforeBorrow(writer, value); }
                finally { Class.forName("org.postgresql.Driver", true, loader).getMethod("deregister").invoke(null); }
            }
        } finally { Files.deleteIfExists(altered); }
    }

    @Test void carrierSplitFromPinnedDriverArtifactIsRejectedBeforeBorrow() throws Exception {
        var writer = writer();
        Path original = Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path shadow = Files.createTempFile("jsonb74-carrier-", ".jar");
        try {
            try (var artifact = new java.util.jar.JarFile(original.toFile());
                 var output = new java.util.jar.JarOutputStream(Files.newOutputStream(shadow))) {
                String entry = "org/postgresql/util/PGobject.class";
                output.putNextEntry(new java.util.jar.JarEntry(entry));
                try (var bytes = artifact.getInputStream(artifact.getJarEntry(entry))) { bytes.transferTo(output); }
                output.closeEntry();
            }
            try (var loader = new URLClassLoader(new java.net.URL[]{shadow.toUri().toURL(), original.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader())) {
                Class<?> carrier = Class.forName("org.postgresql.util.PGobject", true, loader);
                Class<?> driver = Class.forName("org.postgresql.Driver", true, loader);
                try {
                    assertSame(carrier.getClassLoader(), driver.getClassLoader());
                    assertNotEquals(carrier.getProtectionDomain().getCodeSource().getLocation(),
                            driver.getProtectionDomain().getCodeSource().getLocation());
                    Object value = carrier.getConstructor().newInstance();
                    carrier.getMethod("setType", String.class).invoke(value, "jsonb");
                    carrier.getMethod("setValue", String.class).invoke(value, "{}");
                    rejectBeforeBorrow(writer, value);
                } finally { driver.getMethod("deregister").invoke(null); }
            }
        } finally { Files.deleteIfExists(shadow); }
    }

    @Test void catalogOidAndProtocolPreserveExactNumbersUnicodeSqlNullAndJsonNull() throws Exception {
        var writer = writer();
        PGobject document = jsonb(EXACT), jsonNull = jsonb("null");
        Object[] first = {1, document}, second = {2, jsonNull}, third = {3, null};
        var result = writer.writeWithReceipt(List.of("id", "value"), List.of(first, second, third));
        assertEquals(3, result.acknowledgedRows()); assertEquals(1, copies.get());
        if (observer.getMetaData().getDatabaseMajorVersion() == 18) assertNull(result.receipt());
        else assertNotNull(result.receipt());
        assertSame(document, first[1]); assertEquals(EXACT, document.getValue());
        assertSame(jsonNull, second[1]); assertEquals("null", jsonNull.getValue()); assertNull(third[1]);
        try (var query = observer.createStatement(); var rows = query.executeQuery("SELECT value->>'decimal',value->>'unicode',value IS NULL,jsonb_typeof(value) FROM " + schema + ".rows ORDER BY id")) {
            assertTrue(rows.next()); assertEquals(new BigDecimal("123456789012345678901234567890.12345678901234567890"), rows.getBigDecimal(1));
            assertEquals("中文 😀 \uD800\uDC00", rows.getString(2)); assertFalse(rows.getBoolean(3)); assertEquals("object", rows.getString(4));
            assertTrue(rows.next()); assertFalse(rows.getBoolean(3)); assertEquals("null", rows.getString(4));
            assertTrue(rows.next()); assertTrue(rows.getBoolean(3)); assertNull(rows.getString(4)); assertFalse(rows.next());
        }
        try (var query = observer.createStatement(); var rows = query.executeQuery("SELECT a.atttypid,a.atttypmod,a.attndims,t.typtype,n.nspname FROM pg_attribute a JOIN pg_type t ON t.oid=a.atttypid JOIN pg_namespace n ON n.oid=t.typnamespace WHERE a.attrelid='" + schema + ".rows'::regclass AND a.attname='value'")) {
            assertTrue(rows.next()); assertEquals(3802, rows.getInt(1)); assertEquals(ObjectIdentifier.Jsonb, rows.getInt(1));
            assertEquals(-1, rows.getInt(2)); assertEquals(0, rows.getInt(3)); assertEquals("b", rows.getString(4)); assertEquals("pg_catalog", rows.getString(5));
        }
        assertPoolUsable();
    }

    @ParameterizedTest @ValueSource(strings = {"string", "bytes", "number", "json", "uuid", "subclass", "null-value", "object"})
    void otherValuesCannotBeGuessedFromStringsOrVendorObjects(String kind) throws Exception {
        var writer = writer();
        Object wrong = switch (kind) {
            case "string" -> EXACT; case "bytes" -> new byte[]{1, 2}; case "number" -> 1;
            case "json" -> pg("json", EXACT); case "uuid" -> pg("uuid", "00112233-4455-6677-8899-aabbccddeeff");
            case "subclass" -> new PGobject() { @Override public String getValue() { return EXACT; } };
            case "null-value" -> jsonb(null);
            default -> new Object() { @Override public String toString() { throw new AssertionError("Must not infer JSONB from toString"); } };
        };
        rejectBeforeBorrow(writer, wrong);
    }

    @ParameterizedTest @ValueSource(strings = {"syntax", "trailing", "comment", "empty", "nul", "escaped-nul", "high", "low", "reversed", "escaped-high", "escaped-low", "number-range", "number-length", "depth"})
    void badLaterJsonValueRejectsTheWholeBatchBeforeBorrowOrProtocol(String kind) throws Exception {
        var writer = writer();
        String invalid = switch (kind) {
            case "syntax" -> "{\"private-marker\":}"; case "trailing" -> "{} []";
            case "comment" -> "{/* private-marker */}"; case "empty" -> " ";
            case "nul" -> "\"private-marker\0\""; case "escaped-nul" -> "\"private-marker\\u0000\"";
            case "high" -> "\"private-marker\uD800\""; case "low" -> "\"private-marker\uDC00\"";
            case "reversed" -> "\"private-marker\uDC00\uD800\"";
            case "escaped-high" -> "\"private-marker\\uD800\""; case "escaped-low" -> "\"private-marker\\uDC00\"";
            case "number-range" -> "1e131068"; case "number-length" -> "1".repeat(1001);
            default -> "[".repeat(101) + "0" + "]".repeat(101);
        };
        rejectBeforeBorrow(writer, jsonb(invalid));
    }

    @ParameterizedTest @ValueSource(strings = {"domain", "array", "json", "oid", "typmod", "namespace"})
    void unverifiedNativeJsonLayoutCannotOpenCopy(String kind) throws Exception {
        if (kind.equals("domain")) { sql("CREATE DOMAIN " + schema + ".jsonb_alias AS jsonb"); sql("ALTER TABLE " + schema + ".rows ALTER COLUMN value TYPE " + schema + ".jsonb_alias"); }
        else if (kind.equals("array")) sql("ALTER TABLE " + schema + ".rows ALTER COLUMN value TYPE jsonb[] USING ARRAY[value]");
        else if (kind.equals("json")) sql("ALTER TABLE " + schema + ".rows ALTER COLUMN value TYPE json");
        else fault = kind;
        var writer = new PostgresCopyWriterImpl(facade());
        assertThrows(SQLFeatureNotSupportedException.class, () -> writer.prepareWrite(schema, "rows", List.of("id", "value")));
        assertThrows(SQLFeatureNotSupportedException.class, () -> writer.write(List.of("id", "value"), List.<Object[]>of(new Object[]{1, jsonb("{}") } )));
        assertEquals(0, copies.get()); assertEquals(0, count()); assertPoolUsable();
    }

    @Test void changedJsonbOidAfterPreparationIsRejectedBeforeProtocol() throws Exception {
        var writer = writer(); fault = "oid";
        var failure = assertThrows(IllegalStateException.class, () -> writer.write(List.of("id", "value"), List.<Object[]>of(new Object[]{1, jsonb("{}") })));
        assertNull(failure.getCause()); assertEquals(0, copies.get()); assertEquals(0, count()); assertPoolUsable();
    }

    @Test void sameSpelledDomainCannotGrantAPlannedBuiltin() throws Exception {
        sql("CREATE DOMAIN " + schema + ".jsonb AS pg_catalog.jsonb"); pool.setConnectionInitSql("SET search_path=" + schema + ",pg_catalog");
        var writer = new PostgresCopyWriterImpl(facade());
        writer.preflightTargetDefinition(schema, "copied", List.of("CREATE TABLE " + schema + ".copied(value jsonb)"), List.of("value"));
        assertThrows(SQLFeatureNotSupportedException.class, writer::preflightRuntimeWrite);
        assertEquals(0, copies.get()); assertPoolUsable();
    }

    @Test void utf8JsonbPayloadAndAggregateBudgetApplyBeforeBorrowing() throws Exception {
        var writer = writer(); PGobject value = jsonb("\"" + "中".repeat(40) + "\"");
        assertTrue(2L * value.getValue().length() < 100);
        writer.configureBatchLimits(new BatchLimits(10, 1000, 100, 2, 1000)); rejectBeforeBorrow(writer, value);
        writer.configureBatchLimits(new BatchLimits(10, 520, 250, 2, 1000));
        PGobject aggregate = jsonb("\"" + "中".repeat(60) + "\"");
        int before = borrowed.get(); Object[] a = {1, aggregate}, b = {2, aggregate}, c = {3, aggregate};
        assertThrows(BatchRejectedException.class, () -> writer.write(List.of("id", "value"), List.of(a, b, c)));
        assertEquals(before, borrowed.get()); assertEquals(0, copies.get()); assertEquals(0, count()); assertSame(aggregate, a[1]);
        writer.configureBatchLimits(BatchLimits.defaults()); assertEquals(1, writer.write(List.of("id", "value"), List.<Object[]>of(new Object[]{4, value})));
        assertPoolUsable();
    }

    @Test void duplicateKeyFailureRollsBackEntireBatchAndKeepsUnknownWriteContract() throws Exception {
        var writer = writer(); PGobject value = jsonb(EXACT);
        assertThrows(cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException.class,
                () -> writer.write(List.of("id", "value"), List.of(new Object[]{1, value}, new Object[]{1, jsonb("null")})));
        assertEquals(0, count()); assertPoolUsable();
        assertEquals(1, writer.write(List.of("id", "value"), List.<Object[]>of(new Object[]{2, value}))); assertEquals(2, copies.get());
    }

    @ParameterizedTest @ValueSource(strings = {"create", "drop", "truncate", "append", "constraint-failure", "budget-failure"})
    void publicFullCopyMigrationPreservesNativeJsonbAndReleasesAllBudgets(String mode) throws Exception {
        sql("CREATE TABLE " + schema + ".source(id integer PRIMARY KEY,value jsonb)");
        try (var insert = observer.prepareStatement("INSERT INTO " + schema + ".source VALUES(1,?::jsonb),(2,'null'::jsonb),(3,NULL)")) { insert.setString(1, EXACT); insert.executeUpdate(); }
        if (!mode.equals("create")) { sql("CREATE TABLE " + schema + ".copied(id integer PRIMARY KEY,value jsonb" + (mode.equals("constraint-failure") ? " NOT NULL" : "") + ")"); sql("INSERT INTO " + schema + ".copied VALUES(9,'{\"sentinel\":true}')"); }
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
            if (mode.equals("budget-failure")) target.setBatchLimits(new BatchLimits(10, 1024, 80, 2, 1000));
            var service = new MigrationService(request, ignored -> { }, null, runtime);
            try {
                var result = service.run();
                if (mode.endsWith("failure")) assertFalse(result.getIfAllSuccess().get());
                else { assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString()); assertEquals(3, result.getTableResultList().getFirst().getSyncCount().get()); }
            } finally { service.physicalExit().toCompletableFuture().get(20, TimeUnit.SECONDS); }
        }
        assertEquals(new cn.iocoder.yudao.framework.dbswitch.driver.ConnectionBudget.Snapshot(8, 2, 0, 0, 0, 0, 0, 0), budget.snapshot());
        try (var query = observer.createStatement(); var rows = query.executeQuery("SELECT id,value->>'decimal',value IS NULL,jsonb_typeof(value) FROM " + schema + ".copied ORDER BY id")) {
            if (mode.endsWith("failure")) { assertTrue(rows.next()); assertEquals(9, rows.getInt(1)); assertFalse(rows.next()); return; }
            assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); assertEquals(new BigDecimal("123456789012345678901234567890.12345678901234567890"), rows.getBigDecimal(2));
            assertTrue(rows.next()); assertEquals(2, rows.getInt(1)); assertFalse(rows.getBoolean(3)); assertEquals("null", rows.getString(4));
            assertTrue(rows.next()); assertEquals(3, rows.getInt(1)); assertTrue(rows.getBoolean(3));
            if (mode.equals("append")) { assertTrue(rows.next()); assertEquals(9, rows.getInt(1)); } assertFalse(rows.next());
        }
    }

    private void rejectBeforeBorrow(PostgresCopyWriterImpl writer, Object wrong) throws Exception {
        sql("INSERT INTO " + schema + ".rows VALUES(99,'{\"sentinel\":true}') ON CONFLICT DO NOTHING");
        PGobject valid = jsonb("{}"); Object[] good = {1, valid}, bad = {2, wrong}; int before = borrowed.get();
        var failure = assertThrows(BatchRejectedException.class, () -> writer.write(List.of("id", "value"), List.of(good, bad)));
        assertNull(failure.getCause()); assertFalse(failure.getMessage().contains("private-marker"));
        assertEquals(before, borrowed.get()); assertEquals(0, copies.get()); assertEquals(1, count()); assertSame(valid, good[1]); assertSame(wrong, bad[1]);
        try (var query = observer.createStatement(); var rows = query.executeQuery("SELECT id,value->>'sentinel' FROM " + schema + ".rows")) { assertTrue(rows.next()); assertEquals(99, rows.getInt(1)); assertEquals("true", rows.getString(2)); assertFalse(rows.next()); }
        sql("DELETE FROM " + schema + ".rows"); assertPoolUsable();
    }
    private static PGobject jsonb(String text) throws Exception { return pg("jsonb", text); }
    private static PGobject pg(String type, String text) throws Exception { var value = new PGobject(); value.setType(type); value.setValue(text); return value; }
    private PostgresCopyWriterImpl writer() { var writer = new PostgresCopyWriterImpl(facade()); writer.preflightRuntimeWrite(); writer.prepareWrite(schema, "rows", List.of("id", "value")); return writer; }
    private DataSource facade() {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
            if (method.getName().equals("getConnection")) { borrowed.incrementAndGet(); return wrap(pool.getConnection()); } return invoke(pool, method, args);
        });
    }
    private Connection wrap(Connection actual) throws Exception {
        PGConnection pg = actual.unwrap(PGConnection.class);
        PGConnection nativeFacade = (PGConnection) Proxy.newProxyInstance(PGConnection.class.getClassLoader(), new Class<?>[]{PGConnection.class}, (proxy, method, args) -> {
            if (method.getName().equals("getCopyAPI")) copies.incrementAndGet(); return invoke(pg, method, args);
        });
        return (Connection) Proxy.newProxyInstance(PGConnection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            if (method.getName().equals("unwrap") && args[0] == PGConnection.class) return nativeFacade;
            if (method.getName().equals("isWrapperFor") && args[0] == PGConnection.class) return true;
            Object result = invoke(actual, method, args);
            if (method.getName().equals("prepareStatement") && ((String) args[0]).contains("a.atttypmod")) {
                var query = (PreparedStatement) result;
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                    Object value = invoke(query, m, a); if (!m.getName().equals("executeQuery")) return value; var rows = (ResultSet) value;
                    return Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class}, (rp, rm, ra) -> {
                        if ((rm.getName().equals("getString") || rm.getName().equals("getInt")) && ra != null && ra.length > 0 && ra[0] instanceof Integer column && "jsonb".equals(rows.getString(8))) {
                            if (rm.getName().equals("getString") && column == 14 && fault.equals("oid")) return "114";
                            if (rm.getName().equals("getString") && column == 9 && fault.equals("namespace")) return schema;
                            if (rm.getName().equals("getInt") && column == 10 && fault.equals("typmod")) return 1;
                        } return invoke(rows, rm, ra);
                    });
                });
            } return result;
        });
    }
    private void assertPoolUsable() throws Exception { try (var connection = pool.getConnection(); var query = connection.createStatement(); var rows = query.executeQuery("SELECT 1")) { assertTrue(connection.getAutoCommit()); assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); } assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections()); }
    private static Object invoke(Object target, Method method, Object[] args) throws Throwable { try { return method.invoke(target, args); } catch (InvocationTargetException failure) { throw failure.getCause(); } }
    private void sql(String sql) throws Exception { try (var statement = observer.createStatement()) { statement.execute(sql); } }
    private long count() throws Exception { try (var query = observer.createStatement(); var rows = query.executeQuery("SELECT count(*) FROM " + schema + ".rows")) { assertTrue(rows.next()); return rows.getLong(1); } }
}

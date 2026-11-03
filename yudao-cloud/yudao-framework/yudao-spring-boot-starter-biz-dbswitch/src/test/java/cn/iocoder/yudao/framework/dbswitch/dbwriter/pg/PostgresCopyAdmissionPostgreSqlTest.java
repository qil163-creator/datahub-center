package cn.iocoder.yudao.framework.dbswitch.dbwriter.pg;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.postgresql.PGConnection;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Actual native COPY on the supplied isolated PG16.15 or PG18.4; no compatibility-product certification. */
@Timeout(30)
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/.*")
class PostgresCopyAdmissionPostgreSqlTest {
    @TempDir Path directory;
    private HikariDataSource pool;
    private Connection observer;
    private String schema;
    private String disguise = "";
    private final AtomicInteger copies = new AtomicInteger();
    private final AtomicInteger transactionChanges = new AtomicInteger();

    @BeforeEach void setup() throws Exception {
        String url = System.getProperty("datahub.test.postgres.url");
        assertTrue(url.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/[^?]+"));
        String user = System.getProperty("datahub.test.postgres.user", "dataforge_test");
        pool = new HikariDataSource(); pool.setJdbcUrl(url + "?socketTimeout=5"); pool.setUsername(user);
        pool.setMaximumPoolSize(2); pool.setMinimumIdle(0); pool.setConnectionTimeout(3000);
        observer = DriverManager.getConnection(url, user, "");
        schema = "copyadmission_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE SCHEMA " + schema);
        sql("CREATE TABLE " + schema + ".rows(id integer,note text)");
    }

    @AfterEach void cleanup() throws Exception {
        Thread.interrupted();
        if (pool != null) pool.close();
        if (observer != null) try (var owned = observer) {
            assertTrue(schema.matches("copyadmission_[a-f0-9]{32}"));
            sql("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test void exactPrimitiveNativeTypesCommitAndPg18CannotClaimPg16Receipt() throws Exception {
        sql("CREATE TABLE " + schema + ".typed(si smallint,i integer,id bigint,amount numeric,"
                + "f real,d double precision,b boolean,c char(1),v varchar(100),t text,bytes bytea,"
                + "day date,clock time(6),moment timestamp(6))");
        var fields = List.of("si", "i", "id", "amount", "f", "d", "b", "c", "v", "t", "bytes", "day", "clock", "moment");
        var decimal = new BigDecimal("123456789012345678901234567890.12345678901234567890");
        var moment = LocalDateTime.of(2026, 10, 7, 12, 34, 56, 123456000);
        var writer = new PostgresCopyWriterImpl(facade());
        writer.preflightRuntimeWrite();
        assertEquals(0, transactionChanges.get(), "runtime preflight must not change transaction state");
        assertEquals(0, copies.get(), "runtime preflight must not open a COPY API");
        writer.prepareWrite(schema, "typed", fields);
        Object[] row = {(short) 32767, Integer.MAX_VALUE, Long.MAX_VALUE, decimal, 1.5f, -0.0d, true,
                "中", "中文'\\\t", "literal;\nCOPY text", new byte[]{0, 1, -1}, moment.toLocalDate(), moment.toLocalTime(), moment};
        var negative = new Object[fields.size()]; negative[2] = Long.MIN_VALUE; negative[4] = -0.0f; negative[5] = -0.0d;
        var result = writer.writeWithReceipt(fields, List.of(row, negative, new Object[fields.size()]));
        assertEquals(3, result.acknowledgedRows());
        if (observer.getMetaData().getDatabaseMajorVersion() == 18) assertNull(result.receipt());
        else assertNotNull(result.receipt());
        try (var query = observer.createStatement(); var stored = query.executeQuery("SELECT * FROM " + schema + ".typed ORDER BY id DESC NULLS LAST")) {
            assertTrue(stored.next());
            assertEquals((short) 32767, stored.getShort(1)); assertEquals(Integer.MAX_VALUE, stored.getInt(2));
            assertEquals(Long.MAX_VALUE, stored.getLong(3)); assertEquals(decimal, stored.getBigDecimal(4));
            assertEquals(1.5f, stored.getFloat(5));
            assertEquals(Double.doubleToRawLongBits(-0.0d), Double.doubleToRawLongBits(stored.getDouble(6)));
            assertTrue(stored.getBoolean(7)); assertEquals("中", stored.getString(8));
            assertEquals(row[8], stored.getString(9)); assertEquals(row[9], stored.getString(10));
            assertArrayEquals((byte[]) row[10], stored.getBytes(11));
            assertEquals((LocalDate) row[11], stored.getDate(12).toLocalDate());
            assertEquals((LocalTime) row[12], stored.getObject(13, LocalTime.class));
            assertEquals(moment, stored.getTimestamp(14).toLocalDateTime());
            assertTrue(stored.next());
            assertEquals(Long.MIN_VALUE, stored.getLong(3));
            assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits(stored.getFloat(5)));
            assertEquals(Double.doubleToRawLongBits(-0.0d), Double.doubleToRawLongBits(stored.getDouble(6)));
            assertTrue(stored.next());
            for (int column = 1; column <= fields.size(); column++) assertNull(stored.getObject(column));
            assertFalse(stored.next());
        }
        assertEquals(1, copies.get());
        try (var connection = pool.getConnection()) { assertTrue(connection.getAutoCommit()); }
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "smallint;-128;127;-129", "smallint;0;255;256", "integer;0;65535;65536",
            "integer;-8388608;8388607;8388608", "integer;0;16777215;16777216",
            "bigint;0;4294967295;4294967296", "numeric(20,0);0;18446744073709551615;18446744073709551616",
            "numeric(65,30);0;12345678901234567890.123456789012345678901234567890;-1"})
    void generatedMappedDomainsCopyExactBoundsAndRollbackTheWholeInvalidBatch(
            String type, String minimum, String maximum, String outside) throws Exception {
        String predicate = type.equals("numeric(65,30)") ? "id >= 0" : "id BETWEEN " + minimum + " AND " + maximum;
        String ddl = "CREATE TABLE " + schema + ".checked(id " + type + " CHECK(" + predicate + "))";
        cn.iocoder.yudao.framework.dbswitch.core.service.PostgresCopyTargetDefinition.validate(
                schema, "checked", List.of(ddl), List.of("id"));
        sql(ddl);
        var writer = new PostgresCopyWriterImpl(facade());
        writer.prepareWrite(schema, "checked", List.of("id"));
        var valid = writer.writeWithReceipt(List.of("id"), List.of(
                new Object[]{numericValue(type, minimum)}, new Object[]{numericValue(type, maximum)}, new Object[]{null}));
        assertEquals(3, valid.acknowledgedRows());
        try (var query = observer.createStatement(); var rows = query.executeQuery(
                "SELECT id FROM " + schema + ".checked ORDER BY id NULLS LAST")) {
            assertTrue(rows.next()); assertEquals(0, new BigDecimal(minimum).compareTo(rows.getBigDecimal(1)));
            assertTrue(rows.next()); assertEquals(0, new BigDecimal(maximum).compareTo(rows.getBigDecimal(1)));
            assertTrue(rows.next()); assertNull(rows.getObject(1)); assertFalse(rows.next());
        }
        var unconfirmed = assertThrows(cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException.class,
                () -> writer.write(List.of("id"), List.of(
                new Object[]{numericValue(type, minimum)}, new Object[]{numericValue(type, outside)})));
        assertNull(unconfirmed.getCause()); // Observer proof below does not weaken the public unknown-result contract.
        try (var query = observer.createStatement(); var rows = query.executeQuery("SELECT count(*) FROM " + schema + ".checked")) {
            assertTrue(rows.next()); assertEquals(3, rows.getInt(1), "the preceding valid row in the failed batch must roll back");
        }
        try (var connection = pool.getConnection()) { assertTrue(connection.getAutoCommit()); }
    }

    private static Object numericValue(String type, String value) {
        var decimal = new BigDecimal(value);
        return switch (type) {
            case "smallint" -> decimal.shortValueExact();
            case "integer" -> decimal.intValueExact();
            case "bigint" -> decimal.longValueExact();
            default -> decimal;
        };
    }

    @ParameterizedTest
    @ValueSource(strings = {"product", "driver-name", "driver-version", "jdbc-version", "server-version", "encoding", "datetimes", "private-error"})
    void unknownOrInconsistentRuntimeCannotOpenCopyOrChangeTransaction(String fault) throws Exception {
        disguise = fault;
        var writer = new PostgresCopyWriterImpl(facade());
        var failure = assertThrows(SQLFeatureNotSupportedException.class, writer::preflightRuntimeWrite);
        assertEquals("0A000", failure.getSQLState()); assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("private"));
        assertThrows(SQLFeatureNotSupportedException.class, () -> writer.prepareWrite(schema, "rows", List.of("id")));
        assertThrows(SQLFeatureNotSupportedException.class, () -> writer.write(List.of("id"), List.<Object[]>of(new Object[]{1})));
        assertEquals(0, copies.get()); assertEquals(0, transactionChanges.get()); assertEquals(0, count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"uuid[]", "integer[][]", "timestamp with time zone", "money"})
    void nativeTypesOutsideTheBinaryProfileAreRejectedBeforeAnyCopy(String type) throws Exception {
        sql("CREATE TABLE " + schema + ".unsupported(value " + type + ")");
        var writer = new PostgresCopyWriterImpl(facade());
        writer.preflightRuntimeWrite();
        assertThrows(SQLFeatureNotSupportedException.class, () -> writer.prepareWrite(schema, "unsupported", List.of("value")));
        assertEquals(0, copies.get()); assertEquals(0, transactionChanges.get());
    }

    @Test void failedPreparationCannotKeepThePriorTablesAdmission() throws Exception {
        sql("CREATE TABLE " + schema + ".unsupported(value uuid[])");
        var writer = new PostgresCopyWriterImpl(facade());
        writer.prepareWrite(schema, "rows", List.of("id"));
        assertThrows(SQLFeatureNotSupportedException.class, () -> writer.prepareWrite(schema, "unsupported", List.of("value")));
        assertThrows(SQLFeatureNotSupportedException.class, () -> writer.write(List.of("id"), List.<Object[]>of(new Object[]{1})));
        assertEquals(0, copies.get()); assertEquals(0, count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"column-type", "table-replaced", "route", "server-version", "encoding", "driver-version"})
    void eachActualBatchRechecksLayoutObjectAndRuntimeBeforeOpeningCopy(String change) throws Exception {
        var writer = new PostgresCopyWriterImpl(facade());
        writer.prepareWrite(schema, "rows", List.of("id"));
        if (change.equals("column-type")) sql("ALTER TABLE " + schema + ".rows ALTER COLUMN id TYPE bigint");
        else if (change.equals("table-replaced")) {
            sql("DROP TABLE " + schema + ".rows"); sql("CREATE TABLE " + schema + ".rows(id integer,note text)");
        } else disguise = change;
        var failure = assertThrows(IllegalStateException.class,
                () -> writer.write(List.of("id"), List.<Object[]>of(new Object[]{1})));
        assertEquals(IllegalStateException.class, failure.getClass(), "no data protocol was opened");
        assertNull(failure.getCause()); assertEquals(0, copies.get()); assertEquals(0, count());
    }

    @Test void aCallerTransactionIsNotAdoptedByRuntimePreflight() throws Exception {
        disguise = "caller-transaction";
        var writer = new PostgresCopyWriterImpl(facade());
        assertThrows(SQLFeatureNotSupportedException.class, writer::preflightRuntimeWrite);
        assertEquals(0, copies.get()); assertEquals(0, transactionChanges.get()); assertEquals(0, count());
    }

    @Test void aRealSessionCannotShadowBuiltinPlanTypesWithADomain() throws Exception {
        sql("CREATE DOMAIN " + schema + ".text AS integer");
        pool.setConnectionInitSql("SET search_path=" + schema + ",pg_catalog");
        var writer = new PostgresCopyWriterImpl(facade());
        // The builtin spelling is a valid pure shape, but this actual session resolves it from
        // an unproved user namespace and must reject before any target creation/clearing.
        writer.preflightTargetDefinition(schema, "copied", List.of("CREATE TABLE " + schema + ".copied(id text)"), List.of("id"));
        assertThrows(SQLFeatureNotSupportedException.class, writer::preflightRuntimeWrite);
        assertEquals(0, copies.get()); assertEquals(0, transactionChanges.get()); assertFalse(targetExists());
    }

    @ParameterizedTest
    @ValueSource(strings = {"drop", "create", "truncate"})
    void publicMigrationCannotChangeTargetsWithAnUncertifiedArtifact(String mode) throws Exception {
        sql("CREATE TABLE " + schema + ".source(id integer PRIMARY KEY,note text)");
        sql("INSERT INTO " + schema + ".source VALUES(1,'new')");
        if (!mode.equals("create")) {
            sql("CREATE TABLE " + schema + ".copied(id integer PRIMARY KEY,note text)");
            sql("INSERT INTO " + schema + ".copied VALUES(9,'keep')");
        }
        Path altered = alteredDriverArtifact();
        var spec = new VerifiedDriverTestHandle.Spec(altered.toString(), VerifiedDriverTestHandle.sha256(altered), "org.postgresql.Driver");
        // The deployment verifies this alternate artifact's own digest. That establishes bundle
        // integrity only; it does not grant the original JAR's operation-specific COPY evidence.
        try (var drivers = new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.POSTGRESQL, spec));
             var runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), DbSwitchOptions.defaults(), drivers)) {
            var request = new MigrationRequest();
            var source = new SourceDataSourceProperties(); source.setSourceProductType(ProductTypeEnum.POSTGRESQL);
            source.setUrl(System.getProperty("datahub.test.postgres.url"));
            source.setDriverClassName("org.postgresql.Driver");
            source.setUsername(System.getProperty("datahub.test.postgres.user", "dataforge_test")); source.setPassword("");
            source.setSourceSchema(schema); source.setSourceIncludes("source"); source.setPoolSize(1);
            source.setRegexTableMapper(List.of(new PatternMapper("^source$", "copied")));
            request.getSource().add(source);
            var target = request.getTarget(); target.setTargetProductType(ProductTypeEnum.POSTGRESQL);
            target.setDriverClassName("org.postgresql.Driver");
            target.setUrl(source.getUrl()); target.setUsername(source.getUsername()); target.setPassword("");
            target.setTargetSchema(schema); target.setPoolSize(1); target.setWriterEngineInsert(false);
            target.setTargetDrop(mode.equals("drop")); target.setTargetTruncateData(mode.equals("truncate"));
            try (var connection = runtime.openConnection(ProductTypeEnum.POSTGRESQL, null, source.getUrl(), source.getUsername(), "");
                 var query = connection.createStatement(); var rows = query.executeQuery("SELECT count(*) FROM " + schema + ".source")) {
                assertEquals("42.7.13", connection.getMetaData().getDriverVersion());
                assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
            }
            var service = new MigrationService(request, ignored -> { }, null, runtime);
            try {
                var result = service.run(); assertFalse(result.getIfAllSuccess().get());
            } finally { service.physicalExit().toCompletableFuture().get(20, TimeUnit.SECONDS); }
        }
        try (var query = observer.createStatement()) {
            if (mode.equals("create")) {
                try (var rows = query.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='"
                        + schema + "' AND table_name='copied'")) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
            } else {
                try (var rows = query.executeQuery("SELECT id,note FROM " + schema + ".copied")) {
                    assertTrue(rows.next()); assertEquals(9, rows.getInt(1)); assertEquals("keep", rows.getString(2)); assertFalse(rows.next());
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"drop", "create", "truncate"})
    void publicMigrationRejectsUnprovedNativeColumnsBeforeTargetMutation(String mode) throws Exception {
        sql("CREATE TABLE " + schema + ".source(id uuid[] PRIMARY KEY,note text)");
        sql("INSERT INTO " + schema + ".source VALUES(ARRAY['00000000-0000-0000-0000-000000000001'::uuid],'new')");
        if (!mode.equals("create")) {
            sql("CREATE TABLE " + schema + ".copied(id uuid[] PRIMARY KEY,note text)");
            sql("INSERT INTO " + schema + ".copied VALUES(ARRAY['00000000-0000-0000-0000-000000000009'::uuid],'keep')");
        }
        assertFalse(runOriginalArtifactMigration(mode).getIfAllSuccess().get());
        if (mode.equals("create")) assertFalse(targetExists());
        else try (var query = observer.createStatement(); var rows = query.executeQuery("SELECT id,note FROM " + schema + ".copied")) {
            assertTrue(rows.next()); assertEquals("{00000000-0000-0000-0000-000000000009}", rows.getString(1));
            assertEquals("keep", rows.getString(2)); assertFalse(rows.next());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"drop", "create", "truncate"})
    void publicMigrationStillCopiesSupportedColumnsThroughRealPreparation(String mode) throws Exception {
        sql("CREATE TABLE " + schema + ".source(id integer PRIMARY KEY,note text)");
        sql("INSERT INTO " + schema + ".source VALUES(1,'new')");
        if (!mode.equals("create")) {
            sql("CREATE TABLE " + schema + ".copied(id integer PRIMARY KEY,note text)");
            sql("INSERT INTO " + schema + ".copied VALUES(9,'old')");
        }
        assertTrue(runOriginalArtifactMigration(mode).getIfAllSuccess().get());
        try (var query = observer.createStatement(); var rows = query.executeQuery("SELECT id,note FROM " + schema + ".copied")) {
            assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); assertEquals("new", rows.getString(2)); assertFalse(rows.next());
        }
    }

    private cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult runOriginalArtifactMigration(String mode) throws Exception {
        Path original = Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var spec = new VerifiedDriverTestHandle.Spec(original.toString(), VerifiedDriverTestHandle.sha256(original), "org.postgresql.Driver");
        try (var drivers = new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.POSTGRESQL, spec));
             var runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), DbSwitchOptions.defaults(), drivers)) {
            var request = new MigrationRequest();
            var source = new SourceDataSourceProperties(); source.setSourceProductType(ProductTypeEnum.POSTGRESQL);
            source.setUrl(System.getProperty("datahub.test.postgres.url")); source.setDriverClassName("org.postgresql.Driver");
            source.setUsername(System.getProperty("datahub.test.postgres.user", "dataforge_test")); source.setPassword("");
            source.setSourceSchema(schema); source.setSourceIncludes("source"); source.setPoolSize(1);
            source.setRegexTableMapper(List.of(new PatternMapper("^source$", "copied"))); request.getSource().add(source);
            var target = request.getTarget(); target.setTargetProductType(ProductTypeEnum.POSTGRESQL);
            target.setUrl(source.getUrl()); target.setDriverClassName("org.postgresql.Driver");
            target.setUsername(source.getUsername()); target.setPassword(""); target.setTargetSchema(schema); target.setPoolSize(1);
            target.setWriterEngineInsert(false); target.setTargetDrop(mode.equals("drop")); target.setTargetTruncateData(mode.equals("truncate"));
            var service = new MigrationService(request, ignored -> { }, null, runtime);
            try { return service.run(); }
            finally { service.physicalExit().toCompletableFuture().get(20, TimeUnit.SECONDS); }
        }
    }

    private boolean targetExists() throws Exception {
        try (var query = observer.prepareStatement("SELECT pg_catalog.to_regclass(?)")) {
            query.setString(1, schema + ".copied");
            try (var rows = query.executeQuery()) { assertTrue(rows.next()); return rows.getString(1) != null; }
        }
    }

    private Path alteredDriverArtifact() throws Exception {
        Path original = Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path altered = directory.resolve("alternate-postgresql.jar");
        try (var input = new JarFile(original.toFile()); var output = new JarOutputStream(Files.newOutputStream(altered))) {
            var entries = input.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement(); output.putNextEntry(new JarEntry(entry.getName()));
                if (!entry.isDirectory()) try (var data = input.getInputStream(entry)) { data.transferTo(output); }
                output.closeEntry();
            }
            output.putNextEntry(new JarEntry("META-INF/dbswitch-uncertified-profile.txt"));
            output.write("Fixture: different artifact, same driver version".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        assertNotEquals(VerifiedDriverTestHandle.sha256(original), VerifiedDriverTestHandle.sha256(altered));
        return altered;
    }

    private DataSource facade() {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> method.getName().equals("getConnection") ? wrap(pool.getConnection()) : invoke(pool, method, args));
    }

    private Connection wrap(Connection actual) throws Exception {
        PGConnection pg = actual.unwrap(PGConnection.class);
        PGConnection nativeFacade = (PGConnection) Proxy.newProxyInstance(PGConnection.class.getClassLoader(), new Class<?>[]{PGConnection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getCopyAPI")) copies.incrementAndGet();
                    return invoke(pg, method, args);
                });
        return (Connection) Proxy.newProxyInstance(PGConnection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("unwrap") && args[0] == PGConnection.class) return nativeFacade;
                    if (name.equals("isWrapperFor") && args[0] == PGConnection.class) return true;
                    if (name.equals("getAutoCommit") && disguise.equals("caller-transaction")) return false;
                    if (List.of("commit", "rollback", "setAutoCommit").contains(name)) transactionChanges.incrementAndGet();
                    if (name.equals("getMetaData")) {
                        if (disguise.equals("private-error")) throw new java.sql.SQLException("fixture-private-connection-detail");
                        var metadata = actual.getMetaData();
                        return Proxy.newProxyInstance(java.sql.DatabaseMetaData.class.getClassLoader(), new Class<?>[]{java.sql.DatabaseMetaData.class},
                                (p, m, a) -> switch (m.getName()) {
                                    case "getDatabaseProductName" -> disguise.equals("product") ? "Greenplum" : metadata.getDatabaseProductName();
                                    case "getDriverName" -> disguise.equals("driver-name") ? "Unknown Driver" : metadata.getDriverName();
                                    case "getDriverVersion" -> disguise.equals("driver-version") ? "42.7.99" : metadata.getDriverVersion();
                                    case "getDatabaseMinorVersion" -> disguise.equals("jdbc-version") ? 99 : metadata.getDatabaseMinorVersion();
                                    default -> invoke(metadata, m, a);
                                });
                    }
                    if (name.equals("createStatement")) return statement(actual.createStatement());
                    return invoke(actual, method, args);
                });
    }

    private Statement statement(Statement actual) {
        return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class}, (proxy, method, args) -> {
            Object result = invoke(actual, method, args);
            if (method.getName().equals("executeQuery") && ((String) args[0]).contains("current_setting('server_version_num')")) {
                var rows = (ResultSet) result;
                return Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class}, (p, m, a) -> {
                    if (m.getName().equals("getString") && a[0] instanceof Integer column) {
                        if (column == 1 && disguise.equals("server-version")) return "PostgreSQL 17.5 fixture";
                        if (column == 3 && disguise.equals("encoding")) return "LATIN1";
                        if (column == 4 && disguise.equals("datetimes")) return "off";
                        if (column == 5 && disguise.equals("route")) return "changed_route";
                    }
                    return invoke(rows, m, a);
                });
            }
            return result;
        });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private void sql(String statement) throws Exception { try (var query = observer.createStatement()) { query.execute(statement); } }
    private long count() throws Exception {
        try (var query = observer.createStatement(); var rows = query.executeQuery("SELECT count(*) FROM " + schema + ".rows")) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }
}

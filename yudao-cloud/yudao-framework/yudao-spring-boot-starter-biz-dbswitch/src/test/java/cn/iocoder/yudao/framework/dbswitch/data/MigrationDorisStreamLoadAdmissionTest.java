package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.IDatabaseWriter;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductDescriptor;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import cn.iocoder.yudao.framework.dbswitch.spi.WriteMode;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.DORIS;
import static org.junit.jupiter.api.Assertions.*;

/** Public native plans must reject an unsupported selected transport before replacing the owned target. */
@Timeout(180)
@EnabledIfSystemProperty(named = "datahub.test.doris.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/")
class MigrationDorisStreamLoadAdmissionTest {
    private final AtomicInteger nativeChecks = new AtomicInteger();
    private final AtomicInteger preparations = new AtomicInteger();
    private HikariDataSource pool;
    private Connection control;
    private DbSwitchRuntime runtime;
    private String schema;

    @BeforeEach void setup() throws Exception {
        pool = new HikariDataSource(); pool.setJdbcUrl(url());
        pool.setUsername(user()); pool.setPassword(password());
        pool.setMinimumIdle(0); pool.setMaximumPoolSize(3); pool.setConnectionTimeout(10_000);
        control = pool.getConnection();
        try (var statement = control.createStatement(); var rows = statement.executeQuery("SHOW FRONTENDS")) {
            assertTrue(rows.next()); assertEquals("doris-4.0.8-rc02-bc8ea1bac6d", rows.getString("Version"));
        }
        schema = "doris_admission_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE DATABASE " + schema);
        runtime = new DbSwitchRuntime(observedRegistry(), DbSwitchOptions.defaults(), null);
    }

    @AfterEach void cleanup() throws Exception {
        try { if (runtime != null) runtime.close(); }
        finally {
            try { if (control != null) try (var owned = control) { if (schema != null) sql("DROP DATABASE " + schema + " FORCE"); } }
            finally { if (pool != null) pool.close(); }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"AGGREGATE", "JSON"})
    void unsupportedNativeTransportPlanPreservesSentinelBeforeExplicitDrop(String shape) throws Exception {
        String sourceShape = shape.equals("AGGREGATE")
                ? "(id BIGINT NOT NULL,payload BIGINT SUM) AGGREGATE KEY(id)"
                : "(id BIGINT NOT NULL,payload JSON) DUPLICATE KEY(id)";
        String distribution = " DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')";
        sql("CREATE TABLE " + table("source_rows") + sourceShape + distribution);
        sql("CREATE TABLE " + table("target_rows") + "(id BIGINT NOT NULL,payload INT) DUPLICATE KEY(id)" + distribution);
        sql("INSERT INTO " + table("target_rows") + " VALUES(77,99)");
        var request = request();
        request.getTarget().setTargetDrop(true);
        request.getTarget().setExclusiveTargetDdlOwnership(true);
        var result = new MigrationService(request, ignored -> { }, null, runtime).run();
        assertFalse(result.getIfAllSuccess().get());
        assertTrue(nativeChecks.get() > 0, "The native writer hook must be reached before the plan executes DROP");
        assertEquals(0, preparations.get(), "Refusal must precede preparing a writer or submitting any HTTP request");
        try (var statement = control.createStatement(); var rows = statement.executeQuery("SELECT id,payload FROM " + table("target_rows"))) {
            assertTrue(rows.next()); assertEquals(77, rows.getLong(1)); assertEquals(99, rows.getInt(2)); assertFalse(rows.next());
        }
        try (var statement = control.createStatement(); var rows = statement.executeQuery("SHOW CREATE TABLE " + table("target_rows"))) {
            assertTrue(rows.next()); assertTrue(rows.getString(2).contains("`payload` int"), rows.getString(2));
        }
    }

    private DbSwitchProductRegistry observedRegistry() {
        var discovered = DbSwitchProductRegistry.discover(); var original = discovered.product(DORIS);
        var builder = DbSwitchProductDescriptor.builder(DORIS).metadata(original::metadata).reader(original::reader)
                .writer(WriteMode.INSERT, source -> observed(original.writer(source, WriteMode.INSERT)))
                .writer(WriteMode.COPY, source -> observed(original.writer(source, WriteMode.COPY)))
                .synchronizer(original::synchronizer).upsert(original::upsertWriter)
                .cursorTargets(original.cursorTargets().toArray(ProductTypeEnum[]::new));
        original.typeMapper().ifPresent(builder::typeMapper);
        original.capabilities().forEach((operation, capability) -> {
            if (capability.status() == OperationCapability.Status.UNSUPPORTED) builder.unsupported(operation, capability.reason());
            else { assertEquals(OperationCapability.Status.DECLARED, capability.status()); builder.declare(operation, capability.reason()); }
        });
        var observed = builder.build(); assertEquals(original.capabilities(), observed.capabilities());
        return new DbSwitchProductRegistry(discovered.products().stream().map(product -> product.product() == DORIS ? observed : product).toList());
    }

    private IDatabaseWriter observed(IDatabaseWriter writer) {
        return (IDatabaseWriter) Proxy.newProxyInstance(IDatabaseWriter.class.getClassLoader(), new Class<?>[]{IDatabaseWriter.class}, (proxy, method, args) -> {
            if (method.getName().equals("preflightNativeTarget")) nativeChecks.incrementAndGet();
            if (method.getName().equals("prepareWrite")) preparations.incrementAndGet();
            try { return method.invoke(writer, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
    }

    private MigrationRequest request() {
        var request = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(DORIS); source.setUrl(url()); source.setUsername(user()); source.setPassword(password());
        source.setSourceSchema(schema); source.setIncludeOrExclude(1); source.setSourceTableNames(List.of("source_rows"));
        source.setPoolSize(1); source.setFetchSize(2);
        source.setRegexTableMapper(List.of(new PatternMapper("^source_rows$", "target_rows"))); request.getSource().add(source);
        var target = request.getTarget(); target.setTargetProductType(DORIS); target.setUrl(url()); target.setUsername(user()); target.setPassword(password());
        target.setTargetSchema(schema); target.setPoolSize(1); target.setWriterEngineInsert(true); return request;
    }
    private void sql(String command) throws Exception { try (var statement = control.createStatement()) { statement.setQueryTimeout(30); statement.execute(command); } }
    private String table(String name) { return schema + "." + name; }
    private static String url() { return System.getProperty("datahub.test.doris.url"); }
    private static String user() { return System.getProperty("datahub.test.doris.user", "root"); }
    private static String password() { return System.getProperty("datahub.test.doris.password", ""); }
}

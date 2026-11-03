package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.entity.TargetDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import cn.iocoder.yudao.framework.dbswitch.driver.VerifiedDriverTestHandle;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.hive.HiveWriterImpl;
import cn.iocoder.yudao.framework.dbswitch.dbsynch.hive.HiveDatabaseSyncImpl;
import cn.iocoder.yudao.framework.dbswitch.product.hive.HiveJdbcWriteSupport;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.AbstractDataSource;
import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(300)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "datahub.test.hive.url", matches = "jdbc:hive2://127[.]0[.]0[.]1:[0-9]+/default")
@EnabledIfSystemProperty(named = "datahub.test.hive.driver", matches = ".+")
class HiveMigrationHiveTest {
    private static final List<String> FIELDS = List.of("id", "amount", "note");
    private static final BigDecimal AMOUNT = new BigDecimal("12345678901234567890123456789.123456789");
    private static final BatchLimits LIMITS = new BatchLimits(10, 8192, 2048, 6, 1000);
    private static final DbSwitchOptions OPTIONS = new DbSwitchOptions(8192, false, false, 90, 0, 0, 250, 0);
    private final String schema = "native_hive_" + UUID.randomUUID().toString().replace("-", "");
    private ConfiguredJdbcDrivers drivers;
    private DbSwitchRuntime runtime;
    private com.zaxxer.hikari.HikariDataSource pool;
    private String source;
    private String target;

    @BeforeAll void start() throws Exception {
        Path artifact = Path.of(System.getProperty("datahub.test.hive.driver")).toRealPath();
        var spec = new DbSwitchProperties.Driver(); spec.setPath(artifact.toString()); spec.setSha256(VerifiedDriverTestHandle.sha256(artifact));
        drivers = new ConfiguredJdbcDrivers(Map.of(ProductTypeEnum.HIVE, spec));
        runtime = new DbSwitchRuntime(DbSwitchProductRegistry.discover(), OPTIONS, drivers);
        var properties = new TargetDataSourceProperties(); properties.setTargetProductType(ProductTypeEnum.HIVE);
        properties.setUrl(url()); properties.setUsername("hive"); properties.setPassword(""); properties.setPoolSize(4);
        pool = DataSourceUtils.createTargetDataSource(properties, null, null, drivers.find(ProductTypeEnum.HIVE).orElseThrow());
        sql("CREATE DATABASE " + HiveJdbcWriteSupport.quote(schema));
    }
    @AfterAll void stop() throws Exception {
        try { if (pool != null) sql("DROP DATABASE IF EXISTS " + HiveJdbcWriteSupport.quote(schema) + " CASCADE"); }
        finally {
            try { if (pool != null) pool.close(); }
            finally { try { if (runtime != null) runtime.close(); } finally { if (drivers != null) drivers.close(); } }
        }
    }
    @BeforeEach void tableNames() {
        source = "src_" + UUID.randomUUID().toString().replace("-", "");
        target = "dst_" + UUID.randomUUID().toString().replace("-", "");
    }
    @AfterEach void dropTables() throws Exception {
        sql("DROP TABLE IF EXISTS " + name(source)); sql("DROP TABLE IF EXISTS " + name(target));
    }
    @Test void nativeBoundedMultiValuesPreservesBigDecimalNullAndText() throws Exception {
        create(source, true);
        var writer = writer(source, pool);
        assertEquals(3, writer.write(FIELDS, List.of(new Object[]{-1L, AMOUNT, "中文'\\\n"},
                new Object[]{2L, null, ""}, new Object[]{3L, new BigDecimal("3.000000001"), null})));
        assertRows(source, List.of("-1|" + AMOUNT.toPlainString() + "|中文'\\\n", "2|NULL|", "3|3.000000001|NULL"));
    }
    @Test void nativeAcidUpdateDeleteInsertAreVisibleAndNotNoOps() throws Exception {
        create(source, true); seed(source);
        var sync = sync(source, pool);
        assertEquals(1, sync.executeUpdate(List.<Object[]>of(new Object[]{1L, AMOUNT, "updated"})));
        assertEquals(1, sync.executeDelete(List.<Object[]>of(new Object[]{2L, null, "second"})));
        assertEquals(1, sync.executeInsert(List.<Object[]>of(new Object[]{3L, null, "new"})));
        assertRows(source, List.of("1|" + AMOUNT.toPlainString() + "|updated", "3|NULL|new"));
    }
    @Test void nonAcidAndDuplicateKeysCannotSilentlySkipMutations() throws Exception {
        create(source, false); seed(source);
        assertThrows(IllegalStateException.class, () -> sync(source, pool));
        create(target, true);
        writer(target, pool).write(FIELDS, List.of(new Object[]{1L, null, "a"}, new Object[]{1L, null, "b"}));
        assertThrows(IllegalStateException.class, () -> sync(target, pool));
        assertRows(source, List.of("1|NULL|first", "2|NULL|second"));
        assertRows(target, List.of("1|NULL|a", "1|NULL|b"));
    }
    @Test void invalidLaterDecimalRejectsBeforeAnyPrefixInsertOrUpdate() throws Exception {
        create(source, true); var writer = writer(source, pool);
        var rows = List.of(new Object[]{1L, AMOUNT, "must-not-write"}, new Object[]{2L, new BigDecimal("1.1234567891"), "bad"});
        assertThrows(IllegalArgumentException.class, () -> writer.write(FIELDS, rows)); assertRows(source, List.of());
        seed(source); var sync = sync(source, pool);
        assertThrows(IllegalArgumentException.class, () -> sync.executeUpdate(rows));
        assertRows(source, List.of("1|NULL|first", "2|NULL|second"));
    }
    @Test void publicMigrationAutoCreatesOrcAndMovesEveryRowWithoutNumericNarrowing() throws Exception {
        create(source, true);
        writer(source, pool).write(FIELDS, List.of(new Object[]{-1L, AMOUNT, "中文"}, new Object[]{2L, null, ""}, new Object[]{3L, null, null}));
        var result = new MigrationService(request(false), ignored -> { }, null, runtime).run();
        assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString());
        assertRows(target, List.of("-1|" + AMOUNT.toPlainString() + "|中文", "2|NULL|", "3|NULL|NULL"));
        var columns = runtime.metadata(pool, ProductTypeEnum.HIVE).queryTableColumnMeta(schema, target);
        assertEquals(38, columns.get(1).getPrecisionSize()); assertEquals(9, columns.get(1).getScaleSize());
    }
    @Test void publicDifferenceUsesExplicitMatchKeysAndSupportsIdempotentNoChangeReplay() throws Exception {
        create(source, true); create(target, true);
        writer(source, pool).write(FIELDS, List.of(new Object[]{2L, AMOUNT, "updated"}, new Object[]{3L, null, "new"})); seed(target);
        assertTrue(runtime.metadata(pool, ProductTypeEnum.HIVE).queryTablePrimaryKeys(schema, target).isEmpty(),
                "Observed uniqueness must not masquerade as an enforced Hive primary constraint");
        var result = new MigrationService(request(true), ignored -> { }, null, runtime).run();
        assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString());
        assertRows(target, List.of("2|" + AMOUNT.toPlainString() + "|updated", "3|NULL|new"));
        var replay = new MigrationService(request(true), ignored -> { }, null, runtime).run();
        assertTrue(replay.getIfAllSuccess().get()); assertRows(target, List.of("2|" + AMOUNT.toPlainString() + "|updated", "3|NULL|new"));
    }
    @Test void realCommittedMutationWithLostResponseStaysUnknown() throws Exception {
        create(source, true); seed(source); var armed = new AtomicBoolean();
        var sync = sync(source, lostMutationResponse(armed)); armed.set(true);
        var failure = assertThrows(UnconfirmedWriteException.class,
                () -> sync.executeUpdate(List.<Object[]>of(new Object[]{1L, AMOUNT, "committed"})));
        assertNull(failure.getCause()); assertFalse(armed.get());
        assertRows(source, List.of("1|" + AMOUNT.toPlainString() + "|committed", "2|NULL|second"));
    }
    private void create(String table, boolean acid) throws Exception {
        sql("CREATE TABLE " + name(table) + " (id BIGINT,amount DECIMAL(38,9),note STRING) STORED AS ORC TBLPROPERTIES ('transactional'='"
                + acid + "')");
    }
    private void seed(String table) { writer(table, pool).write(FIELDS, List.of(new Object[]{1L, null, "first"}, new Object[]{2L, null, "second"})); }
    private HiveWriterImpl writer(String table, DataSource source) {
        var writer = new HiveWriterImpl(source); writer.configure(OPTIONS); writer.configureBatchLimits(LIMITS);
        writer.prepareWrite(schema, table, FIELDS); return writer;
    }
    private HiveDatabaseSyncImpl sync(String table, DataSource source) {
        var sync = new HiveDatabaseSyncImpl(source); sync.configure(OPTIONS); sync.configureBatchLimits(LIMITS);
        sync.prepare(schema, table, FIELDS, List.of("id")); return sync;
    }
    private MigrationRequest request(boolean difference) {
        var request = new MigrationRequest(); var input = new SourceDataSourceProperties();
        input.setSourceProductType(ProductTypeEnum.HIVE); input.setUrl(url()); input.setUsername("hive"); input.setPassword("");
        input.setSourceSchema(schema); input.setSourceIncludes(source); input.setPoolSize(2); input.setFetchSize(2);
        input.setSourcePrimaryKeys(List.of("id")); input.setRegexTableMapper(List.of(new PatternMapper("^" + source + "$", target)));
        request.getSource().add(input); var output = request.getTarget(); output.setTargetProductType(ProductTypeEnum.HIVE);
        output.setUrl(url()); output.setUsername("hive"); output.setPassword(""); output.setTargetSchema(schema); output.setTargetTable(target);
        output.setPoolSize(4); output.setWriterEngineInsert(true); output.setChangeDataSync(difference); output.setBatchLimits(LIMITS);
        return request;
    }
    private static String url() { return System.getProperty("datahub.test.hive.url"); }
    private String name(String table) { return HiveJdbcWriteSupport.table(schema, table); }
    private void sql(String sql) throws Exception {
        try (var connection = pool.getConnection()) {
            HiveJdbcWriteSupport.session(connection, 90);
            try (var statement = connection.createStatement()) { statement.setQueryTimeout(90); statement.execute(sql); }
        }
    }
    private void assertRows(String table, List<String> expected) throws Exception {
        var actual = new ArrayList<String>();
        try (var connection = pool.getConnection()) {
            HiveJdbcWriteSupport.session(connection, 90);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(90);
                try (var rows = statement.executeQuery("SELECT id,amount,note FROM " + name(table) + " ORDER BY id,note")) {
                    while (rows.next()) actual.add(rows.getLong(1) + "|" + (rows.getBigDecimal(2) == null ? "NULL" : rows.getBigDecimal(2).toPlainString())
                            + "|" + (rows.getString(3) == null ? "NULL" : rows.getString(3)));
                }
            }
        }
        assertEquals(expected, actual);
    }
    private DataSource lostMutationResponse(AtomicBoolean armed) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                var real = pool.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (List.of("setAutoCommit", "commit", "rollback").contains(method.getName())) throw new AssertionError("Unexpected Hive JDBC transaction control");
                    try {
                        Object result = method.invoke(real, args);
                        if (method.getName().equals("prepareStatement") && ((String) args[0]).startsWith("UPDATE ")) {
                            var statement = (PreparedStatement) result;
                            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p, m, a) -> {
                                try {
                                    Object value = m.invoke(statement, a);
                                    if (m.getName().equals("executeUpdate") && armed.compareAndSet(true, false)) throw new SQLException("lost fixture response");
                                    return value;
                                } catch (InvocationTargetException failure) { throw failure.getCause(); }
                            });
                        }
                        return result;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
            @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        };
    }
}

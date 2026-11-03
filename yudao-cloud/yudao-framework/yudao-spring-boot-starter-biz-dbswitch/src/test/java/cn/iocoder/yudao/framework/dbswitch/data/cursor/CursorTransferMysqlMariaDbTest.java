package cn.iocoder.yudao.framework.dbswitch.data.cursor;

import cn.iocoder.yudao.framework.dbswitch.common.config.BatchLimits;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchTableResult;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.CommitAcknowledgement;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
@EnabledIfSystemProperty(named="datahub.test.mysql.url", matches="jdbc:mysql://127[.]0[.]0[.]1:.*")
@EnabledIfSystemProperty(named="datahub.test.mariadb.url", matches="jdbc:mariadb://127[.]0[.]0[.]1:.*")
@EnabledIfSystemProperty(named="datahub.test.postgres.url", matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
class CursorTransferMysqlMariaDbTest {
    private final Map<ProductTypeEnum, DriverManagerDataSource> sources = new EnumMap<>(ProductTypeEnum.class);
    private final Map<ProductTypeEnum, Connection> controls = new EnumMap<>(ProductTypeEnum.class);
    private final String schema = "cursor_multi_" + UUID.randomUUID().toString().replace("-", "");
    private final BatchLimits limits = new BatchLimits(2, 65536, 16384, 65535, 1000);
    private final List<String> fields = List.of("stamp", "id", "value");
    private final AtomicReference<CursorCheckpoint> saved = new AtomicReference<>();
    private DbSwitchRuntime runtime;

    @BeforeEach void setup() throws Exception {
        runtime = DbSwitchRuntime.standalone();
        for (var product : List.of(ProductTypeEnum.MYSQL, ProductTypeEnum.MARIADB, ProductTypeEnum.POSTGRESQL)) {
            String prefix = "datahub.test." + (product == ProductTypeEnum.POSTGRESQL ? "postgres" : product.name().toLowerCase(Locale.ROOT));
            var source = new DriverManagerDataSource(System.getProperty(prefix + ".url"),
                    System.getProperty(prefix + ".user", product == ProductTypeEnum.POSTGRESQL ? "dataforge_test" : "root"),
                    System.getProperty(prefix + ".password", ""));
            source.setDriverClassName(product.getDriveClassName()); sources.put(product, source);
            controls.put(product, source.getConnection());
            sql(product, "CREATE " + (product == ProductTypeEnum.POSTGRESQL ? "SCHEMA " : "DATABASE ") + schema);
        }
    }
    @AfterEach void cleanup() throws Exception {
        if (runtime != null) runtime.close();
        SQLException failure = null;
        for (var entry : controls.entrySet()) {
            try (var connection = entry.getValue(); var statement = connection.createStatement()) {
                statement.execute("DROP " + (entry.getKey() == ProductTypeEnum.POSTGRESQL ? "SCHEMA " : "DATABASE ")
                        + "IF EXISTS " + schema + (entry.getKey() == ProductTypeEnum.POSTGRESQL ? " CASCADE" : ""));
            } catch (SQLException caught) { failure = caught; }
        }
        if (failure != null) throw failure;
    }
    private void tables(ProductTypeEnum source, ProductTypeEnum target) throws Exception {
        create(source, "source", "decimal(38,8)", "bigint"); create(target, "target", "decimal(38,8)", "bigint");
    }
    private void create(ProductTypeEnum product, String table, String stamp, String key) throws Exception {
        sql(product, "CREATE TABLE " + schema + "." + table + "(stamp " + stamp + " NOT NULL,id " + key
                + " NOT NULL PRIMARY KEY,value varchar(80))" + (product == ProductTypeEnum.POSTGRESQL ? "" : " ENGINE=InnoDB"));
    }
    private void sql(ProductTypeEnum product, String sql) throws Exception {
        try (var statement = controls.get(product).createStatement()) { statement.execute(sql); }
    }
    private long count(ProductTypeEnum product) throws Exception {
        try (var statement = controls.get(product).createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM " + schema + ".target")) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }
    private DbSwitchTableResult transfer(ProductTypeEnum source, ProductTypeEnum target, CursorRunBinding.CheckpointStore store) throws Exception {
        return transfer(source, target, sources.get(target), store);
    }
    private DbSwitchTableResult transfer(ProductTypeEnum source, ProductTypeEnum target, DataSource targetSource,
                                         CursorRunBinding.CheckpointStore store) throws Exception {
        var result = new DbSwitchTableResult();
        CursorTransfer.execute(runtime, sources.get(source), targetSource, source, target, schema, "source", schema, "target",
                fields, fields, List.of("id"), List.of("id"), "stamp", limits, 2,
                new CursorRunBinding("a".repeat(64), saved.get(), store), result, () -> { });
        return result;
    }
    private boolean save(CursorCheckpoint checkpoint) { saved.set(checkpoint); return true; }

    @ParameterizedTest @CsvSource({"MYSQL,MYSQL", "MARIADB,MARIADB", "MYSQL,MARIADB", "MARIADB,MYSQL",
            "MYSQL,POSTGRESQL", "POSTGRESQL,MYSQL", "MARIADB,POSTGRESQL", "POSTGRESQL,MARIADB"})
    void commitThenCheckpointCrashReplaysEqualWatermarkWithinFixedUpper(ProductTypeEnum source, ProductTypeEnum target) throws Exception {
        tables(source, target);
        for (int i = 1; i <= 5; i++) sql(source, "INSERT INTO " + schema + ".source VALUES(10," + i + ",'original')");
        var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> transfer(source, target, next -> {
            if (calls.incrementAndGet() == 3) return false;
            return save(next);
        }));
        assertEquals(4, count(target)); assertEquals(1, saved.get().sequence()); assertFalse(saved.get().complete());
        assertEquals(target == ProductTypeEnum.POSTGRESQL ? CommitAcknowledgement.POSTGRESQL_XID8_VERIFIED
                : CommitAcknowledgement.JDBC_COMMIT_RETURNED, saved.get().lastCommitAcknowledgement());
        if (target != ProductTypeEnum.POSTGRESQL) assertNull(saved.get().lastCommit());
        sql(source, "INSERT INTO " + schema + ".source VALUES(10,0,'equal watermark'),(11,100,'next window')");
        transfer(source, target, this::save); assertEquals(6, count(target)); assertTrue(saved.get().complete());
        transfer(source, target, this::save); assertEquals(7, count(target));
        assertEquals(source, saved.get().sourceProduct()); assertEquals(target, saved.get().targetProduct()); assertEquals(2, saved.get().version());
    }

    @ParameterizedTest @EnumSource(value=ProductTypeEnum.class, names={"MYSQL", "MARIADB"})
    void unsignedBigintUpperBoundSurvivesEncodingAndPostgresReplay(ProductTypeEnum source) throws Exception {
        create(source, "source", "bigint unsigned", "bigint unsigned");
        create(ProductTypeEnum.POSTGRESQL, "target", "numeric(20,0)", "numeric(20,0)");
        sql(source, "INSERT INTO " + schema + ".source VALUES(18446744073709551615,18446744073709551615,'exact')");
        transfer(source, ProductTypeEnum.POSTGRESQL, this::save);
        assertEquals("18446744073709551615", saved.get().fixedUpper().values().getFirst().value());
        assertEquals(java.sql.Types.NUMERIC, saved.get().fixedUpper().values().getFirst().jdbcType());
        try (var statement = controls.get(ProductTypeEnum.POSTGRESQL).createStatement(); var rows = statement.executeQuery("SELECT stamp,id FROM " + schema + ".target")) {
            assertTrue(rows.next()); assertEquals("18446744073709551615", rows.getBigDecimal(1).toPlainString());
            assertEquals("18446744073709551615", rows.getBigDecimal(2).toPlainString());
        }
    }

    @ParameterizedTest @EnumSource(value=ProductTypeEnum.class, names={"MYSQL", "MARIADB"})
    void identicalDdlRecreationChangesSourceAndTargetPhysicalIdentity(ProductTypeEnum product) throws Exception {
        tables(product, product); sql(product, "INSERT INTO " + schema + ".source VALUES(10,1,'first')");
        transfer(product, product, this::save);
        sql(product, "DROP TABLE " + schema + ".target"); create(product, "target", "decimal(38,8)", "bigint");
        assertThrows(IllegalArgumentException.class, () -> transfer(product, product, this::save)); assertEquals(0, count(product));
        sql(product, "DROP TABLE " + schema + ".source"); create(product, "source", "decimal(38,8)", "bigint");
        sql(product, "INSERT INTO " + schema + ".source VALUES(11,1,'recreated')");
        assertThrows(IllegalArgumentException.class, () -> transfer(product, product, this::save)); assertEquals(0, count(product));
    }

    @ParameterizedTest @EnumSource(value=ProductTypeEnum.class, names={"MYSQL", "MARIADB"})
    void frozenEmptyWindowDoesNotExpandOnCrashRecovery(ProductTypeEnum product) throws Exception {
        tables(product, product); var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> transfer(product, product, next -> calls.incrementAndGet() == 1 && save(next)));
        assertFalse(saved.get().complete()); assertNull(saved.get().fixedUpper());
        sql(product, "INSERT INTO " + schema + ".source VALUES(10,1,'after frozen empty window')");
        transfer(product, product, this::save); assertEquals(0, count(product)); assertTrue(saved.get().complete());
        transfer(product, product, this::save); assertEquals(1, count(product));
    }

    @ParameterizedTest @EnumSource(value=ProductTypeEnum.class, names={"MYSQL", "MARIADB"})
    void actualCommitWithLostAcknowledgementNeverAdvancesCheckpoint(ProductTypeEnum product) throws Exception {
        tables(product, product); sql(product, "INSERT INTO " + schema + ".source VALUES(10,1,'first'),(10,2,'second')");
        DataSource fault = new org.springframework.jdbc.datasource.AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection actual = sources.get(product).getConnection();
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
        assertThrows(UnconfirmedWriteException.class, () -> transfer(product, product, fault, this::save));
        assertEquals(2, count(product)); assertNull(saved.get().applied()); assertEquals(0, saved.get().sequence());
        transfer(product, product, this::save); assertEquals(2, count(product)); assertTrue(saved.get().complete());
    }

    @ParameterizedTest @EnumSource(value=ProductTypeEnum.class, names={"MYSQL", "MARIADB"})
    void civilTimestampKeepsMicrosecondsAcrossProducts(ProductTypeEnum product) throws Exception {
        create(product, "source", "datetime(6)", "bigint"); create(ProductTypeEnum.POSTGRESQL, "target", "timestamp(6)", "bigint");
        sql(product, "INSERT INTO " + schema + ".source VALUES('2026-03-08 02:30:00.123456',1,'civil')");
        transfer(product, ProductTypeEnum.POSTGRESQL, this::save);
        assertEquals("2026-03-08T02:30:00.123456", saved.get().applied().values().getFirst().value());
        try (var statement = controls.get(ProductTypeEnum.POSTGRESQL).createStatement(); var rows = statement.executeQuery("SELECT stamp FROM " + schema + ".target")) {
            assertTrue(rows.next()); assertEquals(java.time.LocalDateTime.parse("2026-03-08T02:30:00.123456"), rows.getObject(1, java.time.LocalDateTime.class));
        }
    }

    @ParameterizedTest @EnumSource(value=ProductTypeEnum.class, names={"MYSQL", "MARIADB"})
    void triggerSideEffectsRefuseReplayBeforeFirstTargetWrite(ProductTypeEnum product) throws Exception {
        tables(product, product); sql(product, "INSERT INTO " + schema + ".source VALUES(10,1,'first')");
        sql(product, "CREATE TRIGGER " + schema + ".side_effect BEFORE INSERT ON " + schema + ".target FOR EACH ROW SET NEW.value='changed'");
        assertThrows(IllegalArgumentException.class, () -> transfer(product, product, this::save));
        assertNull(saved.get()); assertEquals(0, count(product));
    }
}

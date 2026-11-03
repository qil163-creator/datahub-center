package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.*;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.domain.DbSwitchResult;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.data.util.DataSourceUtils;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ThreadFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Real SQL Server 2000 -> PostgreSQL public FULL/DIFF. Only the pool factory binds the
 * isolated legacy driver and task-owned fixtures; metadata, mappings, handler, readers,
 * writers and synchronizers perform actual IO. This is not driver bundle admission proof.
 */
@Timeout(120)
@EnabledIfEnvironmentVariable(named = "DATAHUB_TEST_SQL2000_URL", matches = "jdbc:sqlserver://127[.]0[.]0[.]1:[0-9]+;.*")
@EnabledIfEnvironmentVariable(named = "DATAHUB_TEST_SQL2000_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_TEST_SQL2000_DRIVER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_TEST_PG_URL", matches = "jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/.*")
class MigrationSqlServer2000PostgreSqlMappingTest {
    private final String schema = "sql2000_" + UUID.randomUUID().toString().replace("-", "");
    private final String sourceTable = "source_" + UUID.randomUUID().toString().replace("-", "");
    private final String targetTable = "copied";
    private URLClassLoader loader;
    private DataSource source;
    private DataSource target;
    private final List<HikariDataSource> pools = new ArrayList<>();
    private boolean targetOwned;
    private boolean sourceOwned;

    @BeforeEach void setup() throws Exception {
        loader = new URLClassLoader(new java.net.URL[]{Path.of(System.getenv("DATAHUB_TEST_SQL2000_DRIVER")).toRealPath().toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
        var driver = (Driver) loader.loadClass("com.microsoft.sqlserver.jdbc.SQLServerDriver").getConstructor().newInstance();
        var properties = new Properties(); properties.setProperty("user", System.getenv("DATAHUB_TEST_SQL2000_USER"));
        properties.setProperty("password", System.getenv("DATAHUB_TEST_SQL2000_PASSWORD"));
        source = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                return driver.connect(System.getenv("DATAHUB_TEST_SQL2000_URL"), properties);
            }
            @Override public Connection getConnection(String username, String password) throws SQLException {
                throw new SQLFeatureNotSupportedException("Fixture credentials are bound to its owned DataSource");
            }
        };
        target = new DriverManagerDataSource(System.getenv("DATAHUB_TEST_PG_URL"),
                System.getenv("DATAHUB_TEST_PG_USER"), Objects.requireNonNullElse(System.getenv("DATAHUB_TEST_PG_PASSWORD"), ""));
        try (var c = source.getConnection()) {
            assertEquals("8.00.760", c.getMetaData().getDatabaseProductVersion());
        }
        sql(source, "CREATE TABLE [dbo].[" + sourceTable + "] (id INT NOT NULL PRIMARY KEY,amount DECIMAL(10,2) NULL)");
        sourceOwned = true;
        sql(target, "CREATE SCHEMA \"" + schema + "\""); targetOwned = true;
    }
    @AfterEach void cleanup() throws Exception {
        try {
            closePools();
            if (sourceOwned) {
                assertTrue(sourceTable.matches("source_[a-f0-9]{32}")); sql(source, "DROP TABLE [dbo].[" + sourceTable + "]");
            }
            if (targetOwned) {
                assertTrue(schema.matches("sql2000_[a-f0-9]{32}")); sql(target, "DROP SCHEMA \"" + schema + "\" CASCADE");
            }
        } finally { if (loader != null) loader.close(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void exactNumericFullDifferenceAndReplayWorkWithCreatedOrExistingTarget(boolean existing) throws Exception {
        seed(false);
        if (existing) sql(target, "CREATE TABLE " + targetRelation() + "(id INTEGER PRIMARY KEY,amount NUMERIC(10,2))");
        var request = request(); success(run(request)); assertEquals(numericRows(source, sourceRelation()), numericRows(target, targetRelation()));
        try (var connection = target.getConnection()) {
            var columns = DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL).queryTableColumnMeta(
                    connection, schema, targetTable);
            assertEquals(List.of("id", "amount"), columns.stream().map(column -> column.getFieldName()).toList());
        }
        mutate(false);
        request.getTarget().setChangeDataSync(true); request.getTarget().setChangeDataSyncType("PK_ORDER_COMPARE");
        success(run(request)); assertEquals(numericRows(source, sourceRelation()), numericRows(target, targetRelation()));
        var replay = run(request); success(replay); assertEquals(0, replay.getTableResultList().getFirst().getSyncCount().get());
    }

    @Test void sourceCollationLossRequiresExactAcknowledgementBeforeActualUnicodeTransfer() throws Exception {
        sql(source, "ALTER TABLE " + sourceRelation() + " ADD note NVARCHAR(12) NULL"); seed(true);
        var request = request(); var denied = run(request); assertFalse(denied.getIfAllSuccess().get());
        assertEquals(Set.of("COLLATION_EQUALITY_CHANGED"), denied.getTableResultList().getFirst().getTypeMappingPlan().column("note").lossCodes());
        var metadata = new cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByDataSourceServiceImpl(
                source, ProductTypeEnum.SQLSERVER2000);
        var plan = DbSwitchProductRegistry.discover().typeMappings().plan(ProductTypeEnum.POSTGRESQL,
                metadata.queryTableColumnMetaWithNativeEvidence("dbo", sourceTable), List.of("id"), LossyColumnAcceptance.rejectAll());
        request.setTypeMappingAcknowledgements(plan.columns().stream().filter(column -> column.fidelity() == TypeFidelity.LOSSY)
                .map(column -> LossyMappingAcknowledgement.forColumn(schema, targetTable, column)).toList());
        success(run(request)); assertEquals(textRows(source, sourceRelation()), textRows(target, targetRelation()));
        mutate(true); request.getTarget().setChangeDataSync(true); request.getTarget().setChangeDataSyncType("PK_ORDER_COMPARE");
        success(run(request)); assertEquals(textRows(source, sourceRelation()), textRows(target, targetRelation()));
    }

    private MigrationRequest request() {
        var request = new MigrationRequest(); var input = new SourceDataSourceProperties();
        input.setSourceProductType(ProductTypeEnum.SQLSERVER2000); input.setUrl(System.getenv("DATAHUB_TEST_SQL2000_URL"));
        input.setUsername(System.getenv("DATAHUB_TEST_SQL2000_USER")); input.setPassword(System.getenv("DATAHUB_TEST_SQL2000_PASSWORD"));
        input.setSourceSchema("dbo"); input.setSourceTableNames(List.of(sourceTable)); input.setIncludeOrExclude(1);
        input.setPoolSize(2); input.setFetchSize(2); input.setRegexTableMapper(List.of(new PatternMapper("^" + sourceTable + "$", targetTable)));
        request.getSource().add(input); var output = request.getTarget();
        output.setTargetProductType(ProductTypeEnum.POSTGRESQL); output.setUrl(System.getenv("DATAHUB_TEST_PG_URL"));
        output.setUsername(System.getenv("DATAHUB_TEST_PG_USER")); output.setPassword(Objects.requireNonNullElse(System.getenv("DATAHUB_TEST_PG_PASSWORD"), ""));
        output.setTargetSchema(schema); output.setPoolSize(2); return request;
    }
    private DbSwitchResult run(MigrationRequest request) throws Exception {
        try (var factories = mockStatic(DataSourceUtils.class)) {
            factories.when(() -> DataSourceUtils.createSourceDataSource(any(), any(ThreadFactory.class), any(), any(), any(), any()))
                    .thenAnswer(call -> pool(source, call.getArgument(1)));
            factories.when(() -> DataSourceUtils.createTargetDataSource(any(), any(ThreadFactory.class), any(), any(), any(), any()))
                    .thenAnswer(call -> pool(target, call.getArgument(1)));
            return new MigrationService(request, ignored -> { }).run();
        } finally { closePools(); }
    }
    private HikariDataSource pool(DataSource source, ThreadFactory threads) {
        var config = new HikariConfig(); config.setDataSource(source); config.setMaximumPoolSize(2); config.setMinimumIdle(0);
        config.setConnectionTestQuery("SELECT 1"); config.setConnectionTimeout(10000); config.setInitializationFailTimeout(10000);
        config.setThreadFactory(threads); config.setPoolName("sql2000-native-" + pools.size());
        var pool = new HikariDataSource(config); pools.add(pool); return pool;
    }
    private void closePools() { for (var pool : pools) if (!pool.isClosed()) pool.close(); pools.clear(); }
    private void seed(boolean text) throws Exception {
        try (var c = source.getConnection(); var statement = c.prepareStatement("INSERT INTO " + sourceRelation() + " VALUES(" + (text ? "?,?,?" : "?,?") + ")")) {
            statement.setQueryTimeout(10);
            statement.setInt(1, 1); statement.setBigDecimal(2, new BigDecimal("12.34")); if (text) statement.setString(3, "中'文"); statement.addBatch();
            statement.setInt(1, 2); statement.setNull(2, Types.DECIMAL); if (text) statement.setNull(3, Types.NVARCHAR); statement.addBatch();
            assertArrayEquals(new int[]{1, 1}, statement.executeBatch());
        }
    }
    private void mutate(boolean text) throws Exception {
        sql(source, "UPDATE " + sourceRelation() + " SET amount=45.67" + (text ? ",note=N'changed 中'" : "") + " WHERE id=1");
        sql(source, "DELETE FROM " + sourceRelation() + " WHERE id=2");
        sql(source, "INSERT INTO " + sourceRelation() + " VALUES(3,34.56" + (text ? ",N'third 文'" : "") + ")");
    }
    private static List<List<Object>> numericRows(DataSource source, String relation) throws SQLException {
        var result = new ArrayList<List<Object>>();
        try (var c = source.getConnection(); var statement = c.createStatement()) {
            statement.setQueryTimeout(10);
            try (var rows = statement.executeQuery("SELECT id,amount FROM " + relation + " ORDER BY id")) {
                while (rows.next()) result.add(Arrays.asList(rows.getInt(1), rows.getBigDecimal(2)));
            }
        } return result;
    }
    private static List<List<Object>> textRows(DataSource source, String relation) throws SQLException {
        var result = new ArrayList<List<Object>>();
        try (var c = source.getConnection(); var statement = c.createStatement()) {
            statement.setQueryTimeout(10);
            try (var rows = statement.executeQuery("SELECT id,amount,note FROM " + relation + " ORDER BY id")) {
                while (rows.next()) result.add(Arrays.asList(rows.getInt(1), rows.getBigDecimal(2), rows.getString(3)));
            }
        } return result;
    }
    private static void success(DbSwitchResult result) {
        assertEquals(1, result.getTableResultList().size()); assertTrue(result.getIfAllSuccess().get(), "Native fixture migration failed");
    }
    private void sql(DataSource source, String text) throws SQLException {
        try (var c = source.getConnection(); var statement = c.createStatement()) { statement.setQueryTimeout(10); statement.execute(text); }
    }
    private String sourceRelation() { return "[dbo].[" + sourceTable + "]"; }
    private String targetRelation() { return "\"" + schema + "\".\"" + targetTable + "\""; }
}

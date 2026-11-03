package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.JdbcSelectResult;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class JdbcScriptExecutionPostgreSqlTest {
    private final String table = "script_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeEach
    void create() throws Exception {
        try (Connection connection = open(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE " + table + "(id int primary key, value text)");
        }
    }

    @AfterEach
    void drop() throws Exception {
        try (Connection connection = open(); var sql = connection.createStatement()) {
            sql.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    @Test
    void externalTransactionRemainsInvisibleAndCanBeRolledBackByItsOwner() throws Exception {
        try (Connection writer = open(); Connection observer = open()) {
            writer.setAutoCommit(false);
            JdbcSelectResult result = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL)
                    .executeBySql(writer, "postgresql", insert(1, "left;right"), 10, null);
            assertTrue(result.getSuccess());
            assertEquals(JdbcSelectResult.TransactionOutcome.NOT_MANAGED, result.getTransactionOutcome());
            assertFalse(writer.getAutoCommit());
            assertFalse(writer.isClosed());
            assertEquals(1, count(writer));
            assertEquals(0, count(observer));
            writer.rollback();
            assertEquals(0, count(observer));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void nativeDollarStringsAndQuotedIdentifiersRemainExact(boolean standard) throws Exception {
        String physical="\""+table+"\"\"; \\quoted\"";
        try(var connection=open();var statement=connection.createStatement()) {
            statement.execute("SET standard_conforming_strings="+(standard?"on":"off"));
            connection.setAutoCommit(false);
            try {
                String value="slash\\'; literal; ? $other$ 中文";
                var plan=SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL,"CREATE TABLE "+physical+"(id INT PRIMARY KEY,value TEXT);"
                        +"INSERT INTO "+physical+" VALUES(1,$text$"+value+"$text$);SELECT value FROM "+physical+";");
                var result=cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL)
                        .executePlan(connection,plan,java.util.List.of(),10,null,ignored->{});
                assertTrue(result.getSuccess());assertEquals(value,result.getResults().getLast().getRowData().getFirst().get("value"));
            } finally { connection.rollback(); }
        }
    }

    @Test
    void externalTransactionOnlyBecomesVisibleAfterItsOwnerCommits() throws Exception {
        try (Connection writer = open(); Connection observer = open()) {
            writer.setAutoCommit(false);
            JdbcSelectResult result = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.POSTGRESQL)
                    .executeBySql(writer, "postgresql", insert(1, "left;right"), 10, null);
            assertTrue(result.getSuccess());
            assertEquals(0, count(observer));
            writer.commit();
            assertEquals(1, count(observer));
            try (var sql = observer.createStatement(); var rows = sql.executeQuery("SELECT value FROM " + table)) {
                assertTrue(rows.next());
                assertEquals("left;right", rows.getString(1));
            }
        }
    }

    @Test
    void ownedTransactionCommitsAllSuccessfulStatements() throws Exception {
        JdbcSelectResult result = executeOwned(insert(1, "left;right") + insert(2, "second"));
        assertTrue(result.getSuccess());
        assertEquals(JdbcSelectResult.TransactionOutcome.COMMITTED, result.getTransactionOutcome());
        try (Connection observer = open()) {
            assertEquals(2, count(observer));
        }
    }

    @Test
    void ownedTransactionRollsBackEarlierWritesWhenALaterStatementFails() throws Exception {
        JdbcSelectResult result = executeOwned(insert(1, "first") + insert(1, "duplicate"));
        assertFalse(result.getSuccess());
        assertEquals(JdbcSelectResult.TransactionOutcome.ROLLED_BACK, result.getTransactionOutcome());
        try (Connection observer = open()) {
            assertEquals(0, count(observer));
        }
    }

    @Test
    void customSeparatorExecutesSupportedStatementsWithoutChangingTransactionOwnership() throws Exception {
        String script = "INSERT INTO " + table + " VALUES(1,$literal$left;\n/\nright$literal$);\n/\n"
                + "SELECT value FROM " + table + " WHERE id=1;\n/";
        var plan = SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, script, "/");
        try (Connection writer = open(); Connection observer = open()) {
            writer.setAutoCommit(false);
            var result = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover()
                    .metadata(ProductTypeEnum.POSTGRESQL)
                    .executePlan(writer, plan, java.util.List.of(), 10, null, ignored -> { });
            assertTrue(result.getSuccess());
            assertEquals(2, result.getResults().size());
            assertEquals("left;\n/\nright", result.getResults().getLast().getRowData().getFirst().get("value"));
            assertEquals(JdbcSelectResult.TransactionOutcome.NOT_MANAGED, result.getTransactionOutcome());
            assertFalse(writer.getAutoCommit());
            assertFalse(writer.isClosed());
            assertEquals(0, count(observer));
            writer.rollback();
            assertEquals(0, count(observer));
        }
    }

    @Test
    void namedBindingCompilationKeepsInjectionLookingValuesAsPreparedStatementData() throws Exception {
        String script = "INSERT INTO " + table + "(id,value) VALUES(${id},${value});"
                + "SELECT value FROM " + table + " WHERE id=${id};";
        var bindings = NamedSqlBindings.compile(ProductTypeEnum.POSTGRESQL, script);
        assertEquals(java.util.List.of("id", "value", "id"), bindings.parameterNames());
        assertEquals(script, bindings.restore(ProductTypeEnum.POSTGRESQL, bindings.sql()));
        String value = "'; DROP TABLE fixture; -- ${value} ? / 中文";
        var plan = SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, bindings.sql());
        try (Connection writer = open(); Connection observer = open()) {
            writer.setAutoCommit(false);
            var result = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover()
                    .metadata(ProductTypeEnum.POSTGRESQL)
                    .executePlan(writer, plan, java.util.List.of(1, value, 1), 10, null, ignored -> { });
            assertTrue(result.getSuccess());
            assertEquals(value, result.getResults().getLast().getRowData().getFirst().get("value"));
            assertEquals(1, count(writer));
            assertEquals(0, count(observer));
            writer.rollback();
            assertEquals(0, count(observer));
        }
    }

    @Test
    void authorizedPaginationRewriteExecutesTheCorrectNamedValues() throws Exception {
        var bindings = NamedSqlBindings.compile(ProductTypeEnum.POSTGRESQL,
                "SELECT value FROM " + table + " WHERE id>${minId} ORDER BY id OFFSET ${offset} LIMIT ${limit}");
        String rewritten = RestrictedSelectGuard.restrictProjectionWithRowFilters(ProductTypeEnum.POSTGRESQL,
                bindings.sql(), "public", table, java.util.List.of("id", "value"), java.util.Set.of("id", "value"),
                java.util.List.of("id > 0")).sql();
        String restored = bindings.restore(ProductTypeEnum.POSTGRESQL, rewritten);
        var canonicalBindings = NamedSqlBindings.compile(ProductTypeEnum.POSTGRESQL, restored);
        assertEquals(java.util.List.of("minId", "limit", "offset"), canonicalBindings.parameterNames());
        var values = java.util.Map.of("minId", 0, "offset", 1, "limit", 2);
        var boundValues = canonicalBindings.parameterNames().stream().<Object>map(values::get).toList();
        var plan = SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, canonicalBindings.sql());
        try (Connection writer = open(); Connection observer = open(); var insert = writer.createStatement()) {
            writer.setAutoCommit(false);
            insert.executeUpdate("INSERT INTO " + table + " VALUES(1,'first'),(2,'second'),(3,'third'),(4,'fourth')");
            var result = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover()
                    .metadata(ProductTypeEnum.POSTGRESQL)
                    .executePlan(writer, plan, boundValues, 10, null, ignored -> { });
            assertTrue(result.getSuccess());
            var rows = result.getResults().getFirst().getRowData();
            assertEquals(2, rows.size());
            assertEquals("second", rows.getFirst().get("value"));
            assertEquals("third", rows.getLast().get("value"));
            assertEquals(0, count(observer));
            writer.rollback();
            assertEquals(0, count(observer));
        }
    }

    private JdbcSelectResult executeOwned(String sql) {
        try (DbSwitchRuntime runtime = DbSwitchRuntime.standalone()) {
            return runtime.jdbcMetadata(ProductTypeEnum.POSTGRESQL).executeBySql(
                    System.getProperty("datahub.test.postgres.url"), "postgresql", username(), "", sql, 1, 10, null);
        }
    }

    private String insert(int id, String value) {
        return "INSERT INTO " + table + "(id,value) VALUES (" + id + ",'" + value + "');";
    }

    private int count(Connection connection) throws SQLException {
        try (var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT count(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(System.getProperty("datahub.test.postgres.url"), username(), "");
    }

    private String username() {
        return System.getProperty("datahub.test.postgres.user", "dataforge_test");
    }
}

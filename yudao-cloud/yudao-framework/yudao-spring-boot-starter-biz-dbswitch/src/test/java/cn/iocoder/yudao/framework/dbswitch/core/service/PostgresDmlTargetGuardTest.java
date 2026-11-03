package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.JdbcSelectResult;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class PostgresDmlTargetGuardTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");
    String schema;
    @BeforeEach void setup() throws Exception {
        schema = "write_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema + ";CREATE TABLE " + schema + ".cases(id int PRIMARY KEY,status text);INSERT INTO " + schema + ".cases VALUES(1,'old')");
        }
    }

    @Test void sameConnectionLocksAndCommitsAllAuthorizedStatements() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        var executor = new MetaDataByJdbcServiceImpl(ProductTypeEnum.POSTGRESQL, DbSwitchProductRegistry.discover(),
                (url, user, password) -> { opened.incrementAndGet(); return DriverManager.getConnection(url,user,password); });
        var plan = plan("UPDATE %s.cases SET status=? WHERE id=?; INSERT INTO %s.cases(id,status) VALUES(?,?)".formatted(schema,schema));
        var result = executor.executeAuthorizedDmlPlan(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword(), plan,
                List.of("new",1,2,"two"), List.of(target(1,"cases"),target(2,"cases")), ignored -> { });
        assertEquals(1, opened.get()); assertTrue(result.getSuccess());
        assertEquals(JdbcSelectResult.TransactionOutcome.COMMITTED, result.getTransactionOutcome());
        assertEquals("new", value(1)); assertEquals("two",value(2));
    }

    @Test void authorizedOnlyOutputReplansAndExecutesWithinTheRowScope() throws Exception {
        execute("INSERT INTO " + schema + ".cases VALUES(2,'outside')");
        var grant = new RestrictedDmlAuthorization.Grant(Set.of("UPDATE", "DELETE"), Set.of("status"),
                Set.of("id"), Map.of("id", "1"), false);
        String update = RestrictedDmlAuthorization.authorize(ProductTypeEnum.POSTGRESQL,
                "UPDATE cases SET status=? WHERE id=? OR id=?", schema, schema, "cases",
                Set.of("id", "status"), List.of("new", "1", "2"), List.of(grant)).sql();
        String delete = RestrictedDmlAuthorization.authorize(ProductTypeEnum.POSTGRESQL,
                "DELETE FROM cases WHERE id=?", schema, schema, "cases",
                Set.of("id", "status"), List.of("2"), List.of(grant)).sql();
        var plan = plan(update + ";\n" + delete);
        assertTrue(plan.steps().get(0).sql().startsWith("UPDATE ONLY "));
        assertTrue(plan.steps().get(1).sql().startsWith("DELETE FROM ONLY "));
        assertEquals(List.of(3, 1), plan.steps().stream().map(SqlScriptPlan.Step::parameterCount).toList());
        AtomicInteger connections = new AtomicInteger();
        var executor = new MetaDataByJdbcServiceImpl(ProductTypeEnum.POSTGRESQL, DbSwitchProductRegistry.discover(),
                (url, user, password) -> { connections.incrementAndGet(); return DriverManager.getConnection(url,user,password); });
        var result = executor.executeAuthorizedDmlPlan(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword(),plan,
                List.of("new",1,2,2),List.of(target(1,"cases"),target(2,"cases")),ignored -> { });
        assertTrue(result.getSuccess());
        assertEquals(JdbcSelectResult.TransactionOutcome.COMMITTED, result.getTransactionOutcome());
        assertEquals(1,connections.get());
        assertEquals("new",value(1));
        assertEquals("outside",value(2));
    }

    @Test void onlyKeywordDoesNotRewriteQuotedNamesOrStringContentsAndCommentsRemainDenied() throws Exception {
        execute("CREATE TABLE " + schema + ".\"ONLY\"(id int,\"ONLY\" text);INSERT INTO " + schema + ".\"ONLY\" VALUES(1,'old')");
        var grant = new RestrictedDmlAuthorization.Grant(Set.of("UPDATE"),Set.of("ONLY"),Set.of("id"),Map.of("id","1"),false);
        String sql = "UPDATE \"ONLY\" SET \"ONLY\"='UPDATE ONLY marker' WHERE id=1";
        String authorized = RestrictedDmlAuthorization.authorize(ProductTypeEnum.POSTGRESQL,sql,schema,schema,"ONLY",
                Set.of("id","ONLY"),List.of(),List.of(grant)).sql();
        var plan = plan(authorized);
        assertTrue(plan.steps().getFirst().sql().startsWith("UPDATE ONLY \"" + schema + "\".\"ONLY\""));
        assertTrue(plan.steps().getFirst().sql().contains("'UPDATE ONLY marker'"));
        assertTrue(executePlan(authorized,List.of(target(1,"ONLY"))).getSuccess());
        try(Connection c=connection(); Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT \"ONLY\" FROM " + schema + ".\"ONLY\"")) {
            assertTrue(r.next()); assertEquals("UPDATE ONLY marker",r.getString(1));
        }
        assertThrows(IllegalArgumentException.class, () -> RestrictedDmlAuthorization.inspect(ProductTypeEnum.POSTGRESQL,
                "UPDATE /* ONLY */ cases SET status='x'",schema));
        var mixed = plan(authorized + "; UPDATE " + schema + ".cases SET status='ONLY'");
        assertFalse(mixed.steps().get(1).sql().startsWith("UPDATE ONLY "));
        assertThrows(IllegalArgumentException.class, () -> plan("UPDATE ONLY ONLY " + schema + ".cases SET status='x'"));
    }

    @Test void unsupportedLaterTargetIsRejectedBeforeFirstWriteAndFailedStatementRollsBackAll() throws Exception {
        execute("CREATE VIEW " + schema + ".v AS SELECT * FROM " + schema + ".cases");
        var invalid = executePlan("UPDATE %s.cases SET status='new';UPDATE %s.v SET status='hidden'".formatted(schema,schema),
                List.of(target(1,"cases"),target(2,"v")));
        assertFalse(invalid.getSuccess()); assertEquals(JdbcSelectResult.TransactionOutcome.ROLLED_BACK, invalid.getTransactionOutcome());
        assertEquals("old",value(1));
        var duplicate = executePlan("UPDATE %s.cases SET status='new';INSERT INTO %s.cases(id) VALUES(1)".formatted(schema,schema),
                List.of(target(1,"cases"),target(2,"cases")));
        assertFalse(duplicate.getSuccess()); assertEquals(JdbcSelectResult.TransactionOutcome.ROLLED_BACK,duplicate.getTransactionOutcome());
        assertEquals("old",value(1));
    }

    @Test void triggersRulesPartitionAndInheritanceNeverReceiveWrites() throws Exception {
        execute("CREATE TABLE " + schema + ".triggered(LIKE " + schema + ".cases);CREATE FUNCTION " + schema + ".touch() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END';CREATE TRIGGER touch BEFORE UPDATE ON " + schema + ".triggered FOR EACH ROW EXECUTE FUNCTION " + schema + ".touch()");
        execute("CREATE TABLE " + schema + ".ruled(LIKE " + schema + ".cases);CREATE RULE hide AS ON UPDATE TO " + schema + ".ruled DO INSTEAD NOTHING");
        execute("CREATE TABLE " + schema + ".part(id int,status text) PARTITION BY RANGE(id);CREATE TABLE " + schema + ".child PARTITION OF " + schema + ".part FOR VALUES FROM(0) TO(100)");
        execute("CREATE TABLE " + schema + ".inherited() INHERITS(" + schema + ".cases)");
        for (String table : List.of("triggered","ruled","part","child","inherited","cases")) {
            var result = executePlan("UPDATE " + schema + "." + table + " SET status='bad'",List.of(target(1,table)));
            assertFalse(result.getSuccess(), table); assertEquals(JdbcSelectResult.TransactionOutcome.ROLLED_BACK,result.getTransactionOutcome());
        }
    }

    @Test void heldTargetLockBlocksConcurrentRenameAndSameNameReplacement() throws Exception {
        var plan = plan("UPDATE " + schema + ".cases SET status='new'");
        try (Connection owner = connection(); Connection ddl = connection()) {
            owner.setAutoCommit(false);
            PostgresDmlTargetGuard.lockAndValidate(owner,plan,List.of(target(1,"cases")));
            try (Statement statement = ddl.createStatement()) {
                statement.execute("SET lock_timeout='150ms'");
                SQLException failure = assertThrows(SQLException.class, () -> statement.execute("ALTER TABLE " + schema + ".cases RENAME TO hidden"));
                assertEquals("55P03",failure.getSQLState());
            }
            owner.rollback();
        }
        execute("ALTER TABLE " + schema + ".cases RENAME TO hidden");
    }

    @Test void injectedTargetMismatchedPlanAndAutocommitAreRejected() throws Exception {
        var plan = plan("DELETE FROM " + schema + ".cases");
        assertThrows(IllegalArgumentException.class, () -> PostgresDmlTargetGuard.validatePlan(plan,List.of(new RestrictedDmlTarget(1,"other","cases"))));
        assertThrows(IllegalArgumentException.class, () -> PostgresDmlTargetGuard.validatePlan(plan,List.of(new RestrictedDmlTarget(1,schema,"cases;DROP SCHEMA public"))));
        try(Connection c=connection()) {
            assertThrows(IllegalArgumentException.class, () -> PostgresDmlTargetGuard.lockAndValidate(c,plan,List.of(target(1,"cases"))));
        }
    }

    @Test void readSourceRejectsViewsPartitionsAndBothInheritanceEndpoints() throws Exception {
        execute("CREATE VIEW " + schema + ".source_view AS SELECT * FROM " + schema + ".cases");
        execute("CREATE TABLE " + schema + ".read_part(id int,status text) PARTITION BY RANGE(id);CREATE TABLE " + schema + ".read_child PARTITION OF " + schema + ".read_part FOR VALUES FROM(0) TO(100)");
        execute("CREATE TABLE " + schema + ".read_inherited() INHERITS(" + schema + ".cases)");
        try (var connection = connection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            for (String table : List.of("source_view", "read_part", "read_child", "read_inherited", "cases")) {
                assertThrows(IllegalArgumentException.class,
                        () -> PostgresDmlTargetGuard.lockAndValidateReadSource(connection, schema, table), table);
                connection.rollback();
            }
        }
    }

    @Test void selectOnlyAccountCanLockCheckedSourceAndConcurrentDropMustWait() throws Exception {
        String reader = "read_source_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE TABLE " + schema + ".checked_source(id int CHECK(id>0),status text);INSERT INTO " + schema + ".checked_source VALUES(1,'source')");
        execute("CREATE FUNCTION " + schema + ".write_only_touch() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END';CREATE TRIGGER write_only_touch BEFORE UPDATE ON " + schema + ".checked_source FOR EACH ROW EXECUTE FUNCTION " + schema + ".write_only_touch()");
        execute("CREATE ROLE " + reader + " LOGIN PASSWORD 'isolated-fixture-only';GRANT USAGE ON SCHEMA " + schema
                + " TO " + reader + ";GRANT SELECT ON " + schema + ".checked_source TO " + reader);
        try {
            try (var source = DriverManager.getConnection(DB.getJdbcUrl(), reader, "isolated-fixture-only"); var ddl = connection()) {
                source.setReadOnly(true); source.setAutoCommit(false);
                source.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                PostgresDmlTargetGuard.lockAndValidateReadSource(source, schema, "checked_source");
                try (var statement = source.createStatement(); var rows = statement.executeQuery("SELECT id,status FROM " + schema + ".checked_source")) {
                    assertTrue(rows.next()); assertEquals("source", rows.getString(2));
                }
                try (var statement = source.createStatement(); var rows = statement.executeQuery("SELECT has_table_privilege(current_user,'" + schema + ".checked_source','INSERT')")) {
                    assertTrue(rows.next()); assertFalse(rows.getBoolean(1));
                }
                try (var statement = ddl.createStatement()) {
                    statement.execute("SET lock_timeout='150ms'");
                    var failure = assertThrows(SQLException.class, () -> statement.execute("DROP TABLE " + schema + ".checked_source"));
                    assertEquals("55P03", failure.getSQLState());
                }
                source.rollback();
                try (var statement = ddl.createStatement()) { statement.execute("DROP TABLE " + schema + ".checked_source"); }
            }
        } finally { execute("DROP OWNED BY " + reader + ";DROP ROLE " + reader); }
    }

    @Test void readSourceDoesNotTakeOwnershipOfAutocommitOrRepeatableReadConnection() throws Exception {
        try (var connection = connection()) {
            assertThrows(IllegalArgumentException.class,
                    () -> PostgresDmlTargetGuard.lockAndValidateReadSource(connection, schema, "cases"));
            assertTrue(connection.getAutoCommit());
            connection.setAutoCommit(false); connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            assertThrows(IllegalArgumentException.class,
                    () -> PostgresDmlTargetGuard.lockAndValidateReadSource(connection, schema, "cases"));
            assertFalse(connection.getAutoCommit());
            assertEquals(Connection.TRANSACTION_REPEATABLE_READ, connection.getTransactionIsolation());
            connection.rollback();
        }
    }
    private JdbcSelectResult executePlan(String sql,List<RestrictedDmlTarget> targets) {
        return new MetaDataByJdbcServiceImpl(ProductTypeEnum.POSTGRESQL,DbSwitchProductRegistry.discover(),DriverManager::getConnection)
                .executeAuthorizedDmlPlan(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword(),plan(sql),List.of(),targets, ignored -> {});
    }
    private RestrictedDmlTarget target(int ordinal,String table) { return new RestrictedDmlTarget(ordinal,schema,table); }
    private SqlScriptPlan plan(String sql) { return SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL,sql); }
    private Connection connection() throws SQLException { return DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()); }
    private void execute(String sql) throws SQLException { try(Connection c=connection();Statement s=c.createStatement()) {s.execute(sql);} }
    private String value(int id) throws SQLException {
        try(Connection c=connection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT status FROM "+schema+".cases WHERE id="+id)) {r.next();return r.getString(1);}
    }
}

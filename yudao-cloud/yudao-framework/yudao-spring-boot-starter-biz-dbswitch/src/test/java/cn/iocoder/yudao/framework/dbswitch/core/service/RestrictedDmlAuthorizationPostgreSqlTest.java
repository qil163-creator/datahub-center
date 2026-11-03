package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class RestrictedDmlAuthorizationPostgreSqlTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final Set<String> COLUMNS = Set.of("id", "district_code", "status", "secret", "CaseValue");
    private static final RestrictedDmlAuthorization.Grant GRANT = new RestrictedDmlAuthorization.Grant(
            Set.of("INSERT", "UPDATE", "DELETE"), Set.of("id", "district_code", "status", "CaseValue"),
            Set.of("id", "status"), Map.of("district_code", "370102"), false);

    @Test void threeOperationsExecuteWithBoundScopeAndPreservedParameters() throws Exception {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS authorized; DROP TABLE IF EXISTS authorized.cases; CREATE TABLE authorized.cases(id bigint, district_code text, status text, secret text, \"CaseValue\" numeric)");
            s.execute("INSERT INTO authorized.cases VALUES(1,'370102','NEW','private',0),(2,'370103','NEW','private',0)");
            String update = authorize("UPDATE cases SET status=? WHERE id=? OR id=?", List.of("DONE", "1", "2"));
            try (PreparedStatement p = c.prepareStatement(update)) {
                p.setString(1, "DONE"); p.setLong(2, 1); p.setLong(3, 2); assertEquals(1, p.executeUpdate());
            }
            String decimal = "12345678901234567890.12345678901234567890";
            String insert = authorize("INSERT INTO cases(id,district_code,status,\"CaseValue\") VALUES(?,?,?,?)",
                    List.of("3", "370102", "NEW", decimal));
            try (PreparedStatement p = c.prepareStatement(insert)) {
                p.setLong(1, 3); p.setString(2, "370102"); p.setString(3, "NEW");
                p.setBigDecimal(4, new java.math.BigDecimal(decimal)); assertEquals(1, p.executeUpdate());
            }
            try (ResultSet r = s.executeQuery("SELECT \"CaseValue\" FROM authorized.cases WHERE id=3")) {
                assertTrue(r.next()); assertEquals(decimal, r.getBigDecimal(1).toPlainString());
            }
            assertEquals(1, s.executeUpdate(authorize("DELETE FROM cases WHERE id=1 OR id=2", List.of())));
            try (ResultSet r = s.executeQuery("SELECT status FROM authorized.cases WHERE id=2")) {
                assertTrue(r.next()); assertEquals("NEW", r.getString(1));
            }
        }
    }

    @Test void enforcesOperationWriteColumnsFilterColumnsAndInsertScopes() {
        assertThrows(IllegalArgumentException.class, () -> authorize("UPDATE cases SET secret='x' WHERE id=1", List.of()));
        assertThrows(IllegalArgumentException.class, () -> authorize("UPDATE cases SET status='x' WHERE secret='private'", List.of()));
        assertThrows(IllegalArgumentException.class, () -> authorize("UPDATE cases SET district_code='370103' WHERE id=1", List.of()));
        assertThrows(IllegalArgumentException.class, () -> authorize("INSERT INTO cases(id,district_code) VALUES(?,?)", List.of("4", "370103")));
        assertThrows(IllegalArgumentException.class, () -> authorize("INSERT INTO cases(id) VALUES(4)", List.of()));
        assertThrows(IllegalArgumentException.class, () -> authorize("INSERT INTO cases(id,district_code) VALUES(4,'370102'),(5,'370103')", List.of()));
        var updateOnly = new RestrictedDmlAuthorization.Grant(Set.of("UPDATE"), GRANT.writeColumns(), GRANT.filterColumns(), GRANT.rowScope(), false);
        assertThrows(IllegalArgumentException.class, () -> bind("DELETE FROM cases WHERE id=1", List.of(), List.of(updateOnly)));
        assertThrows(IllegalArgumentException.class, () -> bind("UPDATE cases SET status='x'", List.of(), List.of()));
    }

    @Test void roleGrantsCannotBeStitchedTogetherAndNullScopeIsExplicit() {
        var writes = new RestrictedDmlAuthorization.Grant(Set.of("UPDATE"), Set.of("status"), Set.of(), Map.of("district_code", "370102"), false);
        var filters = new RestrictedDmlAuthorization.Grant(Set.of("UPDATE"), Set.of(), Set.of("id"), Map.of("district_code", "370103"), false);
        assertThrows(IllegalArgumentException.class, () -> bind("UPDATE cases SET status='x' WHERE id=1", List.of(), List.of(writes, filters)));
        Map<String, String> nullScope = new HashMap<>(); nullScope.put("district_code", null);
        var nullable = new RestrictedDmlAuthorization.Grant(GRANT.operations(), GRANT.writeColumns(), GRANT.filterColumns(), nullScope, false);
        assertTrue(bind("DELETE FROM cases WHERE id=1", List.of(), List.of(nullable)).contains("IS NULL"));
        assertDoesNotThrow(() -> bind("INSERT INTO cases(id,district_code) VALUES(1,NULL)", List.of(), List.of(nullable)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE other.cases SET status='x'", "UPDATE cases c SET status='x'",
            "UPDATE cases SET status=(SELECT 'x')", "UPDATE cases SET status=lower('X')",
            "UPDATE cases SET status='x' FROM cases c", "UPDATE cases SET status='x' RETURNING secret",
            "DELETE FROM cases USING other.cases", "DELETE FROM cases RETURNING *",
            "INSERT INTO cases(id) SELECT 1", "INSERT INTO cases(id) VALUES(1) ON CONFLICT DO NOTHING",
            "WITH x AS (SELECT 1) UPDATE cases SET status='x'", "UPDATE cases SET status='x'; DELETE FROM cases",
            "UPDATE cases SET status='x' -- hidden", "UPDATE cases SET status=$$x$$",
            "UPDATE cases SET status='a''b'", "UPDATE cases SET casevalue=1", "UPDATE cases SET status='x' WHERE secret IS NULL"})
    void rejectsUnprovedSyntaxAndIdentity(String sql) {
        assertThrows(IllegalArgumentException.class, () -> authorize(sql, List.of()));
    }

    @Test void quotedCaseTemporaryTableAndSearchPathCannotRedirectWrite() throws Exception {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA \"MixedSchema\";CREATE TABLE \"MixedSchema\".\"Cases\"(id bigint,district_code text,status text);INSERT INTO \"MixedSchema\".\"Cases\" VALUES(1,'370102','old');CREATE TEMP TABLE \"Cases\"(id bigint,district_code text,status text);INSERT INTO \"Cases\" VALUES(1,'370102','temp')");
            var plan = RestrictedDmlAuthorization.authorize(ProductTypeEnum.POSTGRESQL,
                    "UPDATE \"Cases\" SET status=? WHERE id=?", "MixedSchema", "MixedSchema", "Cases", COLUMNS,
                    List.of("new", "1"), List.of(GRANT));
            try (PreparedStatement p = c.prepareStatement(plan.sql())) { p.setString(1,"new"); p.setLong(2,1); assertEquals(1,p.executeUpdate()); }
            try (ResultSet r = s.executeQuery("SELECT status FROM pg_temp.\"Cases\"")) { r.next(); assertEquals("temp",r.getString(1)); }
            assertThrows(IllegalArgumentException.class, () -> RestrictedDmlAuthorization.authorize(ProductTypeEnum.POSTGRESQL,
                    "UPDATE cases SET status='bad'", "MixedSchema", "MixedSchema", "Cases", COLUMNS, List.of(), List.of(GRANT)));
        }
    }

    @Test void scopeLiteralsCannotInjectOrDependOnStringSessionMode() {
        for (String malicious : List.of("x' OR TRUE --", "x\\'; DELETE FROM cases;--")) {
            var unsafe = new RestrictedDmlAuthorization.Grant(GRANT.operations(), GRANT.writeColumns(), GRANT.filterColumns(), Map.of("district_code", malicious), false);
            assertThrows(IllegalArgumentException.class, () -> bind("DELETE FROM cases", List.of(), List.of(unsafe)));
        }
        assertThrows(IllegalArgumentException.class, () -> authorize("UPDATE cases SET status=?", List.of()));
    }

    @Test void numericAndBooleanScopeTextsHaveTheirRealPostgresSemantics() throws Exception {
        Set<String> columns = Set.of("id","district","active","amount");
        var grant = new RestrictedDmlAuthorization.Grant(Set.of("UPDATE","INSERT"),columns,Set.of("id"),
                Map.of("district","370102","active","false"),false);
        try(Connection c=connection();Statement s=c.createStatement()) {
            s.execute("CREATE TABLE public.typed_scope(id bigint,district numeric,active boolean,amount numeric);INSERT INTO public.typed_scope VALUES(1,370102,false,0),(2,370102,true,0)");
            String exact="999999999999999999.1234567890123456789";
            var update=RestrictedDmlAuthorization.authorize(ProductTypeEnum.POSTGRESQL,"UPDATE typed_scope SET amount=? WHERE id=1 OR id=2","public","public","typed_scope",columns,List.of(exact),List.of(grant));
            try(PreparedStatement p=c.prepareStatement(update.sql())) {p.setBigDecimal(1,new java.math.BigDecimal(exact));assertEquals(1,p.executeUpdate());}
            try(ResultSet r=s.executeQuery("SELECT amount FROM public.typed_scope ORDER BY id")) {
                assertTrue(r.next());assertEquals(exact,r.getBigDecimal(1).toPlainString());assertTrue(r.next());assertEquals("0",r.getBigDecimal(1).toPlainString());
            }
            var insert=RestrictedDmlAuthorization.authorize(ProductTypeEnum.POSTGRESQL,"INSERT INTO typed_scope(id,district,active) VALUES(?,?,?)","public","public","typed_scope",columns,List.of("3","370102","false"),List.of(grant));
            try(PreparedStatement p=c.prepareStatement(insert.sql())) {p.setLong(1,3);p.setBigDecimal(2,new java.math.BigDecimal("370102"));p.setBoolean(3,false);assertEquals(1,p.executeUpdate());}
            assertThrows(IllegalArgumentException.class,()->RestrictedDmlAuthorization.authorize(ProductTypeEnum.POSTGRESQL,"INSERT INTO typed_scope(id,district,active) VALUES(?,?,?)","public","public","typed_scope",columns,List.of("4","370102","true"),List.of(grant)));
        }
    }
    private String authorize(String sql, List<String> values) { return bind(sql, values, List.of(GRANT)); }
    private String bind(String sql, List<String> values, List<RestrictedDmlAuthorization.Grant> grants) {
        return RestrictedDmlAuthorization.authorize(ProductTypeEnum.POSTGRESQL, sql, "authorized", "authorized", "cases", COLUMNS, values, grants).sql();
    }
    private Connection connection() throws SQLException { return DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()); }
}

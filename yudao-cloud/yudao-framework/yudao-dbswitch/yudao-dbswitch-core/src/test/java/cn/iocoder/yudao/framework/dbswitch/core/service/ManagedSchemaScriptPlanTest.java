package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ManagedSchemaScriptPlanTest {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"character varying(64)", "character(12)", "double precision"})
    void postgresCatalogTypeAliasesAreBoundWithoutChangingParameters(String nativeType) {
        var plan = ManagedSchemaScriptPlan.parse(ProductTypeEnum.POSTGRESQL, "fixture", List.of(
                "CREATE TABLE demo(id integer PRIMARY KEY, payload " + nativeType + ")",
                "INSERT INTO demo(id,payload) VALUES (?,?)"));
        assertEquals(List.of("DDL", "DML"), plan.steps().stream().map(ManagedSchemaScriptPlan.Step::operation).toList());
        assertEquals(java.util.Set.of("demo"), plan.steps().getFirst().objectNames());
        assertEquals(List.of(0, 1), plan.steps().getLast().parameterOrder());
        assertEquals(plan, ManagedSchemaScriptPlan.parse(ProductTypeEnum.POSTGRESQL, "fixture",
                plan.steps().stream().map(ManagedSchemaScriptPlan.Step::sql).toList()));
    }

    @ParameterizedTest @EnumSource(value = ProductTypeEnum.class, names = {"POSTGRESQL", "MYSQL", "MARIADB", "ORACLE", "DM"})
    void basicCharacterAndNumericColumnsRemainSupportedWhenColumnOptionsAreRejected(ProductTypeEnum product) {
        String schema = product == ProductTypeEnum.ORACLE || product == ProductTypeEnum.DM ? "FIXTURE" : "fixture";
        var plan = ManagedSchemaScriptPlan.parse(product, schema, List.of(
                "CREATE TABLE demo(id INTEGER NOT NULL PRIMARY KEY, value VARCHAR(64), note TEXT, measure DECIMAL(10,3))",
                "ALTER TABLE demo ADD detail VARCHAR(64)"));
        assertEquals(List.of("DDL", "DDL"), plan.steps().stream().map(ManagedSchemaScriptPlan.Step::operation).toList());
        assertTrue(plan.steps().getFirst().sql().contains("VARCHAR(64)"));
    }

    @Test void postgresCreatePopulateProcedureCallAndQueryAreBoundAsOnePlan() {
        var plan = ManagedSchemaScriptPlan.parse(ProductTypeEnum.POSTGRESQL, "fixture", List.of(
                "CREATE TABLE demo(id integer PRIMARY KEY, value varchar(64))",
                "INSERT INTO demo VALUES(?,?)",
                "CREATE PROCEDURE add_row(p_id integer) LANGUAGE SQL AS $$ INSERT INTO demo VALUES(p_id,'routine'); $$",
                "CALL add_row(?)", "SELECT count(*) FROM demo"));
        assertEquals(List.of("DDL", "DML", "PROCEDURE", "CALL", "QUERY"), plan.steps().stream().map(ManagedSchemaScriptPlan.Step::operation).toList());
        assertTrue(plan.steps().getFirst().sql().contains("\"fixture\".\"demo\""));
        assertTrue(plan.steps().get(2).sql().contains("\"fixture\".\"add_row\""));
        assertTrue(plan.steps().get(2).sql().contains("INSERT INTO \"fixture\".\"demo\""));
        assertEquals(List.of(0, 1), plan.steps().get(1).parameterOrder());
        assertEquals(List.of(0), plan.steps().get(3).parameterOrder());
        assertThrows(UnsupportedOperationException.class, () -> plan.steps().clear());
        assertFalse(plan.toString().contains("demo"));
    }

    @ParameterizedTest @EnumSource(value = ProductTypeEnum.class, names = {"ORACLE", "DM"})
    void oracleCompatibleProcedureAndConstantCleanupKeepBodyAndBindEveryObject(ProductTypeEnum product) {
        var plan = ManagedSchemaScriptPlan.parse(product, "FIXTURE", List.of(
                "BEGIN EXECUTE IMMEDIATE 'DROP TABLE DEMO'; EXCEPTION WHEN OTHERS THEN NULL; END;",
                "CREATE TABLE DEMO(ID BIGINT PRIMARY KEY, OBS_TIME TIMESTAMP DEFAULT SYSDATE)",
                "CREATE OR REPLACE PROCEDURE SYNC_DATA AS BEGIN INSERT INTO DEMO(ID) VALUES(1); END;",
                "CALL SYNC_DATA()"));
        assertEquals("BLOCK", plan.steps().getFirst().operation());
        assertTrue(plan.steps().getFirst().sql().contains("DROP TABLE \"FIXTURE\".\"DEMO\""));
        assertTrue(plan.steps().get(2).sql().contains("AUTHID CURRENT_USER"));
        assertTrue(plan.steps().get(2).sql().contains("INSERT INTO \"FIXTURE\".\"DEMO\""));
        assertEquals("SELECT 1 FROM DUAL", ManagedSchemaScriptPlan.completionQuery(product));
    }

    @Test void mysqlAndPostgresIndexNamesDeriveTheirSchemaFromTheBoundTable() {
        for (var product : List.of(ProductTypeEnum.POSTGRESQL, ProductTypeEnum.MYSQL)) {
            var plan = ManagedSchemaScriptPlan.parse(product, "fixture", List.of("CREATE INDEX by_id ON demo(id)"));
            assertFalse(plan.steps().getFirst().sql().contains("fixture\".\"by_id"));
            assertFalse(plan.steps().getFirst().sql().contains("fixture`.`by_id"));
            assertEquals("SELECT 1", ManagedSchemaScriptPlan.completionQuery(product));
        }
    }

    @Test void paginationBindingOrderComesFromTheActualRenderedAst() {
        var plan = ManagedSchemaScriptPlan.parse(ProductTypeEnum.POSTGRESQL, "fixture", List.of(
                "CREATE TABLE demo(id integer)", "SELECT id FROM demo WHERE id>? OFFSET ? LIMIT ?"));
        var query = plan.steps().getLast();
        assertEquals(List.of(0, 2, 1), query.parameterOrder());
        assertTrue(query.sql().contains("LIMIT ? OFFSET ?"));
        var named = NamedSqlBindings.compile(ProductTypeEnum.POSTGRESQL,
                "SELECT id FROM demo WHERE id>${min} OFFSET ${offset} LIMIT ${limit}");
        assertTrue(named.restoreOrdered(ProductTypeEnum.POSTGRESQL, query.sql(), query.parameterOrder())
                .contains("LIMIT ${limit} OFFSET ${offset}"));
        for (var order : List.of(List.of(0, 0, 1), List.of(0, 1), List.of(0, 1, 3)))
            assertThrows(IllegalArgumentException.class, () -> named.restoreOrdered(ProductTypeEnum.POSTGRESQL, query.sql(), order));
    }

    @Test void schemaEscapePrivilegeExternalCodeAndUnknownRoutinesFailClosed() {
        for (String sql : List.of("CREATE TABLE other.demo(id integer)", "DROP SCHEMA fixture CASCADE", "GRANT ALL ON demo TO actor",
                "ALTER TABLE demo SET SCHEMA other", "ALTER TABLE demo OWNER TO actor", "ALTER TABLE demo RENAME TO other.demo",
                "ALTER TABLE demo DROP COLUMN id CASCADE", "TRUNCATE demo CASCADE", "DROP TABLE demo CASCADE",
                "CREATE TABLE demo(id other.custom_type)", "CREATE TABLE demo(id integer REFERENCES other.secret(id))",
                "SELECT id INTO other.secret FROM demo", "SELECT pg_sleep(1) FROM demo", "CALL existing_definer(?)",
                "SET search_path=other", "COMMIT", "CREATE EXTENSION unsafe", "CREATE TABLE demo AS SELECT id FROM other.secret")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ManagedSchemaScriptPlan.parse(ProductTypeEnum.POSTGRESQL, "fixture", List.of(sql)), sql);
        }
        for (String sql : List.of("BEGIN EXECUTE IMMEDIATE variable_sql; END;",
                "BEGIN EXECUTE IMMEDIATE 'DROP TABLE other.demo'; END;",
                "CREATE PROCEDURE p AUTHID DEFINER AS BEGIN NULL; END;",
                "CREATE PROCEDURE p AS BEGIN CALL existing_definer(); END;"))
            assertThrows(IllegalArgumentException.class,
                    () -> ManagedSchemaScriptPlan.parse(ProductTypeEnum.DM, "FIXTURE", List.of(sql)));
        for (String mode : List.of("OUT", "INOUT"))
            assertThrows(IllegalArgumentException.class, () -> ManagedSchemaScriptPlan.parse(
                    ProductTypeEnum.POSTGRESQL, "fixture", List.of("CREATE PROCEDURE p(" + mode
                            + " p_id INTEGER) LANGUAGE SQL AS $$ SELECT 1; $$", "CALL p(1)")));
    }

    @Test void ordinaryDmlCannotBeSilentlyPromotedToSchemaPrivilege() {
        assertFalse(ManagedSchemaScriptPlan.requiresSchemaAuthorization(ProductTypeEnum.POSTGRESQL,
                List.of("SELECT id FROM demo", "UPDATE demo SET id=? WHERE id=?")));
        assertTrue(ManagedSchemaScriptPlan.requiresSchemaAuthorization(ProductTypeEnum.POSTGRESQL, List.of("CREATE TABLE demo(id integer)")));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedSchemaScriptPlan.parse(ProductTypeEnum.POSTGRESQL, "fixture", List.of("INSERT INTO demo VALUES(?)")));
        assertThrows(IllegalArgumentException.class,
                () -> ManagedSchemaScriptPlan.parse(ProductTypeEnum.POSTGRESQL, "fixture", List.of("CREATE TABLE demo(id integer); DROP TABLE other.secret")));
    }

    @Test void resultReturningWritesDoNotEnterTheExecuteUpdateContract() {
        for (String sql : List.of("INSERT INTO demo VALUES(1) RETURNING id", "UPDATE demo SET id=1 RETURNING id",
                "DELETE FROM demo RETURNING *"))
            assertThrows(IllegalArgumentException.class, () -> ManagedSchemaScriptPlan.parse(
                    ProductTypeEnum.POSTGRESQL, "fixture", List.of("CREATE TABLE demo(id integer)", sql)));
        for (var product : List.of(ProductTypeEnum.ORACLE, ProductTypeEnum.DM))
            for (String sql : List.of("INSERT INTO DEMO(ID) VALUES(1) RETURNING ID INTO output_id",
                    "UPDATE DEMO SET ID=1 RETURNING ID INTO output_id", "DELETE FROM DEMO RETURNING ID INTO output_id"))
                assertThrows(IllegalArgumentException.class, () -> ManagedSchemaScriptPlan.parse(
                        product, "FIXTURE", List.of("CREATE TABLE DEMO(ID INTEGER)", sql)));
    }
}

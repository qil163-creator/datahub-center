package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Parser/lexer contracts only; vendor execution compatibility requires a real target engine. */
class MigrationHookPlanTest {
    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"UNKNOWN", "MONGODB", "ELASTICSEARCH", "INFLUXDB3"},
            mode = EnumSource.Mode.EXCLUDE)
    void ordinarySqlPlansWithTheProductsDialectAndRetainsEveryToken(ProductTypeEnum product) {
        List<String> statements = new ArrayList<>(List.of(
                "CREATE TABLE fixture(id INTEGER, note VARCHAR(40))",
                "INSERT INTO fixture VALUES(1,'left;?@:# and ''quotes''')",
                "UPDATE fixture SET note='next' WHERE id=1",
                "DELETE FROM fixture WHERE id=2",
                "SELECT id,note FROM fixture",
                "CREATE INDEX fixture_ix ON fixture(id)"));
        if (product != ProductTypeEnum.DORIS && product != ProductTypeEnum.STARROCKS)
            statements.add("ALTER TABLE fixture ADD spare INTEGER");
        statements.addAll(List.of("DROP INDEX fixture_ix", "DROP TABLE fixture"));
        var plan = SqlScriptPlan.parseMigrationHook(product, String.join(";", statements) + "; -- trailing comment");
        assertEquals(statements, plan.steps().stream().map(SqlScriptPlan.Step::sql).toList());
        assertEquals(product, plan.product());
        assertTrue(plan.steps().stream().allMatch(step -> step.parameterCount() == 0));
        assertThrows(UnsupportedOperationException.class, () -> plan.steps().clear());
    }

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"DORIS", "STARROCKS"})
    void unimplementedNativeAlterGrammarRemainsAVisibleGap(ProductTypeEnum product) {
        assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.parseMigrationHook(product,
                "ALTER TABLE fixture ADD COLUMN spare INT"));
    }

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"UNKNOWN", "MONGODB", "ELASTICSEARCH", "INFLUXDB3"},
            mode = EnumSource.Mode.EXCLUDE)
    void transactionProcedureDirectivesAndUnboundInputCannotReachTheFirstStep(ProductTypeEnum product) {
        for (String rejected : List.of("COMMIT", "ROLLBACK", "BEGIN", "SAVEPOINT fixture", "CALL fixture()",
                "SET autocommit=0", "INSERT INTO fixture VALUES(?)", "SELECT :unbound", "SELECT @unbound",
                "CREATE PROCEDURE fixture() BEGIN SELECT 1; END", "CREATE FUNCTION fixture() RETURNS INT RETURN 1",
                "CREATE TRIGGER fixture BEFORE INSERT ON other_table FOR EACH ROW SET NEW.value=1",
                "DELIMITER //\nSELECT 1//", "SELECT 1;\nGO", "SELECT 1;\n/")) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> SqlScriptPlan.parseMigrationHook(product, "INSERT INTO fixture VALUES(1);" + rejected), rejected);
            assertNull(failure.getCause());
        }
    }

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"MYSQL", "MARIADB", "TIDB", "POSTGRESQL", "OPENGAUSS"})
    void establishedFiveProductPlansRemainAcceptedWithoutWideningDefaultAdmission(ProductTypeEnum product) {
        String script = "CREATE TABLE fixture(id INTEGER, note VARCHAR(40)); INSERT INTO fixture VALUES(1,'left;right');"
                + "ALTER TABLE fixture ADD extra INTEGER; CREATE INDEX fixture_ix ON fixture(id); SELECT note FROM fixture;";
        assertEquals(SqlScriptPlan.parse(product, script).steps().size(),
                SqlScriptPlan.parseMigrationHook(product, script).steps().size());
        assertTrue(SqlStatementClassifier.supports(product));
    }

    @Test void establishedPostgresIdentitySequenceAndLiteralGrammarRemainsAccepted() {
        String script = "CREATE SEQUENCE public.fixture_seq AS bigint START WITH 5 INCREMENT BY 3"
                + " MINVALUE 1 MAXVALUE 9223372036854775807 CACHE 1 NO CYCLE;"
                + "ALTER SEQUENCE public.fixture_seq RESTART WITH 11;"
                + "CREATE TABLE fixture(id bigint GENERATED ALWAYS AS IDENTITY (START WITH 5 INCREMENT BY 3) PRIMARY KEY,"
                + " note text COLLATE \"C\");"
                + "INSERT INTO fixture(note) VALUES($literal$slash\\'; ? @ : \" ; 中文$literal$);";
        var former = SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, script);
        var hook = SqlScriptPlan.parseMigrationHook(ProductTypeEnum.POSTGRESQL, script);
        assertEquals(former.steps().size(), hook.steps().size());
        assertTrue(hook.steps().get(2).sql().contains("IDENTITY (START WITH 5 INCREMENT BY 3) PRIMARY KEY"));
        assertTrue(hook.steps().getLast().sql().contains("$literal$slash\\'; ? @ : \" ; 中文$literal$"));
    }

    @Test void sqlServerBracketsAndTemporaryTableHashDoNotBecomeSplitPointsOrComments() {
        String first = "CREATE TABLE [fixture;GO]([column;'] INT)";
        String second = "CREATE TABLE #fixture(id INT)";
        var plan = SqlScriptPlan.parseMigrationHook(ProductTypeEnum.SQLSERVER, first + ";" + second);
        assertEquals(List.of(first, second), plan.steps().stream().map(SqlScriptPlan.Step::sql).toList());
        var hidden = assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.parseMigrationHook(
                ProductTypeEnum.SQLSERVER, "SELECT 1 # ignored by mysql;COMMIT"));
        assertNull(hidden.getCause());
    }

    @Test void sqlServerEscapedBracketNamesRemainExplicitlyRejectedWhenDruidCannotParseThem() {
        assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.parseMigrationHook(ProductTypeEnum.SQLSERVER,
                "CREATE TABLE [fixture;GO]]end](id INT)"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT ARRAY[1] @> ARRAY[1]", "SELECT ARRAY[1] <@ ARRAY[1]",
            "SELECT '{}'::jsonb @> '{}'::jsonb", "SELECT '{}'::jsonb ? 'key'"})
    void establishedPostgresExpressionOperatorsAreNotMistakenForBindings(String sql) {
        assertEquals(1, SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, sql).steps().size());
        assertEquals(sql, SqlScriptPlan.parseMigrationHook(ProductTypeEnum.POSTGRESQL, sql).steps().getFirst().sql());
    }

    @Test void mysqlHashCommentDoesNotIntroduceUnboundParametersOrDirectives() {
        String statement = "# comment with ? @ :unbound and GO\nCREATE TABLE fixture(id INT)";
        assertEquals(statement, SqlScriptPlan.parseMigrationHook(ProductTypeEnum.MYSQL, statement).steps().getFirst().sql());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT 2 /*M! +1 */", "SELECT 2 /*m! +1 */", "SELECT 2 /*! +1 */",
            "CREATE TABLE allowed(id INT) /*M! AS SELECT id FROM denied */"})
    void vendorExecutableCommentsCannotChangeTheValidatedHookAtExecution(String sql) {
        assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.parseMigrationHook(ProductTypeEnum.MARIADB, sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT 1--@@autocommit", "SELECT 1--?"})
    void mysqlDashArithmeticCannotHideUnboundInputsAsAComment(String sql) {
        assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.parseMigrationHook(ProductTypeEnum.MYSQL, sql));
        assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.parseMigrationHook(ProductTypeEnum.MARIADB, sql));
    }

    @ParameterizedTest
    @EnumSource(value = ProductTypeEnum.class, names = {"UNKNOWN", "MONGODB", "ELASTICSEARCH", "INFLUXDB3"})
    void productsWithoutAnAdmittedSqlDialectFailExplicitly(ProductTypeEnum product) {
        assertThrows(UnsupportedOperationException.class, () -> SqlScriptPlan.parseMigrationHook(product, "SELECT 1"));
    }

    @Test void widenedHookSyntaxDoesNotWidenTheExternalSqlBoundary() {
        assertThrows(UnsupportedOperationException.class, () -> SqlScriptPlan.parse(ProductTypeEnum.ORACLE, "SELECT 1 FROM dual"));
        assertFalse(SqlStatementClassifier.supports(ProductTypeEnum.ORACLE));
        assertFalse(SqlStatementClassifier.supports(ProductTypeEnum.SQLITE3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT 'fixture_secret_token", "INSERT INTO fixture VALUES('fixture_secret_token'); invalid syntax",
            "SELECT 1; /* fixture_secret_token", "SELECT 1; /*! fixture_secret_token */ SELECT 2", "SELECT 1; COMMIT /* fixture_secret_token */"})
    void incompleteOrUnsupportedInputDoesNotLeakSql(String script) {
        var error = assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.parseMigrationHook(ProductTypeEnum.SQLITE3, script));
        assertFalse(error.toString().contains("fixture_secret_token"));
        assertNull(error.getCause());
    }

    @Test void readerIsBorrowedAndInputBudgetsApplyBeforeAnyPlanIsReturned() {
        boolean[] closed = {false};
        var reader = new StringReader("SELECT 'left;right'; SELECT 2") {
            @Override public void close() { closed[0] = true; super.close(); }
        };
        assertEquals(2, SqlScriptPlan.parseMigrationHook(ProductTypeEnum.SQLITE3, reader).steps().size());
        assertFalse(closed[0]);
        assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.parseMigrationHook(ProductTypeEnum.SQLITE3, "SELECT 1;".repeat(501)));
        assertThrows(IllegalArgumentException.class, () -> SqlScriptPlan.parseMigrationHook(ProductTypeEnum.SQLITE3, "SELECT '" + "x".repeat(25_000) + "'"));
    }
}

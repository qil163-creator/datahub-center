package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReadOnlySqlDialectTest {

    @Test
    void dorisSelectDoesNotFailAtProductAdmission() {
        assertDoesNotThrow(() -> SqlStatementClassifier.assertReadOnlyQuery(ProductTypeEnum.DORIS, "SELECT 1"));
    }

    @Test
    void everyJdbcSqlProductHasAnExplicitReadOnlyDialectWithoutAdmittingNativeProducts() {
        var unsupported = EnumSet.of(ProductTypeEnum.UNKNOWN, ProductTypeEnum.MONGODB, ProductTypeEnum.ELASTICSEARCH,
                ProductTypeEnum.ODPS, ProductTypeEnum.NEO4J, ProductTypeEnum.MILVUS, ProductTypeEnum.INFLUXDB2);
        for (var product : ProductTypeEnum.values()) {
            assertEquals(!unsupported.contains(product), SqlStatementClassifier.supportsReadOnly(product), product.name());
            if (unsupported.contains(product)) {
                assertThrows(UnsupportedOperationException.class,
                        () -> SqlStatementClassifier.assertReadOnlyQuery(product, "SELECT 1"), product.name());
            } else {
                String sql = switch (product) {
                    case ORACLE, DM -> "SELECT 1 FROM DUAL";
                    case DB2 -> "SELECT 1 FROM SYSIBM.SYSDUMMY1";
                    case SAPHANA -> "SELECT 1 FROM DUMMY";
                    default -> "SELECT 1";
                };
                assertDoesNotThrow(() -> SqlStatementClassifier.assertReadOnlyQuery(product, sql), product.name());
                assertDoesNotThrow(() -> SqlStatementClassifier.assertReadOnlyQuery(product,
                        "SELECT id, COUNT(*) FROM fixture WHERE id > ? GROUP BY id ORDER BY id"), product.name());
            }
        }
        assertFalse(SqlStatementClassifier.supportsReadOnly(null));
    }

    @Test
    void readOnlyAdmissionDoesNotWidenAnyExistingClassificationOrScriptEntry() {
        var existing = EnumSet.of(ProductTypeEnum.POSTGRESQL, ProductTypeEnum.OPENGAUSS, ProductTypeEnum.MYSQL,
                ProductTypeEnum.MARIADB, ProductTypeEnum.TIDB);
        for (var product : ProductTypeEnum.values()) {
            assertEquals(existing.contains(product), SqlStatementClassifier.supports(product), product.name());
            if (!existing.contains(product)) {
                assertThrows(UnsupportedOperationException.class,
                        () -> SqlStatementClassifier.classify(product, "INSERT INTO fixture VALUES (1)"), product.name());
                assertThrows(UnsupportedOperationException.class,
                        () -> SqlScriptPlan.parse(product, "INSERT INTO fixture VALUES (1)"), product.name());
            }
        }
        assertEquals(SqlStatementClassifier.Kind.DML, SqlStatementClassifier.classify(ProductTypeEnum.POSTGRESQL,
                "UPDATE fixture SET id=1").kind());
    }

    @Test
    void eachSqlDialectRefusesWritesMultipleStatementsAndControlStructures() {
        for (var product : ProductTypeEnum.values()) {
            if (!SqlStatementClassifier.supportsReadOnly(product)) continue;
            for (String sql : List.of("INSERT INTO fixture VALUES (1)", "UPDATE fixture SET id=1",
                    "DELETE FROM fixture", "DROP TABLE fixture", "SELECT 1; SELECT 2", "SELECT 1; DELETE FROM fixture",
                    "SELECT id INTO exported FROM fixture", "SELECT id FROM fixture INTO OUTFILE '/tmp/export'",
                    "SELECT id FROM fixture FOR UPDATE", "SELECT id FROM fixture FOR SHARE",
                    "SELECT id FROM fixture LOCK IN SHARE MODE", "SELECT id FROM fixture WITH (UPDLOCK)",
                    "SET autocommit=0", "CALL side_effect()", "SELECT 1 trailing invalid syntax")) {
                assertThrows(RuntimeException.class, () -> SqlStatementClassifier.assertReadOnlyQuery(product, sql),
                        product + ": " + sql);
            }
        }
    }

    @Test
    void dorisAcceptsBoundedSelectAndCteButRecursivelyRejectsSideEffects() {
        for (String sql : List.of("/* ordinary comment */ SELECT `id`, UPPER(name), COALESCE(value, 'fallback') FROM fixture WHERE id=?",
                "WITH sample AS (SELECT id, value FROM fixture WHERE id=?) SELECT id, value FROM sample ORDER BY id",
                "SELECT COUNT(*), SUM(value), MAX(value) FROM fixture",
                "SELECT 'INTO OUTFILE FOR UPDATE @var sleep(1) /*+' AS value",
                "# ordinary comment\nSELECT 1", "SELECT 1; -- trailing comment")) {
            assertDoesNotThrow(() -> SqlStatementClassifier.assertReadOnlyQuery(ProductTypeEnum.DORIS, sql), sql);
        }
        for (String sql : List.of("SELECT sleep(1)", "SELECT GET_LOCK('key',1)", "SELECT LOAD_FILE('/tmp/file')",
                "SELECT custom_udf(id) FROM fixture", "SELECT admin.function(id) FROM fixture",
                "SELECT @value:=1", "SELECT @value", "SELECT @@sql_mode", "SELECT id INTO @value FROM fixture",
                "SELECT id FROM fixture WHERE EXISTS (SELECT sleep(1))",
                "WITH sample AS (SELECT GET_LOCK('key',1)) SELECT * FROM sample",
                "WITH changed AS (DELETE FROM fixture RETURNING *) SELECT * FROM changed",
                "SELECT /*+ SET_VAR(query_timeout=100) */ id FROM fixture",
                "SELECT 1 /*! + sleep(1) */", "SELECT 1 /*M! + sleep(1) */",
                "SELECT 'back\\slash'", "SELECT 'escaped\\'; DELETE FROM fixture; -- '")) {
            assertThrows(RuntimeException.class,
                    () -> SqlStatementClassifier.assertReadOnlyQuery(ProductTypeEnum.DORIS, sql), sql);
        }
    }

    @Test
    void sessionSensitiveQuotesAndUnsupportedFunctionsFailWithoutLeakingInput() {
        for (var product : Set.of(ProductTypeEnum.MYSQL, ProductTypeEnum.MARIADB, ProductTypeEnum.DORIS,
                ProductTypeEnum.STARROCKS, ProductTypeEnum.TIDB, ProductTypeEnum.GBASE8A, ProductTypeEnum.OCEANBASE)) {
            assertThrows(IllegalArgumentException.class,
                    () -> SqlStatementClassifier.assertReadOnlyQuery(product, "SELECT \"ambiguous\" FROM fixture"));
        }
        for (String sql : List.of("SELECT nextval('sequence')", "SELECT setval('sequence',1)",
                "SELECT pg_advisory_lock(1)", "SELECT pg_read_file('/tmp/file')", "SELECT schema.custom_function()")) {
            assertThrows(RuntimeException.class,
                    () -> SqlStatementClassifier.assertReadOnlyQuery(ProductTypeEnum.POSTGRESQL, sql), sql);
        }
        assertThrows(RuntimeException.class,
                () -> SqlStatementClassifier.assertReadOnlyQuery(ProductTypeEnum.ORACLE, "SELECT example.nextval FROM DUAL"));
        assertThrows(RuntimeException.class,
                () -> SqlStatementClassifier.assertReadOnlyQuery(ProductTypeEnum.ORACLE, "SELECT example.\"NEXTVAL\" FROM DUAL"));
        assertThrows(RuntimeException.class,
                () -> SqlStatementClassifier.assertReadOnlyQuery(ProductTypeEnum.ORACLE, "SELECT example.\"CURRVAL\" FROM DUAL"));
        var failure = assertThrows(IllegalArgumentException.class, () -> SqlStatementClassifier.assertReadOnlyQuery(
                ProductTypeEnum.DORIS, "SELECT 'fixture_secret_token"));
        assertFalse(failure.toString().contains("fixture_secret_token"));
        assertNull(failure.getCause());
    }

    @Test
    void readOnlyInputRetainsTheBoundedSingleStatementBudget() {
        assertDoesNotThrow(() -> SqlStatementClassifier.assertReadOnlyQuery(ProductTypeEnum.DORIS,
                "SELECT '" + "x".repeat(24_000) + "'"));
        assertThrows(IllegalArgumentException.class, () -> SqlStatementClassifier.assertReadOnlyQuery(ProductTypeEnum.DORIS,
                "SELECT '" + "x".repeat(25_000) + "'"));
    }

    @Test
    void preparedReadOnlySqlRemovesOnlyTheValidatedTerminatorAndPureTrailingComments() {
        String sql = "/* header */ SELECT ';?--' AS value, id FROM fixture WHERE id=?";
        assertEquals(sql, SqlStatementClassifier.prepareReadOnlyQuery(ProductTypeEnum.DORIS,
                "  " + sql + "; -- trailing comment with ; DELETE FROM fixture\n /* comment */ "));
        assertEquals("SELECT id FROM fixture -- no terminator", SqlStatementClassifier.prepareReadOnlyQuery(
                ProductTypeEnum.DORIS, "SELECT id FROM fixture -- no terminator"));
        assertThrows(IllegalArgumentException.class, () -> SqlStatementClassifier.prepareReadOnlyQuery(
                ProductTypeEnum.DORIS, "SELECT 1; DELETE FROM fixture; -- trailing"));
        assertThrows(IllegalArgumentException.class, () -> SqlStatementClassifier.prepareReadOnlyQuery(
                ProductTypeEnum.DORIS, "-- comment only"));
    }

    @Test
    void preparedPaginationShapeKeepsCteUnionAndNestedLimitBindingsSeparate() {
        for (String sql : List.of("SELECT id FROM fixture WHERE id=? LIMIT 5 OFFSET 2",
                "WITH sample AS (SELECT id FROM fixture WHERE id=?) SELECT id FROM sample LIMIT 5",
                "SELECT id FROM fixture WHERE id=? UNION ALL SELECT id FROM fixture LIMIT 5")) {
            var prepared = SqlStatementClassifier.prepareReadOnlyQueryPlan(ProductTypeEnum.DORIS, sql + "; -- trailing");
            assertEquals(sql, prepared.sql());
            assertTrue(prepared.topLevelPagination(), sql);
            assertFalse(prepared.parameterizedPagination(), sql);
        }
        for (String sql : List.of("SELECT id FROM fixture WHERE id=?", "SELECT '?' AS value FROM fixture",
                "SELECT id FROM (SELECT id FROM fixture LIMIT 5) sample")) {
            var prepared = SqlStatementClassifier.prepareReadOnlyQueryPlan(ProductTypeEnum.DORIS, sql);
            assertFalse(prepared.topLevelPagination(), sql);
            assertFalse(prepared.parameterizedPagination(), sql);
        }
        for (String sql : List.of("SELECT id FROM fixture LIMIT ?", "SELECT id FROM fixture LIMIT 5 OFFSET ?",
                "SELECT id FROM (SELECT id FROM fixture LIMIT ?) sample",
                "WITH sample AS (SELECT id FROM fixture LIMIT ?) SELECT id FROM sample")) {
            var prepared = SqlStatementClassifier.prepareReadOnlyQueryPlan(ProductTypeEnum.DORIS, sql);
            assertTrue(prepared.parameterizedPagination(), sql);
        }
    }

    @Test
    void dialectSpecificExpressionsCannotHideUnsafeFunctionsFromTheGenericVisitor() {
        assertDoesNotThrow(() -> SqlStatementClassifier.assertReadOnlyQuery(ProductTypeEnum.POSTGRESQL,
                "SELECT DISTINCT ON (id) id FROM fixture ORDER BY id"));
        assertThrows(RuntimeException.class, () -> SqlStatementClassifier.assertReadOnlyQuery(ProductTypeEnum.POSTGRESQL,
                "SELECT DISTINCT ON (pg_advisory_lock(1)) id FROM fixture"));
        assertDoesNotThrow(() -> SqlStatementClassifier.assertReadOnlyQuery(ProductTypeEnum.SQLSERVER,
                "SELECT TOP (1) id FROM fixture"));
        assertThrows(RuntimeException.class, () -> SqlStatementClassifier.assertReadOnlyQuery(ProductTypeEnum.SQLSERVER,
                "SELECT TOP (custom_function()) id FROM fixture"));
        assertDoesNotThrow(() -> SqlStatementClassifier.assertReadOnlyQuery(ProductTypeEnum.CLICKHOUSE,
                "SELECT id FROM fixture PREWHERE id > 1"));
        assertThrows(RuntimeException.class, () -> SqlStatementClassifier.assertReadOnlyQuery(ProductTypeEnum.CLICKHOUSE,
                "SELECT id FROM fixture PREWHERE sleep(1)"));
        assertThrows(RuntimeException.class, () -> SqlStatementClassifier.assertReadOnlyQuery(ProductTypeEnum.POSTGRESQL,
                "SELECT id FROM fixture FETCH FIRST (nextval('sequence')) ROWS ONLY"));
        assertThrows(RuntimeException.class, () -> SqlStatementClassifier.assertReadOnlyQuery(ProductTypeEnum.OSCAR,
                "SELECT DISTINCT ON (nextval('sequence')) id FROM fixture"));
        assertThrows(RuntimeException.class, () -> SqlStatementClassifier.assertReadOnlyQuery(ProductTypeEnum.OSCAR,
                "SELECT TOP (custom_function()) id FROM fixture"));
        assertThrows(RuntimeException.class, () -> SqlStatementClassifier.assertReadOnlyQuery(ProductTypeEnum.CLICKHOUSE,
                "SELECT id FROM fixture FORMAT JSON"));
        assertThrows(RuntimeException.class, () -> SqlStatementClassifier.assertReadOnlyQuery(ProductTypeEnum.POSTGRESQL,
                "SELECT * FROM dblink('host=remote','DELETE FROM fixture RETURNING *') AS result(id int)"));
        assertThrows(RuntimeException.class, () -> SqlStatementClassifier.assertReadOnlyQuery(ProductTypeEnum.OSCAR,
                "SELECT * FROM custom_function() AS result(id int)"));
        assertThrows(RuntimeException.class, () -> SqlStatementClassifier.assertReadOnlyQuery(ProductTypeEnum.ORACLE,
                "SELECT * FROM fixture@remote_link"));
        assertThrows(RuntimeException.class, () -> SqlStatementClassifier.assertReadOnlyQuery(ProductTypeEnum.SQLSERVER,
                "SELECT TOP (1) id FROM fixture ORDER BY custom_function()"));
        assertThrows(RuntimeException.class, () -> SqlStatementClassifier.assertReadOnlyQuery(ProductTypeEnum.DB2,
                "SELECT id FROM fixture ORDER BY custom_function()"));
    }
}

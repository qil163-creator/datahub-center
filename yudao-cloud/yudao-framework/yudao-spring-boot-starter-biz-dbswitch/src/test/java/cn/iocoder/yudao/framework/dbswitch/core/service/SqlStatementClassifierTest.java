package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlStatementClassifierTest {

    @Test
    void acceptsPlainAndCteSelect() {
        assertEquals(SqlStatementClassifier.Kind.QUERY, SqlStatementClassifier.classify(
                ProductTypeEnum.POSTGRESQL, "SELECT id FROM orders WHERE status = 1").kind());
        assertEquals(SqlStatementClassifier.Kind.QUERY, SqlStatementClassifier.classify(
                ProductTypeEnum.POSTGRESQL,
                "WITH t AS (SELECT 1 AS id) SELECT id FROM t").kind());
        assertDoesNotThrow(() -> SqlStatementClassifier.assertReadOnlyQuery(
                ProductTypeEnum.MYSQL, "/* comment */ SELECT * FROM `order`"));
    }

    @Test
    void rejectsWriteAndMultiStatementsEvenWhenLabeledAsQuery() {
        assertEquals(SqlStatementClassifier.Kind.DML, SqlStatementClassifier.classify(
                ProductTypeEnum.POSTGRESQL, "INSERT INTO orders(id) VALUES (1)").kind());
        assertEquals(SqlStatementClassifier.Kind.DML, SqlStatementClassifier.classify(
                ProductTypeEnum.POSTGRESQL, "UPDATE orders SET status = 1").kind());
        assertEquals(SqlStatementClassifier.Kind.DML, SqlStatementClassifier.classify(
                ProductTypeEnum.MYSQL, "DELETE FROM orders").kind());
        assertEquals(SqlStatementClassifier.Kind.MULTI, SqlStatementClassifier.classify(
                ProductTypeEnum.POSTGRESQL, "SELECT 1; DELETE FROM secrets").kind());
        assertEquals(SqlStatementClassifier.Kind.DDL, SqlStatementClassifier.classify(
                ProductTypeEnum.POSTGRESQL, "DROP TABLE orders").kind());
        assertThrows(IllegalArgumentException.class, () -> SqlStatementClassifier.assertReadOnlyQuery(
                ProductTypeEnum.POSTGRESQL, "INSERT INTO t SELECT * FROM orders"));
        assertThrows(IllegalArgumentException.class, () -> SqlStatementClassifier.assertReadOnlyQuery(
                ProductTypeEnum.POSTGRESQL, "SELECT 1; SELECT 2"));
    }

    @Test
    void rejectsSelectIntoAndForUpdate() {
        assertEquals(SqlStatementClassifier.Kind.DML, SqlStatementClassifier.classify(
                ProductTypeEnum.POSTGRESQL, "SELECT * FROM orders FOR UPDATE").kind());
        assertThrows(IllegalArgumentException.class, () -> SqlStatementClassifier.assertReadOnlyQuery(
                ProductTypeEnum.MYSQL, "SELECT id FROM orders FOR UPDATE"));
    }

    @Test
    void failsClosedForUnverifiedProductAndEmptySql() {
        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> SqlStatementClassifier.classify(ProductTypeEnum.SQLSERVER2000, "SELECT 1 FROM dual"));
        assertTrue(error.getMessage().contains("尚未验证"));
        assertThrows(IllegalArgumentException.class, () -> SqlStatementClassifier.classify(
                ProductTypeEnum.POSTGRESQL, "   "));
    }
}

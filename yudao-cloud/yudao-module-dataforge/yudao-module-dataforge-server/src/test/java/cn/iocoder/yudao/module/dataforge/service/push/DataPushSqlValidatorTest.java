package cn.iocoder.yudao.module.dataforge.service.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自定义 SQL 仅允许单条 SELECT / WITH
 */
class DataPushSqlValidatorTest {

    @Test
    @DisplayName("空 SQL 拒绝")
    void blankRejected() {
        assertThrows(IllegalArgumentException.class, () -> DataPushSqlValidator.validateSelect(""));
        assertThrows(IllegalArgumentException.class, () -> DataPushSqlValidator.validateSelect("   "));
        assertThrows(IllegalArgumentException.class, () -> DataPushSqlValidator.validateSelect(null));
    }

    @Test
    @DisplayName("单条 SELECT 通过，去掉末尾分号")
    void singleSelectAccepted() {
        assertEquals("SELECT id, name FROM t",
                DataPushSqlValidator.validateSelect("SELECT id, name FROM t;"));
    }

    @Test
    @DisplayName("WITH 查询通过")
    void withSelectAccepted() {
        String sql = "WITH cte AS (SELECT 1 AS id) SELECT id FROM cte";
        assertEquals(sql, DataPushSqlValidator.validateSelect(sql));
    }

    @Test
    @DisplayName("多语句拒绝")
    void multiStatementRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> DataPushSqlValidator.validateSelect("SELECT 1; SELECT 2"));
        assertTrue(ex.getMessage().contains("单条"));
    }

    @Test
    @DisplayName("写操作与 DDL 拒绝")
    void dmlAndDdlRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> DataPushSqlValidator.validateSelect("DELETE FROM t"));
        assertThrows(IllegalArgumentException.class,
                () -> DataPushSqlValidator.validateSelect("INSERT INTO t VALUES (1)"));
        assertThrows(IllegalArgumentException.class,
                () -> DataPushSqlValidator.validateSelect("SELECT * INTO t2 FROM t"));
        assertThrows(IllegalArgumentException.class,
                () -> DataPushSqlValidator.validateSelect("DROP TABLE t"));
    }

    @Test
    @DisplayName("字面量和注释里的关键字不误拒")
    void keywordsInLiteralsAllowed() {
        assertEquals("SELECT id FROM t WHERE note LIKE '%DELETE%'",
                DataPushSqlValidator.validateSelect("SELECT id FROM t WHERE note LIKE '%DELETE%'"));
        assertEquals("SELECT id FROM t WHERE remark = 'a; b'",
                DataPushSqlValidator.validateSelect("SELECT id FROM t WHERE remark = 'a; b'"));
        assertEquals("SELECT id FROM t -- DELETE FROM t",
                DataPushSqlValidator.validateSelect("SELECT id FROM t -- DELETE FROM t"));
    }

    @Test
    @DisplayName("去掉字面量后仍能发现多语句")
    void semicolonOutsideLiteralRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> DataPushSqlValidator.validateSelect("SELECT 1; DELETE FROM t"));
    }

    @Test
    @DisplayName("美元引号内的关键字不误拒，引号外的写操作仍拒绝")
    void dollarQuoteStripped() {
        assertEquals("SELECT id FROM t WHERE note = $$DELETE FROM t$$",
                DataPushSqlValidator.validateSelect("SELECT id FROM t WHERE note = $$DELETE FROM t$$"));
        assertThrows(IllegalArgumentException.class,
                () -> DataPushSqlValidator.validateSelect("WITH x AS (SELECT 1 AS id) COPY t FROM x"));
        assertThrows(IllegalArgumentException.class,
                () -> DataPushSqlValidator.validateSelect("WITH x AS (SELECT 1) CALL foo()"));
    }

}

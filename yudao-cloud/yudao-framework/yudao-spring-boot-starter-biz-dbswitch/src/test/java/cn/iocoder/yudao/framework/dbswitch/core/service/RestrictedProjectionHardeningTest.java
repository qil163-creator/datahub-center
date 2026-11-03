package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #17 受限投影加固（DB-less）：无需真实数据库即可证明越权载体在授权前被拒绝。
 * <p>
 * 覆盖交接文档第 6 节 Step 2 收紧项：注释/优化器提示、会话或系统变量与 PostgreSQL 美元引用字符串、
 * 含单引号或反斜杠的字符串字面值（其跨会话模式重渲染无法证明安全）、查询修饰/锁子句/表采样/分区/索引提示。
 * 真实库差异测试（{@link RestrictedProjectionPostgreSqlTest} 等）在多会话模式下补充执行级证据。
 */
class RestrictedProjectionHardeningTest {

    private static final List<String> PHYSICAL = List.of("id", "name", "secret");
    private static final Set<String> ALLOWED = Set.of("id", "name");
    private static final String Q = "'";
    private static final String B = "\\";

    private static void assertRejected(ProductTypeEnum product, String table, String sql) {
        assertThrows(IllegalArgumentException.class, () -> RestrictedSelectGuard.restrictProjection(
                product, sql, "public", "citizen", PHYSICAL, ALLOWED), sql);
    }

    private static void assertRejectedPg(String sql) {
        assertRejected(ProductTypeEnum.POSTGRESQL, "\"public\".\"citizen\"", sql);
    }

    private static void assertRejectedMysql(String sql) {
        assertRejected(ProductTypeEnum.MYSQL, "`public`.`citizen`", sql);
    }

    // —— 字符串字面值：任何内嵌单引号或反斜杠都拒绝（跨会话模式重渲染语义不可证明安全）——

    @Test
    void backslashInStringLiteralIsRejected() {
        // 已在真实库确认：PG standard_conforming_strings=off 时该反斜杠可提前闭合字符串并植入 secret 谓词。
        String pg = "select id, name from \"public\".\"citizen\" where name = " + Q + "x" + B + Q
                + " or secret is not null --" + Q;
        assertRejectedPg(pg);
        String mysql = "select id, name from `public`.`citizen` where name = " + Q + "x" + B + Q
                + " or secret is not null -- " + Q;
        assertRejectedMysql(mysql);
    }

    @Test
    void doubledBackslashInStringLiteralIsRejected() {
        assertRejectedPg("select id, name from \"public\".\"citizen\" where name = " + Q + "x" + B + B + Q
                + " or secret is not null or name = " + Q);
    }

    @Test
    void doubledQuoteInStringLiteralIsRejected() {
        assertRejectedPg("select id, name from \"public\".\"citizen\" where name = " + Q + "x" + Q + Q
                + " or secret is not null or name = " + Q);
    }

    @Test
    void postgresEscapeStringWithBackslashIsRejected() {
        assertRejectedPg("select id, name from \"public\".\"citizen\" where name = E" + Q + "x" + B + Q
                + " or secret is not null --" + Q);
    }

    // —— 注释：任意行/块/# 注释、MySQL 可执行注释与优化器提示一律拒绝 ——

    @Test
    void lineCommentIsRejected() {
        assertRejectedPg("select id, name from \"public\".\"citizen\" where name = " + Q + "x" + Q
                + " -- or secret is not null");
    }

    @Test
    void blockCommentIsRejected() {
        assertRejectedPg("select id, name from \"public\".\"citizen\" where name = " + Q + "x" + Q
                + " /* or secret is not null */");
    }

    @Test
    void mysqlHashCommentIsRejected() {
        assertRejectedMysql("select id, name from `public`.`citizen` where name = " + Q + "x" + Q
                + " # or secret is not null");
    }

    @Test
    void mysqlExecutableCommentIsRejected() {
        assertRejectedMysql("select id /*!50000 , secret */ from `public`.`citizen`");
    }

    @Test
    void optimizerHintIsRejected() {
        assertRejectedPg("select /*+ no_index(t) */ id from \"public\".\"citizen\"");
        assertRejectedMysql("select /*+ no_index(t) */ id from `public`.`citizen`");
    }

    // —— 变量：会话/系统变量与 PostgreSQL 美元引用字符串拒绝，仅保留 ? 与 $n 占位符 ——

    @Test
    void sessionAndSystemVariablesAreRejected() {
        assertRejectedMysql("select id, name from `public`.`citizen` where name = @@version");
        assertRejectedMysql("select id, name from `public`.`citizen` where name = @x");
    }

    @Test
    void postgresDollarQuotedStringIsRejected() {
        assertRejectedPg("select id, name from \"public\".\"citizen\" where name = $$x$$");
    }

    // —— 查询修饰、锁子句、表采样、分区、索引提示 ——

    @Test
    void postgresForShareIsRejected() {
        assertRejectedPg("select id from \"public\".\"citizen\" for share");
    }

    @Test
    void postgresTableSampleIsRejected() {
        assertRejectedPg("select id from \"public\".\"citizen\" tablesample system(50)");
    }

    @Test
    void mysqlLockAndModifierClausesAreRejected() {
        assertRejectedMysql("select id from `public`.`citizen` lock in share mode");
        assertRejectedMysql("select sql_calc_found_rows id from `public`.`citizen`");
        assertRejectedMysql("select high_priority id from `public`.`citizen`");
        assertRejectedMysql("select straight_join id from `public`.`citizen`");
    }

    @Test
    void mysqlIndexHintIsRejected() {
        assertRejectedMysql("select id from `public`.`citizen` use index (primary)");
    }

    // —— AST 层兜底：即便无词法载体，未授权列仍被列/排序检查拒绝 ——

    @Test
    void plainUnapprovedPredicateAndOrderAreRejected() {
        assertRejectedPg("select id, name from \"public\".\"citizen\" where name = " + Q + "x" + Q
                + " or secret is not null");
        assertRejectedPg("select id from \"public\".\"citizen\" order by secret");
        // PG 下 "secret" 是带引号的列标识符，等值比较即触达未授权列。
        assertRejectedPg("select id, name from \"public\".\"citizen\" where name = \"secret\"");
    }

    @Test
    void selectIntoGivesClearChineseError() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> RestrictedSelectGuard.parse(ProductTypeEnum.POSTGRESQL,
                        "select id into other from citizen"));
        assertTrue(ex.getMessage().contains("INTO"), ex.getMessage());
    }

    // —— 正向对照：合法字面值、占位符、过滤与排序在收紧后仍被接受 ——

    @Test
    void benignLiteralPlaceholderFilterAndOrderRemainAccepted() {
        assertDoesNotThrow(() -> RestrictedSelectGuard.restrictProjection(ProductTypeEnum.POSTGRESQL,
                "select id, name from \"public\".\"citizen\" where name = 'zhang' and id > $1 order by name desc",
                "public", "citizen", PHYSICAL, ALLOWED));
        assertDoesNotThrow(() -> RestrictedSelectGuard.restrictProjection(ProductTypeEnum.MYSQL,
                "select id, name from `public`.`citizen` where name = 'zhang' and id > ? order by name desc",
                "public", "citizen", PHYSICAL, ALLOWED));
    }

    @Test
    void limitOffsetFetchArePreserved() {
        assertDoesNotThrow(() -> RestrictedSelectGuard.restrictProjection(ProductTypeEnum.POSTGRESQL,
                "select id, name from \"public\".\"citizen\" order by id limit 10 offset 5",
                "public", "citizen", PHYSICAL, ALLOWED));
        assertDoesNotThrow(() -> RestrictedSelectGuard.restrictProjection(ProductTypeEnum.MYSQL,
                "select id, name from `public`.`citizen` order by id limit 10 offset 5",
                "public", "citizen", PHYSICAL, ALLOWED));
    }
}

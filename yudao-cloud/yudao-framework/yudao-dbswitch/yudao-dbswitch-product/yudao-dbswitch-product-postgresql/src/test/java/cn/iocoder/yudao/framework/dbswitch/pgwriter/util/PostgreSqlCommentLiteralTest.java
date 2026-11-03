package cn.iocoder.yudao.framework.dbswitch.pgwriter.util;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PostgreSqlCommentLiteralTest {
    @Test void commentsEscapeNamesAndValuesIndependently() {
        var table = new TableDescription();
        table.setSchemaName("sch\"ema"); table.setTableName("ta'ble");
        table.setRemarks("owner's \"name\" \\path\nline");
        var column = new ColumnDescription();
        column.setFieldName("co\"lumn"); column.setRemarks("'); DROP TABLE sentinel; --");
        assertEquals(List.of(
                "COMMENT ON TABLE \"sch\"\"ema\".\"ta'ble\" IS E'owner''s \"name\" \\\\path\nline' ",
                "COMMENT ON COLUMN \"sch\"\"ema\".\"ta'ble\".\"co\"\"lumn\" IS E'''); DROP TABLE sentinel; --' "),
                new DatabasePostgresImpl().getTableColumnCommentDefinition(table, List.of(column)));
    }

    @Test void nulCannotReachTheSqlParserAndBlankCommentsStillProduceNoStatements() {
        var table = new TableDescription(); table.setSchemaName("public"); table.setTableName("sample");
        var column = new ColumnDescription(); column.setFieldName("note"); column.setRemarks("  ");
        assertTrue(new DatabasePostgresImpl().getTableColumnCommentDefinition(table, List.of(column)).isEmpty());
        table.setRemarks("prefix\0suffix");
        var failure = assertThrows(IllegalArgumentException.class,
                () -> new DatabasePostgresImpl().getTableColumnCommentDefinition(table, List.of(column)));
        assertFalse(failure.getMessage().contains("prefix"));
    }
}

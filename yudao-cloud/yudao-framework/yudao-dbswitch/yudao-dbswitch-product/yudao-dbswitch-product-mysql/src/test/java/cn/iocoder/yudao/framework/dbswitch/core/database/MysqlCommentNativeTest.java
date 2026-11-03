package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMysqlImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.DriverManager;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MysqlCommentNativeTest {
    @Test
    void modeDependentCharactersCannotBeEmittedByEitherDdlPath() {
        var dialect = new DatabaseMysqlImpl();
        assertThrows(IllegalArgumentException.class, () -> ddl(dialect, "not_created", "bad\\path", "plain"));
        assertThrows(IllegalArgumentException.class, () -> ddl(dialect, "not_created", "plain", "bad\\path"));
        assertThrows(IllegalArgumentException.class, () -> GenerateSqlUtils.quoteMysqlComment("bad\0text", 1024));
        assertThrows(IllegalArgumentException.class, () -> GenerateSqlUtils.quoteMysqlComment("a".repeat(1025), 1024));
        assertThrows(IllegalArgumentException.class, () -> GenerateSqlUtils.quoteMysqlComment("emoji😀", 1024));
    }

    @ParameterizedTest
    @ValueSource(strings = {"STRICT_ALL_TABLES", "STRICT_ALL_TABLES,ANSI_QUOTES,NO_BACKSLASH_ESCAPES"})
    @EnabledIfSystemProperty(named = "datahub.test.mysql.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/dbswitch_test.*")
    void commentsRoundTripUnderBothEscapeModes(String mode) throws Exception {
        String name = "comments88_" + UUID.randomUUID().toString().replace("-", "");
        String tableText = "中文 O'Reilly \"table\"\n'; DROP TABLE other; --";
        String columnText = "字段 'value' \"quoted\" 中文";
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.mysql.url"),
                System.getProperty("datahub.test.mysql.user", "root"), System.getProperty("datahub.test.mysql.password", ""));
             var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.execute("SET SESSION sql_mode='" + mode + "'");
            try {
                statement.execute(ddl(new DatabaseMysqlImpl(), name, columnText, tableText));
                try (var query = connection.prepareStatement("SELECT TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA='dbswitch_test' AND TABLE_NAME=?")) {
                    query.setString(1, name);
                    try (var result = query.executeQuery()) { assertTrue(result.next()); assertEquals(tableText, result.getString(1)); assertFalse(result.next()); }
                }
                try (var query = connection.prepareStatement("SELECT COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='dbswitch_test' AND TABLE_NAME=? AND COLUMN_NAME='id'")) {
                    query.setString(1, name);
                    try (var result = query.executeQuery()) { assertTrue(result.next()); assertEquals(columnText, result.getString(1)); assertFalse(result.next()); }
                }
            } finally { statement.execute("DROP TABLE IF EXISTS dbswitch_test.`" + name + "`"); }
        }
    }

    private static String ddl(DatabaseMysqlImpl dialect, String name, String columnComment, String tableComment) {
        var column = new ColumnDescription();
        column.setFieldName("id"); column.setFieldType(Types.INTEGER); column.setPrecisionSize(9);
        column.setScaleSize(0); column.setNullable(true); column.setRemarks(columnComment);
        return GenerateSqlUtils.getDDLCreateTableSQL(dialect, List.of(column), List.of(), "dbswitch_test", name,
                true, tableComment, false, Map.of());
    }
}

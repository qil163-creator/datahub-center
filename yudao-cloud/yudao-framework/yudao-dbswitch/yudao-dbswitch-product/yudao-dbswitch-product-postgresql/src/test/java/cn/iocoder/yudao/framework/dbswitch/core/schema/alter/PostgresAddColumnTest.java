package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabasePostgresImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import org.junit.jupiter.api.Test;

import java.sql.SQLFeatureNotSupportedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PostgresAddColumnTest {
    @Test
    void nullableColumnIsQuoted() {
        var column = column();
        String sql = new DatabasePostgresImpl().getAddColumnSql("app", "sample", column);
        assertEquals("ALTER TABLE \"app\".\"sample\" ADD COLUMN \"note\"   VARCHAR(20)", sql);
    }

    @Test
    void notNullPrimaryKeyAndDefaultAreRejected() {
        var required = column();
        required.setNullable(false);
        assertRejected(required);
        var key = column();
        key.setPk(true);
        assertRejected(key);
        var withDefault = column();
        withDefault.setDefaultValue("x");
        assertRejected(withDefault);
    }

    private static void assertRejected(ColumnDescription column) {
        SQLFeatureNotSupportedException failure = assertThrows(SQLFeatureNotSupportedException.class,
                () -> new DatabasePostgresImpl().getAddColumnSql("app", "sample", column));
        assertEquals("0A000", failure.getSQLState());
        assertFalse(failure.getMessage().contains("ALTER TABLE"));
    }

    private static ColumnDescription column() {
        var column = new ColumnDescription();
        column.setFieldName("note");
        column.setValType(ColumnMetaData.TYPE_STRING);
        column.setLength(20);
        column.setNullable(true);
        return column;
    }
}

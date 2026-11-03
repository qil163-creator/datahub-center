package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseHiveImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import org.junit.jupiter.api.Test;

import java.sql.SQLFeatureNotSupportedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParentAddColumnRejectedTest {
    @Test
    void subclassWithoutItsOwnAddDoesNotSpliceNames() {
        var dialect = new DatabaseHiveImpl() {
        };
        var column = new ColumnDescription();
        column.setFieldName("note");
        SQLFeatureNotSupportedException failure = assertThrows(SQLFeatureNotSupportedException.class,
                () -> dialect.getAddColumnSql("app", "items", column));
        assertEquals("0A000", failure.getSQLState());
    }
}

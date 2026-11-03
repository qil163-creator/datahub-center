package cn.iocoder.yudao.framework.dbswitch.core.util;

import cn.iocoder.yudao.framework.dbswitch.dbcommon.database.impl.OracleDatabaseOperator;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOracleImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OracleSqlNamesTest {
    @Test void commentsPreserveQuotesBackslashesAndExactNames() {
        var table=new TableDescription();table.setSchemaName("owner");table.setTableName("table'; name");table.setRemarks("a'\\b\"c");
        var column=new ColumnDescription();column.setFieldName("note'; field");column.setRemarks("d'\\e\"f");
        assertEquals(List.of("COMMENT ON TABLE \"owner\".\"table'; name\" IS 'a''\\b\"c'",
                "COMMENT ON COLUMN \"owner\".\"table'; name\".\"note'; field\" IS 'd''\\e\"f'"),
                new DatabaseOracleImpl().getTableColumnCommentDefinition(table,List.of(column)));
    }
    @Test void legalQuotedNamesRetainPunctuationAsOneIdentifier() {
        assertEquals("\"note'; value\"",OracleSqlNames.quote("note'; value"));
        var source=mock(DataSource.class);var operator=new OracleDatabaseOperator(source);
        assertEquals("SELECT \"field'; value\" FROM \"owner\".\"table; name\"",
                operator.getSelectTableSql("owner","table; name",List.of("field'; value")));
        verifyNoInteractions(source);
    }
    @Test void oracleForbiddenDoubleQuotesAndOversizedUtf8AreRejectedBeforeJdbc() {
        var source=mock(DataSource.class);var operator=new OracleDatabaseOperator(source);
        assertThrows(IllegalArgumentException.class,()->operator.truncateTableData("owner","table\"name"));
        assertThrows(IllegalArgumentException.class,()->operator.dropTable("owner","table\0name"));
        assertThrows(IllegalArgumentException.class,()->operator.queryTableData("owner","table",List.of("bad\"field")));
        assertThrows(IllegalArgumentException.class,()->OracleSqlNames.quote("名".repeat(43)));
        verifyNoInteractions(source);
    }
}

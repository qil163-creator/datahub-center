package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMysqlCompatibleOlapImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import org.junit.jupiter.api.Test;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class OlapLegacyCommentTest {
    private final DatabaseMysqlCompatibleOlapImpl database = new DatabaseMysqlCompatibleOlapImpl("fixture.driver") {
        @Override public ProductTypeEnum getDatabaseType() { return ProductTypeEnum.DORIS; }
    };

    @Test void sessionFreeDdlRejectsModeDependentColumnAndTableComments() {
        for (String comment : List.of("O'Reilly", "path\\name", "\\'; DROP TABLE t; --", "nul\0value")) {
            assertThrows(IllegalArgumentException.class, () -> database.getFieldDefinition(column(comment), List.of(), false, false, true));
            assertThrows(IllegalArgumentException.class, () -> database.postAppendCreateTableSql(new StringBuilder(), comment, List.of("id"), Map.of(), "id"));
        }
        assertTrue(database.getFieldDefinition(column("中文 \"double\""), List.of(), false, false, true).contains("COMMENT '中文 \"double\"'"));
    }

    private static ColumnMetaData column(String remarks) {
        var column = new ColumnDescription(); column.setFieldName("id"); column.setFieldType(Types.INTEGER);
        column.setPrecisionSize(9); column.setRemarks(remarks); return new ColumnMetaData(column);
    }
}

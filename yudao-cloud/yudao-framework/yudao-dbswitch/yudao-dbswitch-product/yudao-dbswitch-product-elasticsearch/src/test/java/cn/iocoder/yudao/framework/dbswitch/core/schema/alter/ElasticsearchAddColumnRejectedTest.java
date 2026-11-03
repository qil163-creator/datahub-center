package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseElasticsearchImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import org.junit.jupiter.api.Test;

import java.sql.SQLFeatureNotSupportedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** No Elasticsearch connection. A pass does not certify that a server accepts DDL. */
class ElasticsearchAddColumnRejectedTest {
    private final DatabaseElasticsearchImpl database = new DatabaseElasticsearchImpl();

    @Test
    void addColumnRefusesBeforeAnyAlterText() {
        ColumnDescription column = column("note");
        column.setNullable(false);
        column.setPk(true);
        column.setDefaultValue("x");
        SQLFeatureNotSupportedException failure = assertRejected("app", "logs-2026", column);
        assertFalse(failure.getMessage().contains("ALTER TABLE"));
        assertFalse(failure.getMessage().contains("app"));
        assertFalse(failure.getMessage().contains("logs-2026"));
        assertFalse(failure.getMessage().contains("note"));
    }

    @Test
    void hostileNamesAndNullArgumentsAreNotSpliced() {
        assertRejected("es; DROP TABLE x", "a.b", column("c\"d"));
        assertRejected(null, null, null);
    }

    @Test
    void manifestKeepsAlterUnsupported() {
        var elasticsearch = DbSwitchProductRegistry.discover().products().stream()
                .filter(product -> product.product() == ProductTypeEnum.ELASTICSEARCH)
                .findFirst().orElseThrow();
        assertEquals(new OperationCapability(OperationCapability.Status.UNSUPPORTED, "JSON_PARSE_EXCEPTION"),
                elasticsearch.capabilities().get(DatabaseOperation.DDL_ALTER));
    }

    private SQLFeatureNotSupportedException assertRejected(String schema, String table, ColumnDescription column) {
        SQLFeatureNotSupportedException failure = assertThrows(SQLFeatureNotSupportedException.class,
                () -> database.getAddColumnSql(schema, table, column));
        assertEquals("0A000", failure.getSQLState());
        assertFalse(String.valueOf(failure.getMessage()).contains("ALTER TABLE"));
        return failure;
    }

    private static ColumnDescription column(String name) {
        ColumnDescription description = new ColumnDescription();
        description.setFieldName(name);
        description.setDbType(ProductTypeEnum.ELASTICSEARCH);
        description.setValType(ColumnMetaData.TYPE_STRING);
        description.setLength(20);
        description.setNullable(true);
        return description;
    }
}

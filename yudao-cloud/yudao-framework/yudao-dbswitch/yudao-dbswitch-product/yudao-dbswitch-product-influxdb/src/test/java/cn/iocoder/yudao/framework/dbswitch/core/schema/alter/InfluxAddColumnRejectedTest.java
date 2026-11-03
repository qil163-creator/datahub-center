package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseInfluxdbImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.sql.SQLFeatureNotSupportedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** No InfluxDB connection. Refusal is not a live ALTER proof. */
class InfluxAddColumnRejectedTest {
    private static final String MANIFEST = "META-INF/dbswitch/products/influxdb3.json";
    private static final String UNVERIFIED = "InfluxDB 3 add column is unverified";

    @Test
    void addColumnRejectsBeforeAnySql() throws Exception {
        DatabaseInfluxdbImpl database = new DatabaseInfluxdbImpl();
        SQLFeatureNotSupportedException nullable = assertThrows(SQLFeatureNotSupportedException.class,
                () -> database.getAddColumnSql("wave19_schema", "wave19_table", column(true)));
        assertRejected(nullable);

        ColumnDescription required = column(false);
        SQLFeatureNotSupportedException notNull = assertThrows(SQLFeatureNotSupportedException.class,
                () -> database.getAddColumnSql("wave19_schema", "wave19_table", required));
        assertEquals(UNVERIFIED, notNull.getMessage());
        assertEquals("0A000", notNull.getSQLState());

        SQLFeatureNotSupportedException bare = assertThrows(SQLFeatureNotSupportedException.class,
                () -> database.getAddColumnSql("iox.evil", "a\"b", null));
        assertEquals(UNVERIFIED, bare.getMessage());
        assertEquals("0A000", bare.getSQLState());
        assertFalse(bare.getMessage().contains("iox.evil"));
        assertFalse(bare.getMessage().contains("a\"b"));

        var influx = DbSwitchProductRegistry.discover().products().stream()
                .filter(product -> product.product() == ProductTypeEnum.INFLUXDB3)
                .findFirst().orElseThrow();
        assertEquals(new OperationCapability(OperationCapability.Status.UNSUPPORTED, "OPERATION_NOT_IMPLEMENTED"),
                influx.capabilities().get(DatabaseOperation.DDL_ALTER));
        try (var input = getClass().getClassLoader().getResourceAsStream(MANIFEST)) {
            assertNotNull(input, MANIFEST);
            var declared = new ObjectMapper().readTree(input).path("operations").path("DDL_ALTER");
            assertEquals("UNSUPPORTED", declared.path("status").asText());
            assertEquals("OPERATION_NOT_IMPLEMENTED", declared.path("reason").asText());
        }
    }

    private static void assertRejected(SQLFeatureNotSupportedException failure) {
        assertEquals("0A000", failure.getSQLState());
        assertEquals(UNVERIFIED, failure.getMessage());
        assertFalse(failure.getMessage().toUpperCase(java.util.Locale.ROOT).contains("ALTER"));
        assertFalse(failure.getMessage().contains("wave19_schema"));
        assertFalse(failure.getMessage().contains("wave19_table"));
        assertFalse(failure.getMessage().contains("wave19_column"));
    }

    private static ColumnDescription column(boolean nullable) {
        ColumnDescription description = new ColumnDescription();
        description.setFieldName("wave19_column");
        description.setDbType(ProductTypeEnum.INFLUXDB3);
        description.setValType(ColumnMetaData.TYPE_STRING);
        description.setLength(20);
        description.setNullable(nullable);
        return description;
    }
}

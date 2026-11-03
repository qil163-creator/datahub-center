package cn.iocoder.yudao.framework.dbswitch.core.schema.catalog;

import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.product.influxdb3.InfluxDb3CatalogColumns;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** DB-less. Reads the catalog SQL constant only and does not open Flight SQL. */
@Timeout(60)
class InfluxDb3CatalogColumnsTest {

    @Test
    void columnSqlUsesDataTypeAndOctetLengthNotMysqlGeneration() {
        String sql = InfluxDb3CatalogColumns.COLUMNS;
        assertTrue(sql.contains("data_type"));
        assertTrue(sql.contains("character_octet_length"));
        assertFalse(sql.contains("GENERATION_EXPRESSION"));
        assertFalse(sql.contains("character_maximum_length"));
        assertFalse(sql.contains("system.influxdb_schema"));
        assertTrue(sql.contains("information_schema.columns"));
        assertEquals("iox", InfluxDb3CatalogColumns.SCHEMA);
        assertEquals(List.of(), InfluxDb3CatalogColumns.primaryKeys());
        assertEquals(MetadataValue.unknown(), InfluxDb3CatalogColumns.additionalObjects());
        assertFalse(InfluxDb3CatalogColumns.additionalObjects().known());
    }

    @Test void unboundFlightCatalogSqlIsFixedBoundedAndRejectsNonordinaryNames() {
        String sql = InfluxDb3CatalogColumns.columns("iox", "Sample_1");
        assertTrue(sql.endsWith("WHERE table_schema = 'iox' AND table_name = 'Sample_1' ORDER BY ordinal_position LIMIT 2001"));
        assertFalse(sql.contains("?"));
        for (String table : List.of("", "a'b", "a\\b", "a\"b", "a;DROP TABLE x", "a\n", "a".repeat(64)))
            assertThrows(IllegalArgumentException.class, () -> InfluxDb3CatalogColumns.columns("iox", table));
        assertThrows(IllegalArgumentException.class, () -> InfluxDb3CatalogColumns.columns("database", "sample"));
        assertThrows(IllegalArgumentException.class, () -> InfluxDb3CatalogColumns.columns("iox", null));
    }
}

package cn.iocoder.yudao.framework.dbswitch.core.schema.catalog;

import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeFidelity;
import cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineCatalogColumns;
import cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineLogicalTypeMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDengine 3.3.8.8 列目录。无库测试只锁语句和 DESCRIBE 规则。
 * 实库测试在 {@code datahub.test.tdengine.url} 指向本机 WebSocket 时才运行。
 */
@Timeout(120)
class TdengineCatalogColumnsTest {
    private static final TdengineLogicalTypeMapper MAPPER = new TdengineLogicalTypeMapper();

    @Test
    void catalogSqlIsDescribeNotMysqlOrInsColumnsLength() throws Exception {
        List<String> sql = new ArrayList<>();
        for (Field field : TdengineCatalogColumns.class.getDeclaredFields()) {
            if (field.getType() == String.class) {
                field.setAccessible(true);
                sql.add((String) field.get(null));
            }
        }
        assertEquals(List.of(TdengineCatalogColumns.VERSION_SQL, TdengineCatalogColumns.DESCRIBE_SQL,
                TdengineCatalogColumns.PRECISION_SQL), sql);
        String text = String.join("\n", sql);
        assertTrue(text.contains("SELECT SERVER_VERSION()"));
        assertTrue(text.contains("DESCRIBE "));
        assertTrue(text.contains("information_schema.ins_databases"));
        assertTrue(text.contains("`precision`"));
        assertFalse(text.toLowerCase(Locale.ROOT).contains("col_length"));
        assertFalse(text.toLowerCase(Locale.ROOT).contains("col_precision"));
        assertFalse(text.toLowerCase(Locale.ROOT).contains("col_scale"));
        assertFalse(text.toLowerCase(Locale.ROOT).contains("col_nullable"));
        assertFalse(text.toLowerCase(Locale.ROOT).contains("ins_columns"));
        assertFalse(text.contains("MYSQL_COLUMNS"));
        assertFalse(text.contains("information_schema.COLUMNS"));
        assertFalse(text.contains("GENERATION_EXPRESSION"));
    }

    @Test
    void missingTimestampPrecisionStaysAbsent() {
        assertEquals(3, TdengineCatalogColumns.timestampDigits("ms"));
        assertEquals(6, TdengineCatalogColumns.timestampDigits("us"));
        assertEquals(9, TdengineCatalogColumns.timestampDigits("ns"));
        assertNull(TdengineCatalogColumns.timestampDigits(null));
        assertNull(TdengineCatalogColumns.timestampDigits(""));
        assertNull(TdengineCatalogColumns.timestampDigits("MS"));
        assertEquals(MetadataValue.absent(), TdengineCatalogColumns.timestampScale(null));
        assertEquals(MetadataValue.absent(), TdengineCatalogColumns.timestampScale("sec"));
        assertEquals(MetadataValue.value(3), TdengineCatalogColumns.timestampScale("ms"));
        assertEquals(MetadataValue.value(6), TdengineCatalogColumns.timestampScale("us"));
        assertEquals(MetadataValue.value(9), TdengineCatalogColumns.timestampScale("ns"));
        assertNull(TdengineCatalogColumns.timestampScale(null).value());
    }

    @Test
    void describeLengthIsNotRewrittenFromAnotherCatalog() {
        // The server stores INTEGER as INT and BINARY(n) as VARCHAR(n). This helper reports the DESCRIBE token.
        assertEquals("INTEGER", TdengineCatalogColumns.nativeType("INTEGER", 4));
        assertEquals("INT", TdengineCatalogColumns.nativeType("INT", 4));
        assertEquals("INT UNSIGNED", TdengineCatalogColumns.nativeType("INT UNSIGNED", 4));
        assertEquals("BINARY(10)", TdengineCatalogColumns.nativeType("BINARY", 10));
        assertEquals("VARCHAR(10)", TdengineCatalogColumns.nativeType("VARCHAR", 10));
        assertEquals("NCHAR(10)", TdengineCatalogColumns.nativeType("NCHAR", 10));
        assertEquals("VARBINARY(10)", TdengineCatalogColumns.nativeType("VARBINARY", 10));
        assertEquals("DECIMAL(10,2)", TdengineCatalogColumns.nativeType("DECIMAL(10, 2)", 16));
        assertEquals(MetadataValue.value(10L), TdengineCatalogColumns.characterLength("VARCHAR(10)", 10));
        assertEquals(MetadataValue.value(10L), TdengineCatalogColumns.characterLength("NCHAR(10)", 10));
        assertEquals(MetadataValue.absent(), TdengineCatalogColumns.characterLength("INT", 4));
        assertEquals(MetadataValue.absent(), TdengineCatalogColumns.characterLength("TIMESTAMP", 8));
        assertEquals(MetadataValue.unknown(), TdengineCatalogColumns.nullable(ResultSetMetaData.columnNullableUnknown));
        assertEquals(MetadataValue.value(false), TdengineCatalogColumns.nullable(ResultSetMetaData.columnNoNulls));
        assertEquals(MetadataValue.value(true), TdengineCatalogColumns.nullable(ResultSetMetaData.columnNullable));
        assertNull(TdengineCatalogColumns.nullable(ResultSetMetaData.columnNullableUnknown).value());
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.tdengine.url", matches = "jdbc:TAOS-WS://127[.]0[.]0[.]1:[0-9]+/.*")
    void liveCatalogUsesDescribeLengthAndSkipsTags() throws Exception {
        String password = System.getenv("DATAHUB_TDENGINE_TEST_PASSWORD");
        assertNotNull(password, "DATAHUB_TDENGINE_TEST_PASSWORD is required");
        String base = "w" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Class.forName("com.taosdata.jdbc.ws.WebSocketDriver");
        try (Connection connection = DriverManager.getConnection(System.getProperty("datahub.test.tdengine.url"), "root", password)) {
            List<String> databases = List.of(base + "ms", base + "us", base + "ns");
            try (Statement statement = connection.createStatement()) {
                for (String database : databases) statement.execute("DROP DATABASE IF EXISTS " + database);
                statement.execute("CREATE DATABASE " + databases.get(0) + " PRECISION 'ms' VGROUPS 1 BUFFER 8");
                statement.execute("CREATE DATABASE " + databases.get(1) + " PRECISION 'us' VGROUPS 1 BUFFER 8");
                statement.execute("CREATE DATABASE " + databases.get(2) + " PRECISION 'ns' VGROUPS 1 BUFFER 8");
                statement.execute("CREATE TABLE " + databases.get(0) + ".probe (ts TIMESTAMP, i INTEGER, b BINARY(10),"
                        + " vc VARCHAR(10), nc NCHAR(10), vb VARBINARY(10), bi BOOL, tu TINYINT UNSIGNED,"
                        + " c DECIMAL(10,2), f FLOAT, d DOUBLE)");
                statement.execute("CREATE TABLE " + databases.get(0) + ".ck (ts TIMESTAMP, id INT COMPOSITE KEY, v INT)");
                statement.execute("CREATE STABLE " + databases.get(0) + ".s (ts TIMESTAMP, v INT) TAGS (loc NCHAR(8))");
                statement.execute("CREATE TABLE " + databases.get(0) + ".leaf USING " + databases.get(0) + ".s TAGS ('room')");
                statement.execute("CREATE STABLE " + databases.get(0) + ".j (ts TIMESTAMP, v INT) TAGS (info JSON)");
                statement.execute("CREATE TABLE " + databases.get(0) + ".jc USING " + databases.get(0) + ".j TAGS ('{\"a\":1}')");
                assertThrows(SQLException.class, () -> statement.execute(
                        "CREATE TABLE " + databases.get(0) + ".bad (v INT, ts TIMESTAMP)"));
                assertThrows(SQLException.class, () -> statement.execute(
                        "CREATE TABLE " + databases.get(0) + ".badj (ts TIMESTAMP, j JSON)"));
                assertThrows(SQLException.class, () -> statement.execute(
                        "INSERT INTO " + databases.get(0) + ".probe (ts) VALUES (NULL)"));
                try (ResultSet rows = statement.executeQuery("SELECT SERVER_VERSION()")) {
                    assertTrue(rows.next());
                    assertTrue(rows.getString(1).matches("3\\.3\\.8\\.[0-9]+"));
                    assertFalse(rows.next());
                }
            }
            TableSchemaMetadata probe = TdengineCatalogColumns.read(connection, databases.get(0), "probe", 30);
            assertEquals("TDENGINE_CATALOG_V1", probe.provenance().method());
            assertEquals("TDengine", probe.provenance().serverProduct());
            assertTrue(probe.provenance().serverVersion().matches("3\\.3\\.8\\.[0-9]+"));
            assertEquals(MetadataValue.value(true), probe.existence());
            assertEquals(MetadataValue.unknown(), probe.additionalObjects());
            assertFalse(probe.additionalObjects().known());
            assertEquals(List.of("ts"), probe.primaryKeys());
            assertEquals(List.of("ts", "i", "b", "vc", "nc", "vb", "bi", "tu", "c", "f", "d"), names(probe));
            // col_nullable is NULL. JDBC isNullable for the timestamp column was not stable, so evidence stays absent.
            assertOrdinary(probe, "ts", "TIMESTAMP", Types.TIMESTAMP, "TIMESTAMP", "java.sql.Timestamp", null, 3);
            assertOrdinary(probe, "i", "INT", Types.INTEGER, "INT", "java.lang.Integer", null, 0);
            assertEquals(10, column(probe, "i").getPrecisionSize());
            assertEquals(MetadataValue.value(10), column(probe, "i").getMetadataEvidence().numericPrecision());
            assertSized(probe, "b", "VARCHAR(10)", Types.VARCHAR, "BINARY", "[B", 10);
            assertSized(probe, "vc", "VARCHAR(10)", Types.VARCHAR, "BINARY", "[B", 10);
            assertSized(probe, "nc", "NCHAR(10)", Types.NCHAR, "NCHAR", "java.lang.String", 10);
            assertSized(probe, "vb", "VARBINARY(10)", Types.VARBINARY, "VARBINARY", "[B", 10);
            assertOrdinary(probe, "bi", "BOOL", Types.BOOLEAN, "BOOL", "java.lang.Boolean", null, null);
            assertEquals(MetadataValue.absent(), column(probe, "bi").getMetadataEvidence().numericPrecision());
            assertOrdinary(probe, "tu", "TINYINT UNSIGNED", Types.SMALLINT, "TINYINT UNSIGNED", "java.lang.Short", null, 0);
            assertEquals(3, column(probe, "tu").getPrecisionSize());
            assertTrue(column(probe, "tu").isSigned());
            assertOrdinary(probe, "c", "DECIMAL(10,2)", Types.DECIMAL, "DECIMAL", "java.math.BigDecimal", null, 2);
            assertEquals(10, column(probe, "c").getPrecisionSize());
            assertEquals(MetadataValue.value(10), column(probe, "c").getMetadataEvidence().numericPrecision());
            assertFalse(column(probe, "c").isSigned());
            assertOrdinary(probe, "f", "FLOAT", Types.FLOAT, "FLOAT", "java.lang.Float", null, 0);
            assertEquals(12, column(probe, "f").getPrecisionSize());
            assertOrdinary(probe, "d", "DOUBLE", Types.DOUBLE, "DOUBLE", "java.lang.Double", null, 0);
            assertEquals(22, column(probe, "d").getPrecisionSize());
            assertEquals(TypeFidelity.EXACT, MAPPER.toLogical(column(probe, "i")).fidelity());
            assertEquals(TypeFidelity.LOSSY, MAPPER.toLogical(column(probe, "f")).fidelity());
            assertStoredLengthsDifferFromInsColumns(connection, databases.get(0), "probe");
            for (int index = 0; index < databases.size(); index++) {
                String database = databases.get(index);
                int digits = List.of(3, 6, 9).get(index);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE " + database + ".clock (ts TIMESTAMP, v INT)");
                }
                TableSchemaMetadata clock = TdengineCatalogColumns.read(connection, database, "clock", 30);
                assertEquals(MetadataValue.value(digits), column(clock, "ts").getMetadataEvidence().numericScale());
                assertEquals(MetadataValue.absent(), column(clock, "ts").getMetadataEvidence().numericPrecision());
                assertEquals(0, column(clock, "ts").getScaleSize());
                assertEquals(digits == 9 ? TypeFidelity.LOSSY : TypeFidelity.EXACT, MAPPER.toLogical(column(clock, "ts")).fidelity());
            }
            TableSchemaMetadata composite = TdengineCatalogColumns.read(connection, databases.get(0), "ck", 30);
            assertEquals(List.of("ts", "id"), composite.primaryKeys());
            assertTrue(column(composite, "id").isPk());
            assertFalse(names(composite).contains("loc"));
            TableSchemaMetadata stable = TdengineCatalogColumns.read(connection, databases.get(0), "s", 30);
            assertEquals(List.of("ts", "v"), names(stable));
            assertFalse(names(stable).contains("loc"));
            TableSchemaMetadata child = TdengineCatalogColumns.read(connection, databases.get(0), "leaf", 30);
            assertEquals(List.of("ts", "v"), names(child));
            TableSchemaMetadata jsonStable = TdengineCatalogColumns.read(connection, databases.get(0), "j", 30);
            assertEquals(List.of("ts", "v"), names(jsonStable));
            assertFalse(names(jsonStable).contains("info"));
            try (Statement statement = connection.createStatement()) {
                try (ResultSet rows = statement.executeQuery("SELECT * FROM " + databases.get(0) + ".j LIMIT 0")) {
                    var labels = new ArrayList<String>();
                    for (int index = 1; index <= rows.getMetaData().getColumnCount(); index++)
                        labels.add(rows.getMetaData().getColumnLabel(index));
                    // JDBC lists the JSON tag. DESCRIBE marks it TAG, so the catalog above omits it.
                    assertTrue(labels.contains("info"));
                }
                try (ResultSet rows = statement.executeQuery("SELECT * FROM " + databases.get(0) + ".jc LIMIT 0")) {
                    var labels = new ArrayList<String>();
                    for (int index = 1; index <= rows.getMetaData().getColumnCount(); index++)
                        labels.add(rows.getMetaData().getColumnName(index));
                    assertTrue(labels.contains("ts"));
                    assertFalse(names(TdengineCatalogColumns.read(connection, databases.get(0), "jc", 30)).contains("info"));
                }
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + base + "ms");
                statement.execute("DROP DATABASE IF EXISTS " + base + "us");
                statement.execute("DROP DATABASE IF EXISTS " + base + "ns");
            }
        }
    }

    private static void assertStoredLengthsDifferFromInsColumns(Connection connection, String database, String table) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT col_name, col_length, col_precision, col_scale, col_nullable"
                     + " FROM information_schema.ins_columns WHERE db_name='" + database + "' AND table_name='" + table + "'")) {
            while (rows.next()) {
                assertNull(rows.getObject("col_precision"));
                assertNull(rows.getObject("col_scale"));
                assertNull(rows.getObject("col_nullable"));
                int stored = rows.getInt("col_length");
                String name = rows.getString("col_name");
                int expected = switch (name) {
                    case "vc", "b", "vb" -> 12;
                    case "nc" -> 42;
                    default -> stored;
                };
                assertEquals(expected, stored, name);
            }
        }
    }

    private static void assertOrdinary(TableSchemaMetadata metadata, String name, String nativeType, int jdbc,
                                       String jdbcName, String className, Integer length, Integer scale) {
        ColumnDescription column = column(metadata, name);
        assertEquals(nativeType, column.getMetadataEvidence().nativeType().value());
        assertEquals(jdbc, column.getFieldType());
        assertEquals(jdbcName, column.getFieldTypeName());
        assertEquals(className, column.getFiledTypeClassName());
        assertEquals(length == null ? MetadataValue.absent() : MetadataValue.value(length.longValue()),
                column.getMetadataEvidence().characterLength());
        assertEquals(scale == null ? MetadataValue.absent() : MetadataValue.value(scale),
                column.getMetadataEvidence().numericScale());
        assertEquals(MetadataValue.absent(), column.getMetadataEvidence().nullable());
        assertEquals(MetadataValue.absent(), column.getMetadataEvidence().charset());
        assertEquals(MetadataValue.absent(), column.getMetadataEvidence().collation());
        assertEquals(MetadataValue.absent(), column.getMetadataEvidence().identity());
        assertEquals(MetadataValue.absent(), column.getMetadataEvidence().generatedExpression());
        assertEquals(MetadataValue.absent(), column.getMetadataEvidence().defaultExpression());
    }

    private static void assertSized(TableSchemaMetadata metadata, String name, String nativeType, int jdbc,
                                    String jdbcName, String className, int length) {
        ColumnDescription column = column(metadata, name);
        assertEquals(nativeType, column.getMetadataEvidence().nativeType().value());
        assertEquals(jdbc, column.getFieldType());
        assertEquals(jdbcName, column.getFieldTypeName());
        assertEquals(className, column.getFiledTypeClassName());
        assertEquals(MetadataValue.value((long) length), column.getMetadataEvidence().characterLength());
        assertEquals(MetadataValue.absent(), column.getMetadataEvidence().numericPrecision());
        assertEquals(MetadataValue.absent(), column.getMetadataEvidence().numericScale());
        assertEquals(length, column.getPrecisionSize());
        assertEquals(MetadataValue.absent(), column.getMetadataEvidence().nullable());
    }

    private static List<String> names(TableSchemaMetadata metadata) {
        return metadata.columns().stream().map(ColumnDescription::getFieldName).toList();
    }

    private static ColumnDescription column(TableSchemaMetadata metadata, String name) {
        return metadata.columns().stream().filter(column -> name.equals(column.getFieldName())).findFirst().orElseThrow();
    }
}

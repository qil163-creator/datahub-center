package cn.iocoder.yudao.framework.dbswitch.core.schema.catalog;

import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.product.clickhouse.ClickHouseCatalogColumns;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Instant;

import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.CLICKHOUSE;
import static cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum.MYSQL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Row mapping for the ClickHouse 25.8.33.6 system.columns reader. No server connection. */
class ClickHouseCatalogColumnsTest {
    @Test
    void sqlReadsSystemColumnsAndNotTheMysqlCatalog() {
        String sql = ClickHouseCatalogColumns.SQL;
        assertTrue(sql.contains("FROM system.columns"));
        assertTrue(sql.contains("name AS column_name"));
        assertTrue(sql.contains("type AS native_type"));
        assertTrue(sql.contains("default_kind"));
        assertTrue(sql.contains("default_expression"));
        assertTrue(sql.contains("comment AS column_comment"));
        assertTrue(sql.contains("numeric_precision"));
        assertTrue(sql.contains("numeric_scale"));
        assertTrue(sql.contains("character_octet_length AS character_length"));
        assertTrue(sql.contains("WHERE database=? AND table=? ORDER BY position"));
        assertFalse(sql.contains("GENERATION_EXPRESSION"));
        assertFalse(sql.toLowerCase(java.util.Locale.ROOT).contains("information_schema"));
        assertFalse(sql.contains("is_in_primary_key"));
        assertFalse(sql.contains("is_in_sorting_key"));
        assertFalse(sql.contains("is_in_partition_key"));
        assertFalse(sql.contains("is_in_sampling_key"));
        assertFalse(sql.contains("datetime_precision"));
        assertFalse(sql.contains("serialization_hint"));
        assertFalse(sql.contains("compression_codec"));
    }

    @Test
    void plainIntegerUsesBitPrecisionAndHasNoDefault() throws Exception {
        var column = apply("plain", "Int32", "", "", "", 32L, 0L, null);
        assertFalse(column.isNullable());
        assertFalse(column.isPk());
        assertNull(column.getDefaultValue());
        var facts = column.getMetadataEvidence();
        assertEquals("Int32", facts.nativeType().value());
        assertEquals(MetadataValue.State.ABSENT, facts.defaultExpression().state());
        assertEquals(MetadataValue.State.ABSENT, facts.generatedExpression().state());
        assertEquals(32, facts.numericPrecision().value());
        assertEquals(0, facts.numericScale().value());
        assertEquals(MetadataValue.State.ABSENT, facts.characterLength().state());
        assertEquals(MetadataValue.State.ABSENT, facts.identity().state());
        assertEquals(MetadataValue.State.ABSENT, facts.charset().state());
        assertEquals(MetadataValue.State.ABSENT, facts.collation().state());
        assertEquals(Boolean.FALSE, facts.nullable().value());
    }

    @Test
    void nullableIntegerKeepsPrecisionAndBoolUsesEightBits() throws Exception {
        var nullable = apply("n", "Nullable(Int32)", "", "", "", 32L, 0L, null);
        assertTrue(nullable.isNullable());
        assertEquals(32, nullable.getMetadataEvidence().numericPrecision().value());
        var bool = apply("b", "Bool", "", "", "", 8L, 0L, null);
        assertFalse(bool.isNullable());
        assertEquals(8, bool.getMetadataEvidence().numericPrecision().value());
        assertEquals(0, bool.getMetadataEvidence().numericScale().value());
    }

    @Test
    void defaultTextIsStoredSqlAndFixedStringLengthIsOctets() throws Exception {
        var text = apply("s", "String", "DEFAULT", "'a'", "", null, null, null);
        assertEquals("'a'", text.getDefaultValue());
        assertEquals("'a'", text.getMetadataEvidence().defaultExpression().value());
        assertEquals(MetadataValue.State.ABSENT, text.getMetadataEvidence().generatedExpression().state());
        assertEquals(MetadataValue.State.ABSENT, text.getMetadataEvidence().numericPrecision().state());
        assertEquals(MetadataValue.State.ABSENT, text.getMetadataEvidence().characterLength().state());
        var number = apply("d", "Int32", "DEFAULT", "1", "", 32L, 0L, null);
        assertEquals("1", number.getDefaultValue());
        var fixed = apply("fs", "FixedString(8)", "", "", "", null, null, 8L);
        assertEquals(8L, fixed.getMetadataEvidence().characterLength().value());
        assertEquals(MetadataValue.State.ABSENT, fixed.getMetadataEvidence().numericPrecision().state());
        var note = apply("note", "String", "", "", "wave16", null, null, null);
        assertEquals("wave16", note.getRemarks());
    }

    @Test
    void dateTime64DoesNotCopyDatetimePrecisionIntoNumericScale() throws Exception {
        var column = apply("dt64", "DateTime64(3)", "", "", "", null, null, null);
        assertEquals(MetadataValue.State.ABSENT, column.getMetadataEvidence().numericPrecision().state());
        assertEquals(MetadataValue.State.ABSENT, column.getMetadataEvidence().numericScale().state());
        assertEquals("DateTime64(3)", column.getMetadataEvidence().nativeType().value());
    }

    @Test
    void decimalAndLowCardinalityNullableMatchTheProbedTypeText() throws Exception {
        var decimal = apply("dec", "Decimal(38, 9)", "", "", "", 38L, 9L, null);
        assertFalse(decimal.isNullable());
        assertEquals(38, decimal.getMetadataEvidence().numericPrecision().value());
        assertEquals(9, decimal.getMetadataEvidence().numericScale().value());
        var nested = apply("lcn", "LowCardinality(Nullable(String))", "", "", "", null, null, null);
        assertTrue(nested.isNullable());
        var plain = apply("lc", "LowCardinality(String)", "", "", "", null, null, null);
        assertFalse(plain.isNullable());
    }

    @Test
    void unprobedDefaultKindStaysUnsupportedAndDoesNotPublishAnExpression() throws Exception {
        var column = column("m");
        column.setAutoIncrement(true);
        ClickHouseCatalogColumns.apply(column, row("m", "Int32", "MATERIALIZED", "7", "", 32L, 0L, null), provenance());
        assertNull(column.getDefaultValue());
        assertTrue(column.isAutoIncrement());
        assertFalse(column.isPk());
        assertEquals(MetadataValue.State.UNSUPPORTED, column.getMetadataEvidence().defaultExpression().state());
        assertEquals(MetadataValue.State.UNSUPPORTED, column.getMetadataEvidence().generatedExpression().state());
    }

    @Test
    void splitNumericFactsAndForeignProvenanceAreRejected() throws Exception {
        assertThrows(SQLException.class, () -> apply("plain", "Int32", "", "", "", 32L, null, null));
        assertThrows(SQLException.class, () -> ClickHouseCatalogColumns.apply(column("plain"),
                row("other", "Int32", "", "", "", 32L, 0L, null), provenance()));
        assertThrows(SQLFeatureNotSupportedException.class, () -> ClickHouseCatalogColumns.apply(column("plain"),
                row("plain", "Int32", "", "", "", 32L, 0L, null),
                new MetadataProvenance(MYSQL, "MySQL", "8.0.36", "MYSQL_CATALOG_V1", Instant.EPOCH)));
    }

    @Test
    void defaultGeneratorAndMissingKindCellsCannotProveIdentityAbsence() throws Exception {
        var counter = apply("counter", "UInt64", "DEFAULT", "generateSerialID('counter')", "", 64L, 0L, null);
        assertEquals("generateSerialID('counter')", counter.getMetadataEvidence().defaultExpression().value());
        assertEquals(MetadataValue.State.UNKNOWN, counter.getMetadataEvidence().identity().state());
        var missingKind = apply("plain", "Int64", null, "", "", 64L, 0L, null);
        assertEquals(MetadataValue.State.UNKNOWN, missingKind.getMetadataEvidence().identity().state());
        var missingExpression = apply("plain", "Int64", "", null, "", 64L, 0L, null);
        assertEquals(MetadataValue.State.UNKNOWN, missingExpression.getMetadataEvidence().identity().state());
    }

    private static ColumnDescription apply(String name, String type, String kind, String expression, String comment,
            Long precision, Long scale, Long octets) throws Exception {
        var column = column(name);
        column.setNullable(true);
        column.setPk(false);
        ClickHouseCatalogColumns.apply(column, row(name, type, kind, expression, comment, precision, scale, octets), provenance());
        return column;
    }

    private static ColumnDescription column(String name) {
        var column = new ColumnDescription();
        column.setFieldName(name);
        column.setDbType(CLICKHOUSE);
        return column;
    }

    private static MetadataProvenance provenance() {
        return new MetadataProvenance(CLICKHOUSE, "ClickHouse", "25.8.33.6", "CLICKHOUSE_CATALOG_V1", Instant.EPOCH);
    }

    private static ResultSet row(String name, String type, String kind, String expression, String comment,
            Long precision, Long scale, Long octets) throws SQLException {
        ResultSet rows = mock(ResultSet.class);
        when(rows.getString("column_name")).thenReturn(name);
        when(rows.getString("native_type")).thenReturn(type);
        when(rows.getString("default_kind")).thenReturn(kind);
        when(rows.getString("default_expression")).thenReturn(expression);
        when(rows.getString("column_comment")).thenReturn(comment);
        when(rows.getObject("numeric_precision")).thenReturn(precision);
        when(rows.getObject("numeric_scale")).thenReturn(scale);
        when(rows.getObject("character_length")).thenReturn(octets);
        when(rows.wasNull()).thenReturn(false);
        return rows;
    }
}

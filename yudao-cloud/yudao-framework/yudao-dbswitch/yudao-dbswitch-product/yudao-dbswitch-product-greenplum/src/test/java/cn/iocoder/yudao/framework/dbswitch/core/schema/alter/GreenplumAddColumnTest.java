package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseGreenplumImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnSequenceMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.product.greenplum.GreenplumAddColumn;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import org.junit.jupiter.api.Test;

import java.sql.SQLFeatureNotSupportedException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Generator contract only. No Greenplum connection is opened. */
class GreenplumAddColumnTest {
    private final DatabaseGreenplumImpl database = new DatabaseGreenplumImpl();

    @Test
    void providerDeclaresTheHeapScopeWithoutUsingThePostgresqlDeclaration() {
        var registry = DbSwitchProductRegistry.discover();
        var greenplum = registry.products().stream()
                .filter(product -> product.product() == ProductTypeEnum.GREENPLUM).findFirst().orElseThrow();
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "GREENPLUM_HEAP_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED"),
                greenplum.capabilities().get(DatabaseOperation.DDL_ALTER));
        var postgres = registry.products().stream()
                .filter(product -> product.product() == ProductTypeEnum.POSTGRESQL).findFirst().orElseThrow();
        assertEquals("POSTGRESQL_NULLABLE_ADD_WITH_FRESH_LOCKED_PRECONDITION_ONLY",
                postgres.capabilities().get(DatabaseOperation.DDL_ALTER).reason());
    }

    @Test
    void nullableHeapColumnUsesQuotedPostgresqlAddColumnAndExistingFieldDefinition() throws Exception {
        var column = column("Qty", ColumnMetaData.TYPE_INTEGER, 9, 0);
        String sql = database.getAddColumnSql("App_1", "Orders", column);
        assertEquals(alter("App_1", "Orders", "Qty", "INTEGER"), sql);
        assertEquals(sql, GreenplumAddColumn.sql(database, "App_1", "Orders", column, "heap", List.of("id")));
        assertFalse(sql.toLowerCase().contains("not null"));
        assertFalse(sql.toLowerCase().contains("default"));
        assertFalse(sql.toLowerCase().contains("primary key"));
        assertFalse(sql.toLowerCase().contains("generated"));
        assertFalse(sql.toLowerCase().contains("distributed"));
        assertTrue(database.getFieldDefinition(column.getMetaData(), List.of(), false, false, false).contains("INTEGER"));

        var amount = column("amount", ColumnMetaData.TYPE_NUMBER, 20, 2);
        assertEquals(alter("public", "sample", "amount", "NUMERIC(22, 2)"),
                database.getAddColumnSql("public", "sample", amount));
        assertTrue(database.getFieldDefinition(amount.getMetaData(), List.of(), false, false, false).contains("NUMERIC(22, 2)"));

        assertEquals(alter("public", "sample", "note", "VARCHAR(10)"),
                GreenplumAddColumn.sql(database, "public", "sample", column("note", ColumnMetaData.TYPE_STRING, 10, 0)));
        assertEquals(alter("public", "sample", "body", "TEXT"),
                GreenplumAddColumn.sql(database, "public", "sample", column("body", ColumnMetaData.TYPE_STRING, -1, 0)));
        assertEquals(alter("public", "sample", "payload", "BYTEA"),
                GreenplumAddColumn.sql(database, "public", "sample", column("payload", ColumnMetaData.TYPE_BINARY, -1, 0)));
        assertEquals(alter("public", "sample", "ratio", "DOUBLE PRECISION"),
                GreenplumAddColumn.sql(database, "public", "sample", column("ratio", ColumnMetaData.TYPE_NUMBER, -1, -1)));

        String longName = "c" + "a".repeat(62);
        assertEquals(63, longName.length());
        assertEquals(alter("public", "sample", longName, "BOOLEAN"),
                database.getAddColumnSql("public", "sample", column(longName, ColumnMetaData.TYPE_BOOLEAN, 0, 0)));
    }

    @Test
    void storageDistributionNullabilityKeyDefaultAndGeneratedColumnsAreRejected() {
        var column = column("extra", ColumnMetaData.TYPE_INTEGER, 9, 0);
        assertRejected("append-optimized or column-oriented",
                () -> GreenplumAddColumn.sql(database, "public", "sample", column, "ao_row", List.of()));
        assertRejected("append-optimized or column-oriented",
                () -> GreenplumAddColumn.sql(database, "public", "sample", column, "ao_column", List.of("id")));
        assertRejected("append-optimized or column-oriented",
                () -> GreenplumAddColumn.sql(database, "public", "sample", column, null, List.of()));
        assertRejected("distribution key",
                () -> GreenplumAddColumn.sql(database, "public", "sample", column, "heap", List.of("id", "extra")));
        assertRejected("unknown distribution key",
                () -> GreenplumAddColumn.sql(database, "public", "sample", column, "heap", null));

        var required = column("extra", ColumnMetaData.TYPE_INTEGER, 9, 0);
        required.setNullable(false);
        assertRejected("NOT NULL", () -> database.getAddColumnSql("public", "sample", required));
        var evidenced = column("extra", ColumnMetaData.TYPE_INTEGER, 9, 0);
        evidence(evidenced, MetadataValue.unknown(), MetadataValue.value(false), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.unknown());
        assertRejected("NOT NULL", () -> GreenplumAddColumn.sql(database, "public", "sample", evidenced));

        var key = column("extra", ColumnMetaData.TYPE_INTEGER, 9, 0);
        key.setPk(true);
        assertRejected("primary key", () -> database.getAddColumnSql("public", "sample", key));

        var literalDefault = column("extra", ColumnMetaData.TYPE_INTEGER, 9, 0);
        literalDefault.setDefaultValue("NULL");
        assertRejected("default", () -> database.getAddColumnSql("public", "sample", literalDefault));
        var expressionDefault = column("extra", ColumnMetaData.TYPE_INTEGER, 9, 0);
        evidence(expressionDefault, MetadataValue.value("0"), MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.unknown());
        assertRejected("default", () -> GreenplumAddColumn.sql(database, "public", "sample", expressionDefault));

        var serial = column("extra", ColumnMetaData.TYPE_INTEGER, 9, 0);
        serial.setAutoIncrement(true);
        assertRejected("generated column", () -> database.getAddColumnSql("public", "sample", serial));
        var generated = column("extra", ColumnMetaData.TYPE_INTEGER, 9, 0);
        evidence(generated, MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.value("(id + 1)"), MetadataValue.unknown());
        assertRejected("generated column", () -> GreenplumAddColumn.sql(database, "public", "sample", generated));
        var identity = column("extra", ColumnMetaData.TYPE_INTEGER, 9, 0);
        evidence(identity, MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.value("GENERATED ALWAYS AS IDENTITY"), MetadataValue.unknown(), MetadataValue.unknown());
        assertRejected("generated column", () -> GreenplumAddColumn.sql(database, "public", "sample", identity));
        var sequence = column("extra", ColumnMetaData.TYPE_INTEGER, 9, 0);
        evidence(sequence, MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.unsupported());
        assertRejected("generated column", () -> GreenplumAddColumn.sql(database, "public", "sample", sequence));
    }

    @Test
    void namesOutsideTheOrdinaryQuotedSubsetFailBeforeSqlIsBuilt() {
        var column = column("bad-name", ColumnMetaData.TYPE_INTEGER, 9, 0);
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("public", "sample", column));
        assertThrows(IllegalArgumentException.class,
                () -> database.getAddColumnSql("\"public\"", "sample", column("extra", ColumnMetaData.TYPE_INTEGER, 9, 0)));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("public", "sample",
                column("c" + "a".repeat(63), ColumnMetaData.TYPE_BOOLEAN, 0, 0)));
    }

    @Test
    void unknownProductCannotBorrowTheGreenplumAddColumnImplementation() {
        var other = mock(DatabaseGreenplumImpl.class, CALLS_REAL_METHODS);
        when(other.getDatabaseType()).thenReturn(ProductTypeEnum.UNKNOWN);
        var column = column("extra", ColumnMetaData.TYPE_INTEGER, 9, 0);
        column.setNullable(false);
        var failure = assertThrows(SQLFeatureNotSupportedException.class,
                () -> other.getAddColumnSql("public", "sample", column));
        assertEquals("0A000", failure.getSQLState());
    }

    private static void assertRejected(String reason, org.junit.jupiter.api.function.Executable call) {
        var failure = assertThrows(SQLFeatureNotSupportedException.class, call);
        assertEquals("0A000", failure.getSQLState());
        assertTrue(failure.getMessage().contains(reason), failure.getMessage());
    }

    private static String alter(String schema, String table, String column, String type) {
        return "ALTER TABLE " + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.GREENPLUM, schema)
                + "." + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.GREENPLUM, table)
                + " ADD COLUMN " + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.GREENPLUM, column)
                + " " + type;
    }

    private static ColumnDescription column(String name, int type, int length, int precision) {
        var column = new ColumnDescription();
        column.setFieldName(name);
        column.setNullable(true);
        column.setValType(type);
        column.setLength(length);
        column.setPrecision(precision);
        return column;
    }

    private static void evidence(ColumnDescription column, MetadataValue<String> defaults, MetadataValue<Boolean> nullable,
            MetadataValue<String> identity, MetadataValue<String> generated, MetadataValue<ColumnSequenceMetadata> sequence) {
        var base = ColumnMetadataEvidence.unknown();
        column.setMetadataEvidence(new ColumnMetadataEvidence(base.provenance(), base.nativeType(), defaults, nullable,
                base.numericPrecision(), base.numericScale(), base.characterLength(), identity, generated,
                base.charset(), base.collation(), sequence));
    }
}

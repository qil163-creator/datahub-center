package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.SqlTypeSemantics;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOpengaussImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnSequenceMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.schema.SchemaDiff;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlStatementClassifier;
import cn.iocoder.yudao.framework.dbswitch.product.opengauss.OpengaussAddColumn;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import org.junit.jupiter.api.Test;

import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** SQL generation only. No openGauss connection is opened. */
class OpengaussAddColumnTest {
    private final DatabaseOpengaussImpl database = new DatabaseOpengaussImpl();

    @Test
    void nullableAddQuotesPhysicalNamesAndReusesFieldDefinition() {
        var column = nullable("note", "varchar", 40);
        column.setRemarks("secret'; DROP TABLE sample");
        String sql = database.getAddColumnSql("app", "sample", column);
        assertEquals(OpengaussAddColumn.sql(database, "app", "sample", column), sql);
        assertEquals("ALTER TABLE \"app\".\"sample\" ADD COLUMN \"note\" VARCHAR(40)", sql);
        assertEquals("VARCHAR(40)", fieldType(column));
        assertFalse(sql.contains("secret"));
        assertFalse(sql.contains("pg_catalog"));
        assertFalse(sql.contains("`"));
        assertFalse(sql.contains("ADD ("));
        assertFalse(sql.contains("NOT NULL"));
        assertFalse(sql.toUpperCase(java.util.Locale.ROOT).contains(" DEFAULT"));
        var plan = SqlScriptPlan.parse(ProductTypeEnum.OPENGAUSS, sql);
        assertEquals(1, plan.steps().size());
        assertEquals(SqlStatementClassifier.Kind.DDL, plan.steps().getFirst().kind());
    }

    @Test
    void mixedCaseOrdinaryNamesStayQuotedAsWritten() {
        var column = nullable("MiX", "varchar", 8);
        String sql = database.getAddColumnSql("Sc", "Tb", column);
        assertEquals("ALTER TABLE "
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.OPENGAUSS, "Sc") + "."
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.OPENGAUSS, "Tb")
                + " ADD COLUMN "
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.OPENGAUSS, "MiX")
                + " VARCHAR(8)", sql);
    }

    @Test
    void declaredTypesMatchExistingFieldDefinition() {
        assertSql("v_int", nullable("v_int", "int", null), "INTEGER");
        assertSql("v_big", nullable("v_big", "bigint", null), "BIGINT");
        assertSql("v_small", nullable("v_small", "smallint", null), "SMALLINT");
        assertSql("v_bool", nullable("v_bool", "boolean", null), "BOOLEAN");
        assertSql("v_date", nullable("v_date", "date", null), "DATE");
        assertSql("v_text", nullable("v_text", "text", null), "TEXT");
        assertSql("v_wide", nullable("v_wide", "varchar", 2000), "VARCHAR(2000)");
        assertSql("v_clob", nullable("v_clob", "varchar", 2001), "TEXT");
        assertSql("v_bytes", nullable("v_bytes", "bytea", null), "BYTEA");
        assertSql("v_double", nullable("v_double", "double", null), "DOUBLE PRECISION");
        assertSql("v_num", decimal("v_num", 12, 2), "NUMERIC(12, 2)");
        assertSql("v_zero", decimal("v_zero", 10, 0), "NUMERIC(10, 0)");
        assertSql("v_open", unboundedNumeric("v_open"), "NUMERIC");
        var timestamp = nullable("v_ts", "timestamp", null);
        timestamp.setLength(0);
        assertSql("v_ts", timestamp, "TIMESTAMP(0)");
        var time = nullable("v_time", "time", null);
        time.setLength(3);
        assertSql("v_time", time, "TIME(3)");
    }

    @Test
    void notNullPrimaryKeyDefaultIdentityAndGeneratedAreRejected() {
        var notNull = nullable("note", "varchar", 20);
        notNull.setNullable(false);
        assertRejected("NOT NULL", notNull);

        var evidencedNotNull = nullable("note", "varchar", 20);
        replace(evidencedNotNull, MetadataValue.value(false),
                evidencedNotNull.getMetadataEvidence().defaultExpression(),
                evidencedNotNull.getMetadataEvidence().identity(),
                evidencedNotNull.getMetadataEvidence().generatedExpression());
        assertRejected("NOT NULL", evidencedNotNull);

        var unknownNullable = nullable("note", "varchar", 20);
        replace(unknownNullable, MetadataValue.unsupported(),
                unknownNullable.getMetadataEvidence().defaultExpression(),
                unknownNullable.getMetadataEvidence().identity(),
                unknownNullable.getMetadataEvidence().generatedExpression());
        assertRejected("NOT NULL", unknownNullable);

        var key = nullable("note", "varchar", 20);
        key.setPk(true);
        assertRejected("primary key", key);

        var literalDefault = nullable("note", "varchar", 20);
        literalDefault.setDefaultValue("NULL");
        assertRejected("default", literalDefault);

        var evidencedDefault = nullable("note", "varchar", 20);
        replace(evidencedDefault, evidencedDefault.getMetadataEvidence().nullable(),
                MetadataValue.value("0"), evidencedDefault.getMetadataEvidence().identity(),
                evidencedDefault.getMetadataEvidence().generatedExpression());
        assertRejected("default", evidencedDefault);

        var unsupportedDefault = nullable("note", "varchar", 20);
        replace(unsupportedDefault, unsupportedDefault.getMetadataEvidence().nullable(),
                MetadataValue.unsupported(), unsupportedDefault.getMetadataEvidence().identity(),
                unsupportedDefault.getMetadataEvidence().generatedExpression());
        assertRejected("default", unsupportedDefault);

        var auto = nullable("note", "int", null);
        auto.setAutoIncrement(true);
        assertRejected("identity", auto);

        var serial = nullable("note", "int", null);
        serial.setFieldTypeName("bigserial");
        assertRejected("identity", serial);

        var identity = nullable("note", "int", null);
        replace(identity, identity.getMetadataEvidence().nullable(),
                identity.getMetadataEvidence().defaultExpression(),
                MetadataValue.value("GENERATED BY DEFAULT AS IDENTITY"),
                identity.getMetadataEvidence().generatedExpression());
        assertRejected("identity", identity);

        var generated = nullable("note", "int", null);
        replace(generated, generated.getMetadataEvidence().nullable(),
                generated.getMetadataEvidence().defaultExpression(),
                generated.getMetadataEvidence().identity(),
                MetadataValue.value("n + 1"));
        assertRejected("generated", generated);

        var generatedName = nullable("note", "int", null);
        generatedName.setFieldTypeName("integer generated");
        assertRejected("generated", generatedName);

        var sequence = nullable("note", "int", null);
        var owned = new ColumnSequenceMetadata(ColumnSequenceMetadata.Kind.OWNED_DEFAULT, "app", "note_seq",
                "integer", 1, 1, 1, 1000, 1, false, MetadataValue.absent());
        var base = sequence.getMetadataEvidence();
        sequence.setMetadataEvidence(new ColumnMetadataEvidence(base.provenance(), base.nativeType(),
                base.defaultExpression(), base.nullable(), base.numericPrecision(), base.numericScale(),
                base.characterLength(), base.identity(), base.generatedExpression(), base.charset(),
                base.collation(), MetadataValue.value(owned)));
        assertRejected("generated", sequence);
    }

    @Test
    void namesOutsideOrdinaryIdentifiersAndUnknownTypesFailBeforeSql() {
        assertThrows(IllegalArgumentException.class,
                () -> database.getAddColumnSql("bad-name", "sample", nullable("note", "varchar", 8)));
        assertThrows(IllegalArgumentException.class,
                () -> database.getAddColumnSql("app", "bad-name", nullable("note", "varchar", 8)));
        assertThrows(IllegalArgumentException.class,
                () -> database.getAddColumnSql("app", "sample", nullable("bad-name", "varchar", 8)));
        assertEquals(63, "a".concat("b".repeat(62)).length());
        assertTrue(database.getAddColumnSql("app", "sample", nullable("a".concat("b".repeat(62)), "varchar", 8))
                .contains(RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.OPENGAUSS, "a".concat("b".repeat(62)))));
        assertThrows(IllegalArgumentException.class,
                () -> database.getAddColumnSql("app", "sample", nullable("a".concat("b".repeat(63)), "varchar", 8)));
        var unknown = new ColumnDescription();
        unknown.setFieldName("note");
        unknown.setNullable(true);
        unknown.setDbType(ProductTypeEnum.OPENGAUSS);
        unknown.setValType(ColumnMetaData.TYPE_NONE);
        assertRejected("known field type", unknown);
        assertRejected("requires a column", null);
        var other = nullable("note", "varchar", 8);
        other.setDbType(ProductTypeEnum.POSTGRESQL);
        assertRejected("another product", other);
        var coarseTime = nullable("v_time", "time", null);
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("app", "sample", coarseTime));
        var zone = nullable("v_ts", "timestamp", null);
        zone.setLength(0);
        zone.setFieldType(Types.TIMESTAMP_WITH_TIMEZONE);
        assertThrows(UnsupportedOperationException.class, () -> database.getAddColumnSql("app", "sample", zone));
    }

    @Test
    void nonAModesAndOtherProductsDoNotProduceSql() {
        var column = nullable("note", "varchar", 8);
        for (String mode : new String[] {null, "", "B", "C", "PG", "a", "A ", "MYSQL", "ORACLE"}) {
            var rejected = assertThrows(SQLFeatureNotSupportedException.class,
                    () -> OpengaussAddColumn.sql(database, "app", "sample", column, mode));
            assertEquals("0A000", rejected.getSQLState());
            assertTrue(rejected.getMessage().contains("sql_compatibility A"), rejected.getMessage());
        }
        assertEquals("ALTER TABLE \"app\".\"sample\" ADD COLUMN \"note\" VARCHAR(8)",
                OpengaussAddColumn.sql(database, "app", "sample", column, "A"));
        var other = mock(DatabaseOpengaussImpl.class, CALLS_REAL_METHODS);
        when(other.getDatabaseType()).thenReturn(ProductTypeEnum.UNKNOWN);
        assertRejected("openGauss dialect", other, column);
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> OpengaussAddColumn.sql(null, "app", "sample", column, "A"));
    }

    @Test
    void alterDeclarationIsScopedToAModeAndDoesNotBorrowPostgres() {
        var registry = DbSwitchProductRegistry.discover();
        var opengauss = registry.products().stream()
                .filter(product -> product.product() == ProductTypeEnum.OPENGAUSS).findFirst().orElseThrow();
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "OPENGAUSS_A_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED"),
                opengauss.capabilities().get(DatabaseOperation.DDL_ALTER));
        assertEquals("OPENGAUSS_NATIVE_GENERATION_REPEATABLE_READ_NUMERIC_KEYS",
                opengauss.capabilities().get(DatabaseOperation.SYNC_CURSOR).reason());
        assertEquals("OPENGAUSS_EXPLICIT_NUMERIC_PRIMARY_KEY_TRANSACTIONAL_MERGE",
                opengauss.capabilities().get(DatabaseOperation.WRITE_UPSERT).reason());
        var postgres = registry.products().stream()
                .filter(product -> product.product() == ProductTypeEnum.POSTGRESQL).findFirst().orElseThrow();
        assertEquals("POSTGRESQL_NULLABLE_ADD_WITH_FRESH_LOCKED_PRECONDITION_ONLY",
                postgres.capabilities().get(DatabaseOperation.DDL_ALTER).reason());
    }

    @Test
    void schemaDiffStillDoesNotPlanOpengaussAdds() {
        var column = nullable("extra", "varchar", 30);
        var plan = new SchemaDiff().planAddColumns(database, "app", "sample", List.of(column),
                new TableSchemaMetadata(List.of(), List.of()));
        assertFalse(plan.ready());
        assertTrue(plan.steps().isEmpty());
        assertEquals("UNVERIFIED_ADDITIVE_TARGET", plan.diagnostics().getFirst().code());
    }

    private void assertSql(String name, ColumnDescription column, String type) {
        assertEquals("ALTER TABLE \"app\".\"sample\" ADD COLUMN \"" + name + "\" " + type,
                database.getAddColumnSql("app", "sample", column));
        assertEquals(type, fieldType(column));
    }

    private void assertRejected(String reason, ColumnDescription column) {
        assertRejected(reason, database, column);
    }

    private static void assertRejected(String reason, DatabaseOpengaussImpl dialect, ColumnDescription column) {
        var rejected = assertThrows(SQLFeatureNotSupportedException.class,
                () -> dialect.getAddColumnSql("app", "sample", column));
        assertEquals("0A000", rejected.getSQLState());
        assertTrue(rejected.getMessage().contains(reason), rejected.getMessage());
    }

    private String fieldType(ColumnDescription column) {
        String definition = database.getFieldDefinition(column.getMetaData(), null, false, false, false);
        String prefix = " " + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.OPENGAUSS, column.getFieldName()) + " ";
        assertTrue(definition.startsWith(prefix), definition);
        return definition.substring(prefix.length()).strip();
    }

    private static ColumnDescription nullable(String name, String type, Integer length) {
        var column = new ColumnDescription();
        column.setFieldName(name);
        column.setNullable(true);
        column.setDbType(ProductTypeEnum.OPENGAUSS);
        SqlTypeSemantics.applyDeclaredType(column, type, length, null, null);
        return column;
    }

    private static ColumnDescription decimal(String name, int precision, int scale) {
        var column = new ColumnDescription();
        column.setFieldName(name);
        column.setNullable(true);
        column.setDbType(ProductTypeEnum.OPENGAUSS);
        SqlTypeSemantics.applyDeclaredType(column, "decimal", null, precision, scale);
        return column;
    }

    private static ColumnDescription unboundedNumeric(String name) {
        var column = new ColumnDescription();
        column.setFieldName(name);
        column.setNullable(true);
        column.setDbType(ProductTypeEnum.OPENGAUSS);
        column.setFieldType(Types.NUMERIC);
        column.setValType(ColumnMetaData.TYPE_BIGNUMBER);
        column.setLength(0);
        column.setPrecision(0);
        return column;
    }

    private static void replace(ColumnDescription column, MetadataValue<Boolean> nullable,
                                MetadataValue<String> defaultExpression, MetadataValue<String> identity,
                                MetadataValue<String> generated) {
        var evidence = column.getMetadataEvidence();
        column.setMetadataEvidence(new ColumnMetadataEvidence(evidence.provenance(), evidence.nativeType(), defaultExpression,
                nullable, evidence.numericPrecision(), evidence.numericScale(), evidence.characterLength(), identity,
                generated, evidence.charset(), evidence.collation()));
    }
}

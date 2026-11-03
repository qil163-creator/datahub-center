package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.SqlTypeSemantics;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseUxdbImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlScriptPlan;
import cn.iocoder.yudao.framework.dbswitch.core.service.SqlStatementClassifier;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import org.junit.jupiter.api.Test;

import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** SQL generation only. No UXDB connection is opened. */
class UxdbAddColumnTest {
    private final DatabaseUxdbImpl database = new DatabaseUxdbImpl();

    @Test
    void nullableAddQuotesPhysicalNamesAndReusesFieldDefinition() {
        var column = nullable("note", "varchar", 40);
        column.setRemarks("secret");
        String sql = database.getAddColumnSql("app", "sample", column);
        assertEquals("ALTER TABLE \"app\".\"sample\" ADD COLUMN \"note\" VARCHAR(40)", sql);
        assertEquals("VARCHAR(40)", fieldType(column));
        assertFalse(sql.contains("secret"));
        assertFalse(sql.contains("pg_catalog"));
        assertFalse(sql.contains("ux_catalog"));
        assertFalse(sql.contains("NOT NULL"));
        assertFalse(sql.toUpperCase(java.util.Locale.ROOT).contains(" DEFAULT"));
        var plan = SqlScriptPlan.parse(ProductTypeEnum.POSTGRESQL, sql);
        assertEquals(1, plan.steps().size());
        assertEquals(SqlStatementClassifier.Kind.DDL, plan.steps().getFirst().kind());
    }

    @Test
    void mixedCaseOrdinaryNamesStayQuotedAsWritten() {
        var column = nullable("MiX", "varchar", 8);
        String sql = database.getAddColumnSql("Sc", "Tb", column);
        assertEquals("ALTER TABLE "
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.UXDB, "Sc") + "."
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.UXDB, "Tb")
                + " ADD COLUMN "
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.UXDB, "MiX")
                + " VARCHAR(8)", sql);
    }

    @Test
    void declaredTypesMatchExistingFieldDefinition() {
        assertSql("v_int", nullable("v_int", "int", null), "INTEGER");
        assertSql("v_big", nullable("v_big", "bigint", null), "BIGINT");
        assertSql("v_bool", nullable("v_bool", "boolean", null), "BOOLEAN");
        assertSql("v_ts", nullable("v_ts", "timestamp", null), "TIMESTAMP");
        assertSql("v_date", nullable("v_date", "date", null), "DATE");
        assertSql("v_time", nullable("v_time", "time", null), "TIME");
        assertSql("v_text", nullable("v_text", "text", null), "TEXT");
        assertSql("v_bytes", nullable("v_bytes", "bytea", null), "BYTEA");
        assertSql("v_double", nullable("v_double", "double", null), "DOUBLE PRECISION");
        assertSql("v_num", decimal("v_num", 12, 2), "NUMERIC(12, 2)");
        assertSql("v_zero", decimal("v_zero", 10, 0), "NUMERIC(10, 0)");
        assertSql("v_open", unboundedNumeric("v_open"), "NUMERIC");
    }

    @Test
    void notNullPrimaryKeyDefaultIdentityAndGeneratedAreRejected() {
        var notNull = nullable("note", "varchar", 20);
        notNull.setNullable(false);
        assertRejected(notNull);

        var evidencedNotNull = nullable("note", "varchar", 20);
        replace(evidencedNotNull, MetadataValue.value(false),
                evidencedNotNull.getMetadataEvidence().defaultExpression(),
                evidencedNotNull.getMetadataEvidence().identity(),
                evidencedNotNull.getMetadataEvidence().generatedExpression());
        assertRejected(evidencedNotNull);

        var key = nullable("note", "varchar", 20);
        key.setPk(true);
        assertRejected(key);

        var literalDefault = nullable("note", "varchar", 20);
        literalDefault.setDefaultValue("NULL");
        assertRejected(literalDefault);

        var evidencedDefault = nullable("note", "varchar", 20);
        replace(evidencedDefault, evidencedDefault.getMetadataEvidence().nullable(),
                MetadataValue.value("0"), evidencedDefault.getMetadataEvidence().identity(),
                evidencedDefault.getMetadataEvidence().generatedExpression());
        assertRejected(evidencedDefault);

        var auto = nullable("note", "int", null);
        auto.setAutoIncrement(true);
        assertRejected(auto);

        var identity = nullable("note", "int", null);
        replace(identity, identity.getMetadataEvidence().nullable(),
                identity.getMetadataEvidence().defaultExpression(), MetadataValue.value("GENERATED BY DEFAULT AS IDENTITY"),
                identity.getMetadataEvidence().generatedExpression());
        assertRejected(identity);

        var generated = nullable("note", "int", null);
        replace(generated, generated.getMetadataEvidence().nullable(),
                generated.getMetadataEvidence().defaultExpression(), generated.getMetadataEvidence().identity(),
                MetadataValue.value("n + 1"));
        assertRejected(generated);
    }

    @Test
    void namesOutsideOrdinaryIdentifiersAndUnknownTypesFailBeforeSql() {
        assertThrows(IllegalArgumentException.class,
                () -> database.getAddColumnSql("bad-name", "sample", nullable("note", "varchar", 8)));
        assertThrows(IllegalArgumentException.class,
                () -> database.getAddColumnSql("app", "bad-name", nullable("note", "varchar", 8)));
        assertThrows(IllegalArgumentException.class,
                () -> database.getAddColumnSql("app", "sample", nullable("bad-name", "varchar", 8)));
        var unknown = new ColumnDescription();
        unknown.setFieldName("note");
        unknown.setNullable(true);
        unknown.setValType(ColumnMetaData.TYPE_NONE);
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.getAddColumnSql("app", "sample", unknown));
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.getAddColumnSql("app", "sample", null));
    }

    @Test
    void unknownProductCannotBorrowTheUxdbAddColumnImplementation() {
        var database = mock(DatabaseUxdbImpl.class, CALLS_REAL_METHODS);
        when(database.getDatabaseType()).thenReturn(ProductTypeEnum.UNKNOWN);
        var column = nullable("bad-name", "varchar", 8);
        var failure = assertThrows(SQLFeatureNotSupportedException.class,
                () -> database.getAddColumnSql("app", "sample", column));
        assertEquals("0A000", failure.getSQLState());
    }

    @Test
    void alterDeclarationIsScopedAndDoesNotBorrowPostgres() {
        var registry = DbSwitchProductRegistry.discover();
        var uxdb = registry.products().stream().filter(product -> product.product() == ProductTypeEnum.UXDB).findFirst().orElseThrow();
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED, "UXDB_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED"),
                uxdb.capabilities().get(DatabaseOperation.DDL_ALTER));
        var postgres = registry.products().stream()
                .filter(product -> product.product() == ProductTypeEnum.POSTGRESQL).findFirst().orElseThrow();
        assertEquals("POSTGRESQL_NULLABLE_ADD_WITH_FRESH_LOCKED_PRECONDITION_ONLY",
                postgres.capabilities().get(DatabaseOperation.DDL_ALTER).reason());
    }

    private void assertSql(String name, ColumnDescription column, String type) {
        assertEquals("ALTER TABLE \"app\".\"sample\" ADD COLUMN \"" + name + "\" " + type,
                database.getAddColumnSql("app", "sample", column));
        assertEquals(type, fieldType(column));
    }

    private void assertRejected(ColumnDescription column) {
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.getAddColumnSql("app", "sample", column));
    }

    private String fieldType(ColumnDescription column) {
        String definition = database.getFieldDefinition(column.getMetaData(), null, false, false, true);
        String prefix = " " + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.UXDB, column.getFieldName()) + "   ";
        assertTrue(definition.startsWith(prefix), definition);
        return definition.substring(prefix.length()).strip();
    }

    private static ColumnDescription nullable(String name, String type, Integer length) {
        var column = new ColumnDescription();
        column.setFieldName(name);
        column.setNullable(true);
        column.setDbType(ProductTypeEnum.UXDB);
        SqlTypeSemantics.applyDeclaredType(column, type, length, null, null);
        return column;
    }

    private static ColumnDescription decimal(String name, int precision, int scale) {
        var column = new ColumnDescription();
        column.setFieldName(name);
        column.setNullable(true);
        column.setDbType(ProductTypeEnum.UXDB);
        SqlTypeSemantics.applyDeclaredType(column, "decimal", null, precision, scale);
        return column;
    }

    private static ColumnDescription unboundedNumeric(String name) {
        var column = new ColumnDescription();
        column.setFieldName(name);
        column.setNullable(true);
        column.setDbType(ProductTypeEnum.UXDB);
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

package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseKingbaseImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.schema.SchemaDiff;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.product.kingbase.KingbaseAddColumn;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import org.junit.jupiter.api.Test;

import java.sql.SQLFeatureNotSupportedException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KingbaseAddColumnTest {
    private final DatabaseKingbaseImpl database = new DatabaseKingbaseImpl();

    @Test void nullableColumnUsesGuardQuotesAndExistingFieldDefinition() {
        var column = column("note", ColumnMetaData.TYPE_STRING, 30, 0);
        String sql = database.getAddColumnSql("MiX", "TaB", column);
        assertEquals(KingbaseAddColumn.sql(database, "MiX", "TaB", column), sql);
        assertEquals("ALTER TABLE " + quote("MiX") + "." + quote("TaB") + " ADD COLUMN " + quote("note") + " "
                + typeToken(column), sql);
        assertEquals("ALTER TABLE \"MiX\".\"TaB\" ADD COLUMN \"note\" VARCHAR(30)", sql);
        assertFalse(sql.contains(";"));
        assertFalse(sql.toUpperCase().contains("NOT NULL"));
        assertFalse(sql.toUpperCase().contains("DEFAULT"));
    }

    @Test void declaredTypesStayOnTheExistingFieldDefinition() {
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" BIGINT", sql(column("c", ColumnMetaData.TYPE_INTEGER, 18, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" INTEGER", sql(column("c", ColumnMetaData.TYPE_INTEGER, 9, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" SMALLINT", sql(column("c", ColumnMetaData.TYPE_INTEGER, 4, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" NUMERIC(12, 2)", sql(column("c", ColumnMetaData.TYPE_BIGNUMBER, 10, 2)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" DOUBLE PRECISION", sql(column("c", ColumnMetaData.TYPE_NUMBER, -1, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" DOUBLE PRECISION", sql(column("c", ColumnMetaData.TYPE_NUMBER, 20, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" TEXT", sql(column("c", ColumnMetaData.TYPE_STRING, -1, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" TEXT", sql(column("c", ColumnMetaData.TYPE_STRING, 2001, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" VARCHAR(2000)", sql(column("c", ColumnMetaData.TYPE_STRING, 2000, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" BYTEA", sql(column("c", ColumnMetaData.TYPE_BINARY, 0, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" BOOLEAN", sql(column("c", ColumnMetaData.TYPE_BOOLEAN, 0, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" DATE", sql(column("c", ColumnMetaData.TYPE_DATE, 0, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" TIME", sql(column("c", ColumnMetaData.TYPE_TIME, 0, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" TIMESTAMP", sql(column("c", ColumnMetaData.TYPE_TIMESTAMP, 0, 0)));
    }

    @Test void remarksAndLabelDoNotChangeTheQuotedAdd() {
        var column = column("note", ColumnMetaData.TYPE_STRING, 8, 0);
        column.setRemarks("x'; DROP TABLE \"TaB\"");
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"note\" VARCHAR(8)", sql(column));
        var labeled = new ColumnDescription();
        labeled.setLabelName("note");
        labeled.setNullable(true);
        labeled.setValType(ColumnMetaData.TYPE_STRING);
        labeled.setLength(8);
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"note\" VARCHAR(8)", sql(labeled));
    }

    @Test void unsupportedShapesThrowBeforeFieldDefinition() {
        var dialect = spy(new DatabaseKingbaseImpl());
        assertRejected("Kingbase NOT NULL add column is not supported", dialect, unnamed(false, false, false, null));
        var notNullEvidence = column("c", ColumnMetaData.TYPE_STRING, 8, 0);
        notNullEvidence.setMetadataEvidence(evidence(MetadataValue.unknown(), MetadataValue.value(false),
                MetadataValue.unknown(), MetadataValue.unknown()));
        assertRejected("Kingbase NOT NULL add column is not supported", dialect, notNullEvidence);
        assertRejected("Kingbase primary key add column is not supported", dialect, unnamed(true, true, false, null));
        assertRejected("Kingbase default add column is not supported", dialect, unnamed(true, false, false, "0"));
        assertRejected("Kingbase default add column is not supported", dialect, unnamed(true, false, false, "NULL"));
        assertRejected("Kingbase default add column is not supported", dialect, unnamed(true, false, false, "null"));
        var evidencedDefault = column("c", ColumnMetaData.TYPE_STRING, 8, 0);
        evidencedDefault.setMetadataEvidence(evidence(MetadataValue.value("0"), MetadataValue.value(true),
                MetadataValue.unknown(), MetadataValue.unknown()));
        assertRejected("Kingbase default add column is not supported", dialect, evidencedDefault);
        var identity = column("c", ColumnMetaData.TYPE_INTEGER, 18, 0);
        identity.setAutoIncrement(true);
        assertRejected("Kingbase identity add column is not supported", dialect, identity);
        identity.setAutoIncrement(false);
        identity.setFieldTypeName("bigserial");
        assertRejected("Kingbase identity add column is not supported", dialect, identity);
        identity.setFieldTypeName(null);
        identity.setMetadataEvidence(evidence(MetadataValue.unknown(), MetadataValue.value(true),
                MetadataValue.value("ALWAYS"), MetadataValue.unknown()));
        assertRejected("Kingbase identity add column is not supported", dialect, identity);
        var generated = column("c", ColumnMetaData.TYPE_INTEGER, 9, 0);
        generated.setFieldTypeName("integer generated");
        assertRejected("Kingbase generated add column is not supported", dialect, generated);
        generated.setFieldTypeName(null);
        generated.setMetadataEvidence(evidence(MetadataValue.unknown(), MetadataValue.value(true),
                MetadataValue.unknown(), MetadataValue.value("STORED")));
        assertRejected("Kingbase generated add column is not supported", dialect, generated);
        var unknown = column("c", ColumnMetaData.TYPE_NONE, 0, 0);
        assertRejected("Kingbase add column requires a recognized field type", dialect, unknown);
        var otherProduct = column("c", ColumnMetaData.TYPE_STRING, 8, 0);
        otherProduct.setDbType(ProductTypeEnum.POSTGRESQL);
        assertRejected("Kingbase add column does not accept another product column", dialect, otherProduct);
        verify(dialect, never()).getFieldDefinition(any(), any(), anyBoolean(), anyBoolean(), anyBoolean());
    }

    @Test void badIdentifiersAndOtherProductsDoNotProduceSql() {
        var column = column("bad-name", ColumnMetaData.TYPE_STRING, 8, 0);
        assertThrows(IllegalArgumentException.class, () -> sql(column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("bad-name", "t", column("c", ColumnMetaData.TYPE_STRING, 8, 0)));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("s", "t", column("a\" ;drop", ColumnMetaData.TYPE_STRING, 8, 0)));
        assertEquals(63, "a".concat("b".repeat(62)).length());
        assertTrue(sql(column("a".concat("b".repeat(62)), ColumnMetaData.TYPE_STRING, 8, 0)).contains(quote("a".concat("b".repeat(62)))));
        assertThrows(IllegalArgumentException.class, () -> sql(column("a".concat("b".repeat(63)), ColumnMetaData.TYPE_STRING, 8, 0)));
        var notNullBadName = column("bad-name", ColumnMetaData.TYPE_STRING, 8, 0);
        notNullBadName.setNullable(false);
        assertRejected("Kingbase NOT NULL add column is not supported", database, notNullBadName);
        var dialect = spy(new DatabaseKingbaseImpl());
        doReturn(ProductTypeEnum.UNKNOWN).when(dialect).getDatabaseType();
        assertRejected("Kingbase nullable add column does not apply to this product", dialect,
                column("c", ColumnMetaData.TYPE_STRING, 8, 0));
        assertThrows(IllegalArgumentException.class, () -> KingbaseAddColumn.sql(null, "s", "t", column("c", ColumnMetaData.TYPE_STRING, 8, 0)));
    }

    @Test void providerDeclaresOnlyTheNullableQuotedAddScope() {
        var kingbase = DbSwitchProductRegistry.discover().products().stream()
                .filter(product -> product.product() == ProductTypeEnum.KINGBASE).findFirst().orElseThrow();
        var capabilities = kingbase.capabilities();
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED, "KINGBASE_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED"),
                capabilities.get(DatabaseOperation.DDL_ALTER));
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "KINGBASE_ORACLE_CI_PINNED_GENERATION_REPEATABLE_READ_EXACT_WATERMARK_KEYSET"),
                capabilities.get(DatabaseOperation.SYNC_CURSOR));
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "KINGBASE_ORACLE_CI_ON_CONFLICT_EXACT_UNIQUE_KEY_COMMIT_RETURNED"),
                capabilities.get(DatabaseOperation.WRITE_UPSERT));
        assertEquals(OperationCapability.Status.DECLARED, capabilities.get(DatabaseOperation.DDL_CREATE).status());
        assertEquals(OperationCapability.Status.UNSUPPORTED, capabilities.get(DatabaseOperation.WRITE_COPY).status());
    }

    @Test void schemaDiffStillDoesNotPlanKingbaseAdds() {
        var column = column("extra", ColumnMetaData.TYPE_STRING, 30, 0);
        var plan = new SchemaDiff().planAddColumns(database, "s", "sample", List.of(column), new TableSchemaMetadata(List.of(), List.of()));
        assertFalse(plan.ready());
        assertTrue(plan.steps().isEmpty());
        assertEquals("UNVERIFIED_ADDITIVE_TARGET", plan.diagnostics().getFirst().code());
    }

    private String sql(ColumnDescription column) {
        return database.getAddColumnSql("s", "t", column);
    }

    private static String quote(String name) {
        return RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.KINGBASE, name);
    }

    private String typeToken(ColumnDescription column) {
        String definition = database.getFieldDefinition(column.getMetaData(), null, false, false, false);
        String prefix = " \"" + column.getFieldName() + "\"   ";
        assertTrue(definition.startsWith(prefix));
        return definition.substring(prefix.length());
    }

    private static void assertRejected(String reason, DatabaseKingbaseImpl dialect, ColumnDescription column) {
        var rejected = assertThrows(SQLFeatureNotSupportedException.class, () -> dialect.getAddColumnSql("s", "t", column));
        assertEquals(reason, rejected.getMessage());
        assertEquals("0A000", rejected.getSQLState());
        assertNull(rejected.getCause());
    }

    private static ColumnDescription unnamed(boolean nullable, boolean key, boolean identity, String defaultValue) {
        var column = column("c", ColumnMetaData.TYPE_STRING, 8, 0);
        column.setNullable(nullable);
        column.setPk(key);
        column.setAutoIncrement(identity);
        column.setDefaultValue(defaultValue);
        return column;
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

    private static ColumnMetadataEvidence evidence(MetadataValue<String> defaults, MetadataValue<Boolean> nullable,
                                                   MetadataValue<String> identity, MetadataValue<String> generated) {
        var unknown = ColumnMetadataEvidence.unknown();
        return new ColumnMetadataEvidence(unknown.provenance(), unknown.nativeType(), defaults, nullable,
                unknown.numericPrecision(), unknown.numericScale(), unknown.characterLength(), identity, generated,
                unknown.charset(), unknown.collation());
    }
}

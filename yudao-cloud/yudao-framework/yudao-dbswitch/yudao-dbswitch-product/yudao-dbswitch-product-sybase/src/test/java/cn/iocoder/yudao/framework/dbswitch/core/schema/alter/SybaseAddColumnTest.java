package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSybaseImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.schema.SchemaDiff;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.product.sybase.SybaseAddColumn;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.sql.SQLFeatureNotSupportedException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** SQL shape and refusal only. No Sybase connection is opened. */
class SybaseAddColumnTest {
    private final DatabaseSybaseImpl database = new DatabaseSybaseImpl();

    @Test
    void providerDeclaresQuotedNullableAddWithoutCertifyingAServer() throws Exception {
        var sybase = DbSwitchProductRegistry.discover().products().stream()
                .filter(product -> product.product() == ProductTypeEnum.SYBASE).findFirst().orElseThrow();
        var capabilities = sybase.capabilities();
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "SYBASE_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED"),
                capabilities.get(DatabaseOperation.DDL_ALTER));
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "SYBASE_ASE16_SP02_PL02_PINNED_GENERATION_SERIALIZABLE_ROWCOUNT_KEYSET"),
                capabilities.get(DatabaseOperation.SYNC_CURSOR));
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "SYBASE_ASE16_SP02_PL02_MERGE_EXACT_ORDERED_PRIMARY_KEY_COMMIT_RETURNED"),
                capabilities.get(DatabaseOperation.WRITE_UPSERT));
        assertEquals(OperationCapability.Status.DECLARED, capabilities.get(DatabaseOperation.DDL_CREATE).status());
        assertEquals(OperationCapability.Status.UNSUPPORTED, capabilities.get(DatabaseOperation.WRITE_COPY).status());

        try (var input = getClass().getClassLoader().getResourceAsStream("META-INF/dbswitch/products/sybase.json")) {
            assertNotNull(input);
            var manifest = new ObjectMapper().readTree(input);
            assertEquals("DECLARED", manifest.path("operations").path("DDL_ALTER").path("status").asText());
            assertEquals("SYBASE_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED",
                    manifest.path("operations").path("DDL_ALTER").path("reason").asText());
        }
    }

    @Test
    void nullableAddReusesFieldDefinitionAndBracketQuotes() {
        var column = column("note", ColumnMetaData.TYPE_STRING, 20, 0);
        column.setRemarks("not emitted");
        String sql = database.getAddColumnSql("app", "items", column);
        assertEquals(SybaseAddColumn.sql(database, "app", "items", column), sql);
        String definition = database.getFieldDefinition(column.getMetaData(), null, false, false, false);
        assertEquals(" [note]  VARCHAR(20) NULL", definition);
        assertEquals("ALTER TABLE " + quote("app") + "." + quote("items") + " ADD " + quote("note") + " VARCHAR(20) NULL", sql);
        assertEquals("ALTER TABLE [app].[items] ADD [note] VARCHAR(20) NULL", sql);
        assertFalse(sql.contains("ADD COLUMN"));
        assertFalse(sql.contains("`"));
        assertFalse(sql.contains("\""));
        assertFalse(sql.contains("NOT NULL"));
        assertFalse(sql.toUpperCase().contains(" DEFAULT"));
        assertFalse(sql.toUpperCase().contains(" IDENTITY"));
        assertFalse(sql.contains("not emitted"));
        assertFalse(sql.endsWith(";"));
    }

    @Test
    void declaredTypesStayOnTheExistingFieldDefinition() {
        assertEquals("ALTER TABLE [s].[t] ADD [c] VARCHAR(20) NULL", sql(column("c", ColumnMetaData.TYPE_STRING, 20, 0)));
        assertEquals("ALTER TABLE [s].[t] ADD [c] VARCHAR NULL", sql(column("c", ColumnMetaData.TYPE_STRING, 0, 0)));
        assertEquals("ALTER TABLE [s].[t] ADD [c] VARCHAR NULL", sql(column("c", ColumnMetaData.TYPE_STRING, -1, 0)));
        assertEquals("ALTER TABLE [s].[t] ADD [c] VARCHAR(2047) NULL", sql(column("c", ColumnMetaData.TYPE_STRING, 2047, 0)));
        assertEquals("ALTER TABLE [s].[t] ADD [c] TEXT NULL", sql(column("c", ColumnMetaData.TYPE_STRING, 2048, 0)));
        assertEquals("ALTER TABLE [s].[t] ADD [c] DECIMAL(10, 2) NULL", sql(column("c", ColumnMetaData.TYPE_BIGNUMBER, 10, 2)));
        assertEquals("ALTER TABLE [s].[t] ADD [c] DOUBLE PRECISION NULL", sql(column("c", ColumnMetaData.TYPE_NUMBER, 20, 0)));
        assertEquals("ALTER TABLE [s].[t] ADD [c] INTEGER NULL", sql(column("c", ColumnMetaData.TYPE_INTEGER, 9, 0)));
        assertEquals("ALTER TABLE [s].[t] ADD [c] DOUBLE PRECISION NULL", sql(column("c", ColumnMetaData.TYPE_INTEGER, 10, 0)));
        assertEquals("ALTER TABLE [s].[t] ADD [c] SMALLINT NULL", sql(column("c", ColumnMetaData.TYPE_INTEGER, 4, 0)));
        assertEquals("ALTER TABLE [s].[t] ADD [c] TINYINT NULL", sql(column("c", ColumnMetaData.TYPE_INTEGER, 2, 0)));
        assertEquals("ALTER TABLE [s].[t] ADD [c] DATETIME", sql(column("c", ColumnMetaData.TYPE_TIMESTAMP, 0, 0)));
        assertEquals("ALTER TABLE [s].[t] ADD [c] DATETIME", sql(column("c", ColumnMetaData.TYPE_TIME, 0, 0)));
        assertEquals("ALTER TABLE [s].[t] ADD [c] DATETIME", sql(column("c", ColumnMetaData.TYPE_DATE, 0, 0)));
        assertEquals("ALTER TABLE [s].[t] ADD [c] TINYINT", sql(column("c", ColumnMetaData.TYPE_BOOLEAN, 0, 0)));
        assertEquals("ALTER TABLE [s].[t] ADD [c] VARBINARY", sql(column("c", ColumnMetaData.TYPE_BINARY, 0, 0)));
        assertRejected("recognized field type", database, column("c", ColumnMetaData.TYPE_NONE, 0, 0));
    }

    @Test
    void bracketsDoubleAClosingBracketAndKeepTheGivenSpelling() {
        var column = column("MiX", ColumnMetaData.TYPE_STRING, 8, 0);
        assertEquals("ALTER TABLE [Sc].[Tb] ADD [MiX] VARCHAR(8) NULL", database.getAddColumnSql("Sc", "Tb", column));
        assertEquals("ALTER TABLE [Gov.Db].[A B] ADD [bad-name] VARCHAR(8) NULL",
                database.getAddColumnSql("Gov.Db", "A B", column("bad-name", ColumnMetaData.TYPE_STRING, 8, 0)));
        assertEquals("ALTER TABLE [dbo].[orders] ADD [a]]b] VARCHAR(8) NULL",
                database.getAddColumnSql("dbo", "orders", column("a]b", ColumnMetaData.TYPE_STRING, 8, 0)));
        assertEquals("[]]]", quote("]"));
        assertEquals("ALTER TABLE [s].[t] ADD []]] VARCHAR(8) NULL", sql(column("]", ColumnMetaData.TYPE_STRING, 8, 0)));
        assertEquals("ALTER TABLE [s].[t] ADD [select] VARCHAR(8) NULL", sql(column("select", ColumnMetaData.TYPE_STRING, 8, 0)));
        assertEquals(30, "n".repeat(30).length());
        assertTrue(sql(column("n".repeat(30), ColumnMetaData.TYPE_STRING, 8, 0)).contains(quote("n".repeat(30))));
    }

    @Test
    void remarksAndLabelDoNotChangeTheQuotedAdd() {
        var labeled = new ColumnDescription();
        labeled.setLabelName("note");
        labeled.setNullable(true);
        labeled.setValType(ColumnMetaData.TYPE_STRING);
        labeled.setLength(8);
        labeled.setRemarks("x'; DROP TABLE [Tb]");
        assertEquals("ALTER TABLE [s].[t] ADD [note] VARCHAR(8) NULL", sql(labeled));
    }

    @Test
    void notNullPrimaryKeyDefaultIdentityAndGeneratedAreRefused() {
        var dialect = spy(new DatabaseSybaseImpl());
        var required = column("note", ColumnMetaData.TYPE_STRING, 20, 0);
        required.setNullable(false);
        assertRejected("NOT NULL", dialect, required);

        var evidencedNull = column("note", ColumnMetaData.TYPE_STRING, 20, 0);
        evidencedNull.setMetadataEvidence(evidence(MetadataValue.unknown(), MetadataValue.value(false),
                MetadataValue.unknown(), MetadataValue.unknown()));
        assertRejected("NOT NULL", dialect, evidencedNull);

        var key = column("note", ColumnMetaData.TYPE_STRING, 20, 0);
        key.setPk(true);
        assertRejected("primary key", dialect, key);

        for (String fallback : new String[] {"0", "", "null", "NULL"}) {
            var withDefault = column("day", ColumnMetaData.TYPE_DATE, 0, 0);
            withDefault.setDefaultValue(fallback);
            assertRejected("default", dialect, withDefault);
        }
        var declaredDefault = column("note", ColumnMetaData.TYPE_STRING, 20, 0);
        declaredDefault.setMetadataEvidence(evidence(MetadataValue.value("'x'"), MetadataValue.value(true),
                MetadataValue.unknown(), MetadataValue.unknown()));
        assertRejected("default", dialect, declaredDefault);
        var unsupportedDefault = column("note", ColumnMetaData.TYPE_STRING, 20, 0);
        unsupportedDefault.setMetadataEvidence(evidence(MetadataValue.unsupported(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.unknown()));
        assertRejected("default", dialect, unsupportedDefault);

        var increment = column("id", ColumnMetaData.TYPE_INTEGER, 9, 0);
        increment.setAutoIncrement(true);
        assertRejected("identity", dialect, increment);
        var identity = column("id", ColumnMetaData.TYPE_INTEGER, 9, 0);
        identity.setMetadataEvidence(evidence(MetadataValue.unknown(), MetadataValue.value(true),
                MetadataValue.value("IDENTITY"), MetadataValue.unknown()));
        assertRejected("identity", dialect, identity);
        var namedIdentity = column("id", ColumnMetaData.TYPE_INTEGER, 9, 0);
        namedIdentity.setFieldTypeName("int identity");
        assertRejected("identity", dialect, namedIdentity);
        var unsupportedIdentity = column("id", ColumnMetaData.TYPE_INTEGER, 9, 0);
        unsupportedIdentity.setMetadataEvidence(evidence(MetadataValue.unknown(), MetadataValue.value(true),
                MetadataValue.unsupported(), MetadataValue.unknown()));
        assertRejected("identity", dialect, unsupportedIdentity);

        var generated = column("v", ColumnMetaData.TYPE_INTEGER, 9, 0);
        generated.setMetadataEvidence(evidence(MetadataValue.unknown(), MetadataValue.value(true),
                MetadataValue.unknown(), MetadataValue.value("AS(id+1)")));
        assertRejected("generated", dialect, generated);
        var namedGenerated = column("v", ColumnMetaData.TYPE_INTEGER, 9, 0);
        namedGenerated.setFieldTypeName("int generated always");
        assertRejected("generated", dialect, namedGenerated);

        var otherProduct = column("c", ColumnMetaData.TYPE_STRING, 8, 0);
        otherProduct.setDbType(ProductTypeEnum.SQLSERVER);
        assertRejected("another product", dialect, otherProduct);
        verify(dialect, never()).getFieldDefinition(any(), any(), anyBoolean(), anyBoolean(), anyBoolean());
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("app", "items", null));
    }

    @Test
    void fieldDefinitionThatStillContainsARejectedClauseIsRefused() {
        var dialect = spy(new DatabaseSybaseImpl());
        var column = column("note", ColumnMetaData.TYPE_STRING, 20, 0);
        doReturn(" [note]  VARCHAR(20) NOT NULL").when(dialect).getFieldDefinition(any(), isNull(), eq(false), eq(false), eq(false));
        assertRejected("NOT NULL", dialect, column);
        doReturn(" [note]  VARCHAR(20) DEFAULT 0").when(dialect).getFieldDefinition(any(), isNull(), eq(false), eq(false), eq(false));
        assertRejected("default", dialect, column);
        doReturn(" [note]  INTEGER IDENTITY").when(dialect).getFieldDefinition(any(), isNull(), eq(false), eq(false), eq(false));
        assertRejected("identity", dialect, column);
        doReturn(" [note]  INTEGER PRIMARY KEY").when(dialect).getFieldDefinition(any(), isNull(), eq(false), eq(false), eq(false));
        assertRejected("primary key", dialect, column);
        doReturn("note VARCHAR(20) NULL").when(dialect).getFieldDefinition(any(), isNull(), eq(false), eq(false), eq(false));
        assertRejected("existing quoted column layout", dialect, column);
    }

    @Test
    void physicalIdentifierRulesRejectBeforeSqlIsEmitted() {
        var column = column("note", ColumnMetaData.TYPE_STRING, 20, 0);
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql(null, "items", column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql(" ", "items", column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("app", "", column));
        assertThrows(IllegalArgumentException.class, () -> sql(column("n".repeat(31), ColumnMetaData.TYPE_STRING, 8, 0)));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("a\0b", "items", column));
        var dialect = spy(new DatabaseSybaseImpl());
        assertThrows(IllegalArgumentException.class, () -> dialect.getAddColumnSql("app", "items",
                column("n".repeat(31), ColumnMetaData.TYPE_STRING, 8, 0)));
        verify(dialect, never()).getFieldDefinition(any(), any(), anyBoolean(), anyBoolean(), anyBoolean());
        doReturn(ProductTypeEnum.SQLSERVER).when(dialect).getDatabaseType();
        assertRejected("does not apply to this product", dialect, column);
    }

    @Test
    void schemaDiffStillDoesNotPlanSybaseAdds() {
        var column = column("extra", ColumnMetaData.TYPE_STRING, 20, 0);
        var plan = new SchemaDiff().planAddColumns(database, "s", "sample", List.of(column),
                new TableSchemaMetadata(List.of(), List.of()));
        assertFalse(plan.ready());
        assertTrue(plan.steps().isEmpty());
        assertEquals("UNVERIFIED_ADDITIVE_TARGET", plan.diagnostics().getFirst().code());
    }

    private String sql(ColumnDescription column) {
        return database.getAddColumnSql("s", "t", column);
    }

    private static String quote(String name) {
        return RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.SYBASE, name);
    }

    private static void assertRejected(String reason, DatabaseSybaseImpl dialect, ColumnDescription column) {
        var rejected = assertThrows(SQLFeatureNotSupportedException.class,
                () -> dialect.getAddColumnSql("s", "t", column));
        assertTrue(rejected.getMessage().contains(reason), rejected.getMessage());
        assertFalse(rejected.getMessage().contains("ALTER TABLE"), rejected.getMessage());
        assertEquals("0A000", rejected.getSQLState());
        assertNull(rejected.getCause());
    }

    private static ColumnDescription column(String name, int type, int length, int precision) {
        var description = new ColumnDescription();
        description.setFieldName(name);
        description.setDbType(ProductTypeEnum.SYBASE);
        description.setNullable(true);
        description.setValType(type);
        description.setLength(length);
        description.setPrecision(precision);
        return description;
    }

    private static ColumnMetadataEvidence evidence(MetadataValue<String> defaults, MetadataValue<Boolean> nullable,
            MetadataValue<String> identity, MetadataValue<String> generated) {
        var unknown = ColumnMetadataEvidence.unknown();
        return new ColumnMetadataEvidence(unknown.provenance(), unknown.nativeType(), defaults, nullable,
                unknown.numericPrecision(), unknown.numericScale(), unknown.characterLength(), identity, generated,
                unknown.charset(), unknown.collation());
    }
}

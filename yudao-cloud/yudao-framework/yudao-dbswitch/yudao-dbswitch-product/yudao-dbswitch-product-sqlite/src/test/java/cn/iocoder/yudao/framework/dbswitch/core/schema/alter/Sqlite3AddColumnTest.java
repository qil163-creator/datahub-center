package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.AbstractDatabase;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqliteImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnSequenceMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.model.TableSchemaMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.schema.SchemaDiff;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.product.sqlite3.Sqlite3AddColumn;
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

/** SQL shape and refusal only. The 3.31.1 file probe is not repeated here. */
class Sqlite3AddColumnTest {
    private final DatabaseSqliteImpl database = new DatabaseSqliteImpl();

    @Test
    void providerDeclaresQuotedNullableAddWithoutCertifyingAServer() throws Exception {
        var sqlite = DbSwitchProductRegistry.discover().products().stream()
                .filter(product -> product.product() == ProductTypeEnum.SQLITE3).findFirst().orElseThrow();
        var capabilities = sqlite.capabilities();
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "SQLITE3_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED"),
                capabilities.get(DatabaseOperation.DDL_ALTER));
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "DEPLOYMENT_PINNED_FILE_INTEGER_WATERMARK_BINARY_KEYS"),
                capabilities.get(DatabaseOperation.SYNC_CURSOR));
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "EXPLICIT_NON_NULL_BINARY_UNIQUE_KEY_TRANSACTIONAL_UPSERT"),
                capabilities.get(DatabaseOperation.WRITE_UPSERT));
        assertEquals(OperationCapability.Status.DECLARED, capabilities.get(DatabaseOperation.DDL_CREATE).status());
        assertEquals(OperationCapability.Status.UNSUPPORTED, capabilities.get(DatabaseOperation.WRITE_COPY).status());

        try (var input = getClass().getClassLoader().getResourceAsStream("META-INF/dbswitch/products/sqlite3.json")) {
            assertNotNull(input);
            var manifest = new ObjectMapper().readTree(input);
            assertEquals("DECLARED", manifest.path("operations").path("DDL_ALTER").path("status").asText());
            assertEquals("SQLITE3_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED",
                    manifest.path("operations").path("DDL_ALTER").path("reason").asText());
        }
    }

    @Test
    void nullableAddReusesFieldDefinitionAndGuardQuotes() {
        var column = column("note", ColumnMetaData.TYPE_STRING, 20, 0);
        column.setRemarks("x'; DROP TABLE \"sample\"");
        String sql = database.getAddColumnSql("app", "items", column);
        assertEquals(Sqlite3AddColumn.sql(database, "app", "items", column), sql);
        String definition = database.getFieldDefinition(column.getMetaData(), null, false, false, false);
        assertEquals(" \"note\"   TEXT", definition);
        assertEquals("ALTER TABLE " + quote("app") + "." + quote("items") + " ADD COLUMN " + quote("note") + " TEXT", sql);
        assertEquals("ALTER TABLE \"app\".\"items\" ADD COLUMN \"note\" TEXT", sql);
        assertFalse(sql.contains("ADD COLUMN \"note\"   "));
        assertFalse(sql.contains("`"));
        assertFalse(sql.contains("NOT NULL"));
        assertFalse(sql.toUpperCase().contains(" DEFAULT"));
        assertFalse(sql.toUpperCase().contains("PRIMARY"));
        assertFalse(sql.toUpperCase().contains("UNIQUE"));
        assertFalse(sql.contains("DROP TABLE"));
        assertFalse(sql.endsWith(";"));
    }

    @Test
    void declaredTypesStayOnTheExistingFieldDefinition() {
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" TEXT", sql(column("c", ColumnMetaData.TYPE_STRING, 20, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" TEXT", sql(column("c", ColumnMetaData.TYPE_STRING, 1, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" TEXT",
                sql(column("c", ColumnMetaData.TYPE_STRING, AbstractDatabase.CLOB_LENGTH - 1, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" TEXT", sql(column("c", ColumnMetaData.TYPE_STRING, 0, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" TEXT", sql(column("c", ColumnMetaData.TYPE_STRING, -1, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" TEXT",
                sql(column("c", ColumnMetaData.TYPE_STRING, AbstractDatabase.CLOB_LENGTH, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" INTEGER", sql(column("c", ColumnMetaData.TYPE_INTEGER, 18, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" INTEGER", sql(column("c", ColumnMetaData.TYPE_INTEGER, 0, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" NUMERIC", sql(column("c", ColumnMetaData.TYPE_INTEGER, 19, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" NUMERIC", sql(column("c", ColumnMetaData.TYPE_NUMBER, 10, 2)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" NUMERIC", sql(column("c", ColumnMetaData.TYPE_BIGNUMBER, -1, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" BLOB", sql(column("c", ColumnMetaData.TYPE_BINARY, 0, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" CHAR(1)", sql(column("c", ColumnMetaData.TYPE_BOOLEAN, 0, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" DATETIME", sql(column("c", ColumnMetaData.TYPE_DATE, 0, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" DATETIME", sql(column("c", ColumnMetaData.TYPE_TIME, 0, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"c\" DATETIME", sql(column("c", ColumnMetaData.TYPE_TIMESTAMP, 0, 0)));
    }

    @Test
    void blankSchemaIsMainAndPhysicalQuotesKeepEmbeddedQuotes() {
        var column = column("a\"b", ColumnMetaData.TYPE_STRING, 8, 0);
        assertEquals("ALTER TABLE \"main\".\"items\" ADD COLUMN \"a\"\"b\" TEXT", database.getAddColumnSql(null, "items", column));
        assertEquals("ALTER TABLE \"main\".\"items\" ADD COLUMN \"a\"\"b\" TEXT", database.getAddColumnSql(" ", "items", column));
        assertEquals("ALTER TABLE \"main\".\"items\" ADD COLUMN \"a\"\"b\" TEXT", database.getAddColumnSql("main", "items", column));
        assertEquals("ALTER TABLE \"sc hema\".\"a b\" ADD COLUMN \"select\" INTEGER",
                database.getAddColumnSql("sc hema", "a b", column("select", ColumnMetaData.TYPE_INTEGER, 9, 0)));
        assertEquals("ALTER TABLE \"a\"\"b\".\"held\" ADD COLUMN \"c\"\"d\" NUMERIC",
                database.getAddColumnSql("a\"b", "held", column("c\"d", ColumnMetaData.TYPE_NUMBER, 10, 2)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"\"\"\" TEXT", sql(column("\"", ColumnMetaData.TYPE_STRING, 4, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"bad-name\" TEXT", sql(column("bad-name", ColumnMetaData.TYPE_STRING, 8, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"a;b\" TEXT", sql(column("a;b", ColumnMetaData.TYPE_STRING, 8, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \" \" CHAR(1)", sql(column(" ", ColumnMetaData.TYPE_BOOLEAN, 0, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"中文\" DATETIME", sql(column("中文", ColumnMetaData.TYPE_DATE, 0, 0)));
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"a\nb\" TEXT", sql(column("a\nb", ColumnMetaData.TYPE_STRING, 8, 0)));
    }

    @Test
    void remarksLabelAndNonUniqueIndexDoNotChangeTheQuotedAdd() {
        var labeled = new ColumnDescription();
        labeled.setLabelName("note");
        labeled.setNullable(true);
        labeled.setValType(ColumnMetaData.TYPE_STRING);
        labeled.setLength(8);
        labeled.setRemarks("not emitted");
        labeled.setFieldTypeName("VARCHAR(20)");
        labeled.setIndexName("ix_note");
        labeled.setNonIndexUnique(true);
        assertEquals("ALTER TABLE \"s\".\"t\" ADD COLUMN \"note\" TEXT", sql(labeled));
    }

    @Test
    void notNullPrimaryKeyUniqueDefaultIdentityAndGeneratedAreRefused() {
        var dialect = spy(new DatabaseSqliteImpl());
        var required = column("note", ColumnMetaData.TYPE_STRING, 20, 0);
        required.setNullable(false);
        assertRejected("NOT NULL", dialect, required);

        var evidencedNull = column("note", ColumnMetaData.TYPE_STRING, 20, 0);
        evidencedNull.setMetadataEvidence(evidence(MetadataValue.unknown(), MetadataValue.value(false),
                MetadataValue.unknown(), MetadataValue.unknown()));
        assertRejected("NOT NULL", dialect, evidencedNull);
        var declaredNotNull = column("note", ColumnMetaData.TYPE_STRING, 20, 0);
        declaredNotNull.setFieldTypeName("TEXT NOT NULL");
        assertRejected("NOT NULL", dialect, declaredNotNull);

        var key = column("note", ColumnMetaData.TYPE_STRING, 20, 0);
        key.setPk(true);
        assertRejected("primary key", dialect, key);
        var declaredKey = column("id", ColumnMetaData.TYPE_INTEGER, 9, 0);
        declaredKey.setFieldTypeName("INTEGER PRIMARY KEY");
        assertRejected("primary key", dialect, declaredKey);

        var unique = column("note", ColumnMetaData.TYPE_STRING, 20, 0);
        unique.setFieldTypeName("TEXT UNIQUE");
        assertRejected("unique", dialect, unique);
        var uniqueIndex = column("note", ColumnMetaData.TYPE_STRING, 20, 0);
        uniqueIndex.setIndexName("uq_note");
        assertRejected("unique", dialect, uniqueIndex);

        for (String fallback : new String[] {"0", "", "null", "NULL", "CURRENT_TIMESTAMP"}) {
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
        var namedDefault = column("note", ColumnMetaData.TYPE_STRING, 20, 0);
        namedDefault.setFieldTypeName("TEXT DEFAULT 'x'");
        assertRejected("default", dialect, namedDefault);

        var increment = column("id", ColumnMetaData.TYPE_INTEGER, 9, 0);
        increment.setAutoIncrement(true);
        assertRejected("identity", dialect, increment);
        var identity = column("id", ColumnMetaData.TYPE_INTEGER, 9, 0);
        identity.setMetadataEvidence(evidence(MetadataValue.unknown(), MetadataValue.value(true),
                MetadataValue.value("AUTOINCREMENT"), MetadataValue.unknown()));
        assertRejected("identity", dialect, identity);
        var namedIdentity = column("id", ColumnMetaData.TYPE_INTEGER, 9, 0);
        namedIdentity.setFieldTypeName("integer autoincrement");
        assertRejected("identity", dialect, namedIdentity);
        var unsupportedIdentity = column("id", ColumnMetaData.TYPE_INTEGER, 9, 0);
        unsupportedIdentity.setMetadataEvidence(evidence(MetadataValue.unknown(), MetadataValue.value(true),
                MetadataValue.unsupported(), MetadataValue.unknown()));
        assertRejected("identity", dialect, unsupportedIdentity);
        var sequenced = column("id", ColumnMetaData.TYPE_INTEGER, 9, 0);
        sequenced.setMetadataEvidence(sequence(MetadataValue.unsupported()));
        assertRejected("identity", dialect, sequenced);
        var owned = column("id", ColumnMetaData.TYPE_INTEGER, 9, 0);
        owned.setMetadataEvidence(sequence(MetadataValue.value(new ColumnSequenceMetadata(
                ColumnSequenceMetadata.Kind.IDENTITY_ALWAYS, "main", "seq", "integer",
                1, 1, 1, 100, 1, false, MetadataValue.absent()))));
        assertRejected("identity", dialect, owned);

        var generated = column("v", ColumnMetaData.TYPE_INTEGER, 9, 0);
        generated.setMetadataEvidence(evidence(MetadataValue.unknown(), MetadataValue.value(true),
                MetadataValue.unknown(), MetadataValue.value("AS (id+1)")));
        assertRejected("generated", dialect, generated);
        var stored = column("v", ColumnMetaData.TYPE_INTEGER, 9, 0);
        stored.setFieldTypeName("INTEGER GENERATED ALWAYS AS (1) STORED");
        assertRejected("generated", dialect, stored);
        var virtual = column("v", ColumnMetaData.TYPE_INTEGER, 9, 0);
        virtual.setFieldTypeName("INTEGER GENERATED ALWAYS AS (1) VIRTUAL");
        assertRejected("generated", dialect, virtual);
        var unsupportedGenerated = column("v", ColumnMetaData.TYPE_INTEGER, 9, 0);
        unsupportedGenerated.setMetadataEvidence(evidence(MetadataValue.unknown(), MetadataValue.value(true),
                MetadataValue.unknown(), MetadataValue.unsupported()));
        assertRejected("generated", dialect, unsupportedGenerated);

        var check = column("v", ColumnMetaData.TYPE_STRING, 8, 0);
        check.setFieldTypeName("TEXT CHECK (1)");
        assertRejected("check", dialect, check);
        var reference = column("v", ColumnMetaData.TYPE_INTEGER, 9, 0);
        reference.setFieldTypeName("INTEGER REFERENCES other(id)");
        assertRejected("foreign key", dialect, reference);
        var collation = column("v", ColumnMetaData.TYPE_STRING, 8, 0);
        collation.setFieldTypeName("TEXT COLLATE NOCASE");
        assertRejected("collation", dialect, collation);

        var unknown = column("c", ColumnMetaData.TYPE_NONE, 0, 0);
        assertRejected("recognized field type", dialect, unknown);
        var address = column("c", ColumnMetaData.TYPE_INET, 0, 0);
        assertRejected("recognized field type", dialect, address);
        var otherProduct = column("c", ColumnMetaData.TYPE_STRING, 8, 0);
        otherProduct.setDbType(ProductTypeEnum.MYSQL);
        assertRejected("another product", dialect, otherProduct);
        verify(dialect, never()).getFieldDefinition(any(), isNull(), anyBoolean(), anyBoolean(), anyBoolean());
    }

    @Test
    void fieldDefinitionThatStillContainsARejectedClauseIsRefused() {
        var dialect = spy(new DatabaseSqliteImpl());
        var column = column("note", ColumnMetaData.TYPE_STRING, 20, 0);
        doReturn(" \"note\"   TEXT NOT NULL").when(dialect).getFieldDefinition(any(), isNull(), eq(false), eq(false), eq(false));
        assertRejected("NOT NULL", dialect, column);
        doReturn(" \"note\"   TEXT UNIQUE").when(dialect).getFieldDefinition(any(), isNull(), eq(false), eq(false), eq(false));
        assertRejected("unique", dialect, column);
        doReturn(" \"note\"   INTEGER DEFAULT 0").when(dialect).getFieldDefinition(any(), isNull(), eq(false), eq(false), eq(false));
        assertRejected("default", dialect, column);
        doReturn(" \"note\"   INTEGER PRIMARY KEY AUTOINCREMENT").when(dialect)
                .getFieldDefinition(any(), isNull(), eq(false), eq(false), eq(false));
        assertRejected("primary key", dialect, column);
        doReturn(" \"note\"   INTEGER GENERATED ALWAYS AS (1) STORED").when(dialect)
                .getFieldDefinition(any(), isNull(), eq(false), eq(false), eq(false));
        assertRejected("generated", dialect, column);
        doReturn(" \"note\"   INTEGER GENERATED ALWAYS AS (1) VIRTUAL").when(dialect)
                .getFieldDefinition(any(), isNull(), eq(false), eq(false), eq(false));
        assertRejected("generated", dialect, column);
        doReturn(" \"note\"   TEXT CHECK (1)").when(dialect).getFieldDefinition(any(), isNull(), eq(false), eq(false), eq(false));
        assertRejected("check", dialect, column);
        doReturn(" \"note\"   INTEGER REFERENCES other(id)").when(dialect)
                .getFieldDefinition(any(), isNull(), eq(false), eq(false), eq(false));
        assertRejected("foreign key", dialect, column);
        doReturn(" \"note\"   TEXT COLLATE NO_SUCH").when(dialect).getFieldDefinition(any(), isNull(), eq(false), eq(false), eq(false));
        assertRejected("collation", dialect, column);
        doReturn(" \"note\"   BIGINT ").when(dialect).getFieldDefinition(any(), isNull(), eq(false), eq(false), eq(false));
        assertRejected("field definition", dialect, column);
        doReturn("note TEXT").when(dialect).getFieldDefinition(any(), isNull(), eq(false), eq(false), eq(false));
        assertRejected("existing quoted column layout", dialect, column);
    }

    @Test
    void physicalIdentifierRulesRejectBeforeSqlIsEmitted() {
        var column = column("note", ColumnMetaData.TYPE_STRING, 20, 0);
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("app", "", column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("app", "items",
                column("", ColumnMetaData.TYPE_STRING, 8, 0)));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("a\0b", "items", column));
        assertThrows(IllegalArgumentException.class, () -> sql(column("n\0", ColumnMetaData.TYPE_STRING, 8, 0)));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("app", "items", null));
        assertThrows(IllegalArgumentException.class, () -> Sqlite3AddColumn.sql(null, "app", "items", column));
        var dialect = spy(new DatabaseSqliteImpl());
        assertThrows(IllegalArgumentException.class, () -> dialect.getAddColumnSql("app", "items",
                column("", ColumnMetaData.TYPE_STRING, 8, 0)));
        verify(dialect, never()).getFieldDefinition(any(), isNull(), anyBoolean(), anyBoolean(), anyBoolean());
        doReturn(ProductTypeEnum.MYSQL).when(dialect).getDatabaseType();
        assertRejected("does not apply to this product", dialect, column);
    }

    @Test
    void schemaDiffStillDoesNotPlanSqliteAdds() {
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
        return RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.SQLITE3, name);
    }

    private static void assertRejected(String reason, DatabaseSqliteImpl dialect, ColumnDescription column) {
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
        description.setDbType(ProductTypeEnum.SQLITE3);
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

    private static ColumnMetadataEvidence sequence(MetadataValue<ColumnSequenceMetadata> sequence) {
        var unknown = ColumnMetadataEvidence.unknown();
        return new ColumnMetadataEvidence(unknown.provenance(), unknown.nativeType(), unknown.defaultExpression(),
                MetadataValue.value(true), unknown.numericPrecision(), unknown.numericScale(), unknown.characterLength(),
                unknown.identity(), unknown.generatedExpression(), unknown.charset(), unknown.collation(), sequence);
    }
}

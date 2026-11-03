package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseTdengineImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnSequenceMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.product.tdengine.TdengineAddColumn;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Generator contract only. No TDengine connection is opened. */
class TdengineAddColumnTest {
    private final DatabaseTdengineImpl database = new DatabaseTdengineImpl();

    @Test
    void providerDeclaresQuotedNullableAddWithoutCertifyingAServer() {
        var tdengine = DbSwitchProductRegistry.discover().products().stream()
                .filter(product -> product.product() == ProductTypeEnum.TDENGINE).findFirst().orElseThrow();
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "TDENGINE_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED"),
                tdengine.capabilities().get(DatabaseOperation.DDL_ALTER));
    }

    @Test
    void nullableOrdinaryColumnReusesFieldDefinitionAndBackticks() throws Exception {
        var note = column("note", ColumnMetaData.TYPE_STRING, 20, 0);
        String sql = database.getAddColumnSql("app", "items", note);
        assertEquals(alter("app", "items", "note", "NCHAR(20)"), sql);
        assertEquals(sql, TdengineAddColumn.sql(database, "app", "items", note));
        assertEquals("ALTER TABLE " + quote("app") + "." + quote("items") + " ADD COLUMN"
                + database.getFieldDefinition(note.getMetaData(), null, false, false, false), sql);
        assertFalse(sql.contains("\""));
        assertFalse(sql.contains("ALTER STABLE"));
        assertFalse(sql.contains("ADD TAG"));
        assertFalse(sql.toUpperCase(Locale.ROOT).contains("NOT NULL"));
        assertFalse(sql.toUpperCase(Locale.ROOT).contains(" DEFAULT"));
        assertFalse(sql.contains("COMPOSITE KEY"));
        assertFalse(sql.contains(";"));

        assertEquals(alter("app", "items", "Qty", "INT"),
                database.getAddColumnSql("app", "items", column("Qty", ColumnMetaData.TYPE_INTEGER, 9, 0)));
        assertEquals(alter("app", "items", "wide", "BIGINT"),
                database.getAddColumnSql("app", "items", column("wide", ColumnMetaData.TYPE_INTEGER, 15, 0)));
        assertEquals(alter("app", "items", "amount", "DECIMAL(10,2)"),
                database.getAddColumnSql("app", "items", column("amount", ColumnMetaData.TYPE_BIGNUMBER, 10, 2)));
        var decimal = column("ratio", ColumnMetaData.TYPE_NUMBER, 8, 0);
        decimal.setFieldType(Types.DECIMAL);
        assertEquals(alter("app", "items", "ratio", "DECIMAL(8,0)"), database.getAddColumnSql("app", "items", decimal));
        assertEquals(alter("app", "items", "score", "DOUBLE"),
                database.getAddColumnSql("app", "items", column("score", ColumnMetaData.TYPE_NUMBER, -1, -1)));
        assertEquals(alter("app", "items", "flag", "BOOL"),
                database.getAddColumnSql("app", "items", column("flag", ColumnMetaData.TYPE_BOOLEAN, 0, 0)));
        assertEquals(alter("app", "items", "payload", "VARBINARY(16)"),
                database.getAddColumnSql("app", "items", column("payload", ColumnMetaData.TYPE_BINARY, 16, 0)));
        assertEquals(alter("app", "items", "blob", "VARBINARY(256)"),
                database.getAddColumnSql("app", "items", column("blob", ColumnMetaData.TYPE_BINARY, 0, 0)));
        assertEquals(alter("app", "items", "body", "NCHAR(256)"),
                database.getAddColumnSql("app", "items", column("body", ColumnMetaData.TYPE_STRING, 0, 0)));
        assertEquals(alter("app", "items", "seen", "TIMESTAMP"),
                database.getAddColumnSql("app", "items", column("seen", ColumnMetaData.TYPE_TIMESTAMP, 0, 0)));
        assertEquals(alter("app", "items", "table", "INT"),
                database.getAddColumnSql("app", "items", column("table", ColumnMetaData.TYPE_INTEGER, 4, 0)));

        String longName = "c" + "a".repeat(62);
        assertEquals(63, longName.length());
        assertEquals(alter("app", "items", longName, "BOOL"),
                database.getAddColumnSql("app", "items", column(longName, ColumnMetaData.TYPE_BOOLEAN, 0, 0)));
    }

    @Test
    void notNullPrimaryKeyDefaultAndGeneratedColumnsAreRefused() {
        var required = column("note", ColumnMetaData.TYPE_STRING, 20, 0);
        required.setNullable(false);
        assertRejected("NOT NULL", () -> database.getAddColumnSql("app", "items", required));

        var evidenced = column("note", ColumnMetaData.TYPE_STRING, 20, 0);
        evidence(evidenced, MetadataValue.unknown(), MetadataValue.value(false), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.unknown());
        assertRejected("NOT NULL", () -> database.getAddColumnSql("app", "items", evidenced));
        var unknownNull = column("note", ColumnMetaData.TYPE_STRING, 20, 0);
        evidence(unknownNull, MetadataValue.unknown(), MetadataValue.unsupported(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.unknown());
        assertRejected("NOT NULL", () -> TdengineAddColumn.sql(database, "app", "items", unknownNull));

        var timestampKey = column("ts", ColumnMetaData.TYPE_TIMESTAMP, 0, 0);
        timestampKey.setPk(true);
        assertRejected("TIMESTAMP primary key", () -> database.getAddColumnSql("app", "items", timestampKey));
        var compositeTimestamp = column("ts", ColumnMetaData.TYPE_TIMESTAMP, 0, 0);
        compositeTimestamp.setRemarks("COMPOSITE KEY");
        assertRejected("TIMESTAMP primary key", () -> TdengineAddColumn.sql(database, "app", "items", compositeTimestamp));

        var key = column("id", ColumnMetaData.TYPE_INTEGER, 9, 0);
        key.setPk(true);
        assertRejected("primary key", () -> database.getAddColumnSql("app", "items", key));
        var composite = column("id", ColumnMetaData.TYPE_INTEGER, 9, 0);
        composite.setRemarks("COMPOSITE KEY");
        assertRejected("primary key", () -> database.getAddColumnSql("app", "items", composite));

        var literalDefault = column("note", ColumnMetaData.TYPE_STRING, 20, 0);
        literalDefault.setDefaultValue("0");
        assertRejected("default", () -> database.getAddColumnSql("app", "items", literalDefault));
        var wordNull = column("note", ColumnMetaData.TYPE_STRING, 20, 0);
        wordNull.setDefaultValue("null");
        assertEquals(database.getAddColumnSql("app", "items", column("note", ColumnMetaData.TYPE_STRING, 20, 0)),
                database.getAddColumnSql("app", "items", wordNull));
        var declaredDefault = column("note", ColumnMetaData.TYPE_STRING, 20, 0);
        evidence(declaredDefault, MetadataValue.value("'x'"), MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.unknown());
        assertRejected("default", () -> database.getAddColumnSql("app", "items", declaredDefault));
        var unknownDefault = column("note", ColumnMetaData.TYPE_STRING, 20, 0);
        evidence(unknownDefault, MetadataValue.unsupported(), MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.unknown());
        assertRejected("default", () -> TdengineAddColumn.sql(database, "app", "items", unknownDefault));

        var increment = column("id", ColumnMetaData.TYPE_INTEGER, 9, 0);
        increment.setAutoIncrement(true);
        assertRejected("generated column", () -> database.getAddColumnSql("app", "items", increment));
        var identity = column("id", ColumnMetaData.TYPE_INTEGER, 9, 0);
        evidence(identity, MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.value("ALWAYS"),
                MetadataValue.unknown(), MetadataValue.unknown());
        assertRejected("generated column", () -> TdengineAddColumn.sql(database, "app", "items", identity));
        var generated = column("v", ColumnMetaData.TYPE_INTEGER, 9, 0);
        evidence(generated, MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.value("id + 1"), MetadataValue.unknown());
        assertRejected("generated column", () -> database.getAddColumnSql("app", "items", generated));
        var sequence = column("id", ColumnMetaData.TYPE_INTEGER, 9, 0);
        evidence(sequence, MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.unsupported());
        assertRejected("generated column", () -> TdengineAddColumn.sql(database, "app", "items", sequence));
        var named = column("v", ColumnMetaData.TYPE_INTEGER, 9, 0);
        named.setFieldTypeName("INT GENERATED");
        assertRejected("generated column", () -> database.getAddColumnSql("app", "items", named));
    }

    @Test
    void tagsSuperTablesAndChildTablesAreRefused() throws Exception {
        var tag = column("location", ColumnMetaData.TYPE_STRING, 32, 0);
        tag.setRemarks("TAG");
        assertRejected("TAG", () -> database.getAddColumnSql("app", "items", tag));
        var json = column("info", ColumnMetaData.TYPE_STRING, 32, 0);
        json.setFieldTypeName("JSON");
        assertRejected("TAG", () -> TdengineAddColumn.sql(database, "app", "items", json));

        var column = column("extra", ColumnMetaData.TYPE_INTEGER, 9, 0);
        assertRejected("super table", () -> TdengineAddColumn.sql(database, "app", "meters", column, "SUPER_TABLE"));
        assertRejected("super table", () -> TdengineAddColumn.sql(database, "app", "meters", column, "STABLE"));
        assertRejected("child table", () -> TdengineAddColumn.sql(database, "app", "d001", column, "CHILD_TABLE"));
        assertRejected("unverified table kind", () -> TdengineAddColumn.sql(database, "app", "items", column, null));

        String sql = TdengineAddColumn.sql(database, "app", "items", column, "NORMAL_TABLE");
        assertTrue(sql.startsWith("ALTER TABLE "));
        assertFalse(sql.contains("STABLE"));
        assertFalse(sql.contains("TAG"));
    }

    @Test
    void namesOutsideTheOrdinaryQuotedSubsetFailBeforeSqlIsBuilt() {
        var column = column("note", ColumnMetaData.TYPE_STRING, 20, 0);
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("`app`", "items", column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("app", "", column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql(null, "items", column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("app", "items",
                column("bad-name", ColumnMetaData.TYPE_STRING, 20, 0)));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("app", "items",
                column("c" + "a".repeat(63), ColumnMetaData.TYPE_BOOLEAN, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("app", "items",
                column("day", ColumnMetaData.TYPE_DATE, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("app", "items",
                column("amount", ColumnMetaData.TYPE_BIGNUMBER, 0, 0)));
    }

    private static void assertRejected(String reason, Executable call) {
        var failure = assertThrows(SQLFeatureNotSupportedException.class, call);
        assertEquals("0A000", failure.getSQLState());
        assertTrue(failure.getMessage().contains(reason), failure.getMessage());
    }

    private static String alter(String schema, String table, String column, String type) {
        return "ALTER TABLE " + quote(schema) + "." + quote(table) + " ADD COLUMN " + quote(column) + " " + type;
    }

    private static String quote(String name) {
        return RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.TDENGINE, name);
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

package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseHiveImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnSequenceMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.product.hive.HiveAddColumn;
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

/** Generator contract only. No Hive connection is opened. */
class HiveAddColumnTest {
    private final DatabaseHiveImpl database = new DatabaseHiveImpl();

    @Test
    void providerDeclaresOrdinaryExternalTextWithoutClaimingAServer() {
        var hive = DbSwitchProductRegistry.discover().products().stream()
                .filter(product -> product.product() == ProductTypeEnum.HIVE).findFirst().orElseThrow();
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "HIVE_TEXT_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED"),
                hive.capabilities().get(DatabaseOperation.DDL_ALTER));
    }

    @Test
    void nullableExternalTextColumnUsesQuotedAddColumnsAndExistingFieldDefinition() throws Exception {
        var column = column("Qty", Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0);
        String sql = database.getAddColumnSql("App_1", "Orders", column);
        assertEquals(alter("App_1", "Orders", "Qty", "BIGINT"), sql);
        assertEquals(sql, HiveAddColumn.sql(database, "App_1", "Orders", column, HiveAddColumn.ORDINARY_EXTERNAL_TEXTFILE));
        assertFalse(sql.toLowerCase().contains("not null"));
        assertFalse(sql.toLowerCase().contains("default"));
        assertFalse(sql.toLowerCase().contains("cascade"));
        assertFalse(sql.toLowerCase().contains("replace"));
        assertFalse(sql.toLowerCase().contains("restrict"));
        assertFalse(sql.toLowerCase().contains("partition"));
        assertFalse(sql.toLowerCase().contains("comment"));
        assertTrue(sql.contains("ADD COLUMNS ("));
        assertFalse(sql.contains("ADD COLUMN "));
        String definition = database.getFieldDefinition(column.getMetaData(), List.of(), false, false, false);
        assertTrue(definition.endsWith(" BIGINT"), definition);

        assertEquals(alter("public", "sample", "amount", "DECIMAL(10,2)"),
                database.getAddColumnSql("public", "sample",
                        column("amount", Types.DECIMAL, ColumnMetaData.TYPE_NUMBER, 10, 2)));
        assertEquals(alter("public", "sample", "wide", "DECIMAL(20,0)"),
                HiveAddColumn.sql(database, "public", "sample",
                        column("wide", Types.BIGINT, ColumnMetaData.TYPE_BIGNUMBER, 20, 0)));
        assertEquals(alter("public", "sample", "note", "STRING"),
                HiveAddColumn.sql(database, "public", "sample",
                        column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 10, 0)));
        assertEquals(alter("public", "sample", "payload", "BINARY"),
                HiveAddColumn.sql(database, "public", "sample",
                        column("payload", Types.BINARY, ColumnMetaData.TYPE_BINARY, -1, 0)));
        assertEquals(alter("public", "sample", "ratio", "DOUBLE"),
                HiveAddColumn.sql(database, "public", "sample",
                        column("ratio", Types.DOUBLE, ColumnMetaData.TYPE_NUMBER, -1, -1)));
        assertEquals(alter("public", "sample", "approx", "FLOAT"),
                HiveAddColumn.sql(database, "public", "sample",
                        column("approx", Types.REAL, ColumnMetaData.TYPE_NUMBER, -1, -1)));
        assertEquals(alter("public", "sample", "flag", "BOOLEAN"),
                HiveAddColumn.sql(database, "public", "sample",
                        column("flag", Types.BOOLEAN, ColumnMetaData.TYPE_BOOLEAN, 0, 0)));
        assertEquals(alter("public", "sample", "day", "DATE"),
                HiveAddColumn.sql(database, "public", "sample",
                        column("day", Types.DATE, ColumnMetaData.TYPE_DATE, 0, 0)));
        assertEquals(alter("public", "sample", "seen", "TIMESTAMP"),
                HiveAddColumn.sql(database, "public", "sample",
                        column("seen", Types.TIMESTAMP, ColumnMetaData.TYPE_TIMESTAMP, 0, 0)));
        assertEquals(alter("public", "sample", "small", "SMALLINT"),
                HiveAddColumn.sql(database, "public", "sample",
                        column("small", Types.TINYINT, ColumnMetaData.TYPE_INTEGER, 3, 0)));
        assertEquals(alter("public", "sample", "mid", "INT"),
                HiveAddColumn.sql(database, "public", "sample",
                        column("mid", Types.SMALLINT, ColumnMetaData.TYPE_INTEGER, 5, 0)));

        var noted = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 10, 0);
        noted.setRemarks("later");
        assertFalse(database.getAddColumnSql("public", "sample", noted).toLowerCase().contains("comment"));

        String longName = "c" + "a".repeat(62);
        assertEquals(63, longName.length());
        assertEquals(alter("public", "sample", longName, "BOOLEAN"),
                database.getAddColumnSql("public", "sample",
                        column(longName, Types.BOOLEAN, ColumnMetaData.TYPE_BOOLEAN, 0, 0)));
    }

    @Test
    void shapeNullabilityDefaultGeneratedCascadeAndReplaceAreRejected() {
        var column = column("extra", Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0);
        assertRejected("views", () -> HiveAddColumn.sql(database, "public", "sample", column, shape(false, false, true, "ADD")));
        assertRejected("views", () -> HiveAddColumn.sql(database, "public", "sample", column,
                new HiveAddColumn.TableShape("VIRTUAL_VIEW", HiveAddColumn.TEXT_INPUT, HiveAddColumn.LAZY_SERDE,
                        false, false, false, "ADD")));
        assertRejected("partitioned", () -> HiveAddColumn.sql(database, "public", "sample", column, shape(true, false, false, "ADD")));
        assertRejected("ACID", () -> HiveAddColumn.sql(database, "public", "sample", column, shape(false, true, false, "ADD")));
        assertRejected("ordinary external TEXTFILE", () -> HiveAddColumn.sql(database, "public", "sample", column,
                new HiveAddColumn.TableShape("MANAGED_TABLE", HiveAddColumn.TEXT_INPUT, HiveAddColumn.LAZY_SERDE,
                        false, false, false, "ADD")));
        assertRejected("ordinary external TEXTFILE", () -> HiveAddColumn.sql(database, "public", "sample", column,
                new HiveAddColumn.TableShape(HiveAddColumn.EXTERNAL_TABLE, "org.apache.hadoop.hive.ql.io.orc.OrcInputFormat",
                        HiveAddColumn.LAZY_SERDE, false, false, false, "ADD")));
        assertRejected("ordinary external TEXTFILE", () -> HiveAddColumn.sql(database, "public", "sample", column,
                new HiveAddColumn.TableShape(null, null, null, false, false, false, "ADD")));
        assertRejected("unknown table", () -> HiveAddColumn.sql(database, "public", "sample", column, null));
        assertRejected("CASCADE", () -> HiveAddColumn.sql(database, "public", "sample", column, shape(false, false, false, "CASCADE")));
        assertRejected("REPLACE", () -> HiveAddColumn.sql(database, "public", "sample", column, shape(false, false, false, "REPLACE")));
        assertRejected("RESTRICT", () -> HiveAddColumn.sql(database, "public", "sample", column, shape(false, false, false, "RESTRICT")));
        assertRejected("unknown alteration", () -> HiveAddColumn.sql(database, "public", "sample", column, shape(false, false, false, null)));

        var required = column("extra", Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0);
        required.setNullable(false);
        assertRejected("NOT NULL", () -> database.getAddColumnSql("public", "sample", required));
        var evidenced = column("extra", Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0);
        evidence(evidenced, MetadataValue.unknown(), MetadataValue.value(false), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.unknown());
        assertRejected("NOT NULL", () -> HiveAddColumn.sql(database, "public", "sample", evidenced));
        var unsupportedNull = column("extra", Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0);
        evidence(unsupportedNull, MetadataValue.unknown(), MetadataValue.unsupported(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.unknown());
        assertRejected("NOT NULL", () -> HiveAddColumn.sql(database, "public", "sample", unsupportedNull));

        var key = column("extra", Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0);
        key.setPk(true);
        assertRejected("primary key", () -> database.getAddColumnSql("public", "sample", key));

        var literalDefault = column("extra", Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0);
        literalDefault.setDefaultValue("NULL");
        assertRejected("default", () -> database.getAddColumnSql("public", "sample", literalDefault));
        var expressionDefault = column("extra", Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0);
        evidence(expressionDefault, MetadataValue.value("0"), MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.unknown());
        assertRejected("default", () -> HiveAddColumn.sql(database, "public", "sample", expressionDefault));

        var serial = column("extra", Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0);
        serial.setAutoIncrement(true);
        assertRejected("generated column", () -> database.getAddColumnSql("public", "sample", serial));
        var generated = column("extra", Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0);
        evidence(generated, MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.value("(id + 1)"), MetadataValue.unknown());
        assertRejected("generated column", () -> HiveAddColumn.sql(database, "public", "sample", generated));
        var identity = column("extra", Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0);
        evidence(identity, MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.value("GENERATED ALWAYS AS IDENTITY"), MetadataValue.unknown(), MetadataValue.unknown());
        assertRejected("generated column", () -> HiveAddColumn.sql(database, "public", "sample", identity));
        var sequence = column("extra", Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0);
        evidence(sequence, MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.unsupported());
        assertRejected("generated column", () -> HiveAddColumn.sql(database, "public", "sample", sequence));

        var badDecimal = column("amount", Types.DECIMAL, ColumnMetaData.TYPE_NUMBER, 39, 0);
        assertThrows(UnsupportedOperationException.class, () -> database.getAddColumnSql("public", "sample", badDecimal));
    }

    @Test
    void namesOutsideTheOrdinaryQuotedSubsetFailBeforeSqlIsBuilt() {
        var column = column("bad-name", Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0);
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("public", "sample", column));
        assertThrows(IllegalArgumentException.class,
                () -> database.getAddColumnSql("`public`", "sample", column("extra", Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0)));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("public", "sample",
                column("c" + "a".repeat(63), Types.BOOLEAN, ColumnMetaData.TYPE_BOOLEAN, 0, 0)));
    }

    @Test
    void unknownProductCannotBorrowTheHiveAddColumnImplementation() {
        var other = mock(DatabaseHiveImpl.class, CALLS_REAL_METHODS);
        when(other.getDatabaseType()).thenReturn(ProductTypeEnum.UNKNOWN);
        var column = column("extra", Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0);
        column.setNullable(false);
        var failure = assertThrows(SQLFeatureNotSupportedException.class,
                () -> other.getAddColumnSql("public", "sample", column));
        assertEquals("0A000", failure.getSQLState());
    }

    private static HiveAddColumn.TableShape shape(boolean partitioned, boolean transactional, boolean view, String alteration) {
        return new HiveAddColumn.TableShape(HiveAddColumn.EXTERNAL_TABLE, HiveAddColumn.TEXT_INPUT,
                HiveAddColumn.LAZY_SERDE, partitioned, transactional, view, alteration);
    }

    private static void assertRejected(String reason, org.junit.jupiter.api.function.Executable call) {
        var failure = assertThrows(SQLFeatureNotSupportedException.class, call);
        assertEquals("0A000", failure.getSQLState());
        assertTrue(failure.getMessage().contains(reason), failure.getMessage());
    }

    private static String alter(String schema, String table, String column, String type) {
        return "ALTER TABLE " + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.HIVE, schema)
                + "." + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.HIVE, table)
                + " ADD COLUMNS (" + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.HIVE, column)
                + " " + type + ")";
    }

    private static ColumnDescription column(String name, int jdbcType, int valueType, int length, int precision) {
        var column = new ColumnDescription();
        column.setFieldName(name);
        column.setNullable(true);
        column.setFieldType(jdbcType);
        column.setValType(valueType);
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

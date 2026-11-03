package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDB2Impl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SQL shape only. No DB2 connection is opened. */
class Db2AddColumnTest {
    private final DatabaseDB2Impl database = new DatabaseDB2Impl();

    @Test
    void providerDeclaresQuotedNullableAddWithoutCertifyingAServer() {
        var db2 = DbSwitchProductRegistry.discover().products().stream()
                .filter(product -> product.product() == ProductTypeEnum.DB2).findFirst().orElseThrow();
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "DB2_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED"),
                db2.capabilities().get(DatabaseOperation.DDL_ALTER));
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "Note;" + Types.VARCHAR + ";" + ColumnMetaData.TYPE_STRING + ";20;0;VARCHAR(60)",
            "Amount;" + Types.DECIMAL + ";" + ColumnMetaData.TYPE_BIGNUMBER + ";10;2;DECIMAL(10, 2)",
            "Ratio;" + Types.NUMERIC + ";" + ColumnMetaData.TYPE_NUMBER + ";8;0;DECIMAL(8)",
            "Seen;" + Types.TIMESTAMP + ";" + ColumnMetaData.TYPE_TIMESTAMP + ";3;0;TIMESTAMP(3)",
            "At;" + Types.TIME + ";" + ColumnMetaData.TYPE_TIME + ";6;0;TIME",
            "Day;" + Types.DATE + ";" + ColumnMetaData.TYPE_DATE + ";0;0;DATE",
            "Flag;" + Types.BIT + ";" + ColumnMetaData.TYPE_BOOLEAN + ";0;0;BOOLEAN",
            "Id;" + Types.BIGINT + ";" + ColumnMetaData.TYPE_INTEGER + ";15;0;BIGINT",
            "Payload;" + Types.BLOB + ";" + ColumnMetaData.TYPE_BINARY + ";0;0;BLOB",
            "Bits;" + Types.BINARY + ";" + ColumnMetaData.TYPE_BINARY + ";4;0;CHAR(4) FOR BIT DATA",
            "Wide;" + Types.VARCHAR + ";" + ColumnMetaData.TYPE_STRING + ";20000;0;CLOB",
            "Loose;" + Types.FLOAT + ";" + ColumnMetaData.TYPE_NUMBER + ";0;0;FLOAT"
    })
    void nullableAddReusesFieldDefinitionAndQuotesGivenSpelling(String column, int sqlType, int valueType,
            int length, int precision, String type) {
        var description = column(column, sqlType, valueType, length, precision);
        String sql = database.getAddColumnSql("App", "Items", description);
        String rendered = database.getFieldDefinition(description.getMetaData(), null, false, false, false);
        String prefix = " \"" + column + "\"   ";
        assertTrue(rendered.startsWith(prefix), rendered);
        assertEquals(type, rendered.substring(prefix.length()));
        assertEquals("ALTER TABLE "
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.DB2, "App") + "."
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.DB2, "Items") + " ADD "
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.DB2, column) + " " + type, sql);
        assertEquals("ALTER TABLE \"App\".\"Items\" ADD \"" + column + "\" " + type, sql);
        assertFalse(sql.contains("ADD COLUMN"));
        assertFalse(sql.contains("`"));
        assertFalse(sql.contains("NOT NULL"));
        assertFalse(sql.toUpperCase(java.util.Locale.ROOT).contains(" DEFAULT"));
    }

    @Test
    void embeddedQuoteIsDoubledInEachPhysicalName() {
        var description = column("a\"b", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        String sql = database.getAddColumnSql("s\"1", "t\"2", description);
        assertEquals("ALTER TABLE \"s\"\"1\".\"t\"\"2\" ADD \"a\"\"b\" VARCHAR(60)", sql);
    }

    @Test
    void notNullPrimaryKeyDefaultIdentityAndGeneratedAreRefused() {
        var required = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        required.setNullable(false);
        assertRefused(required, "NOT NULL");

        var key = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        key.setPk(true);
        assertRefused(key, "primary keys");

        var fallback = column("day", Types.DATE, ColumnMetaData.TYPE_DATE, 0, 0);
        fallback.setDefaultValue("CURRENT DATE");
        assertRefused(fallback, "defaults");

        var wordNull = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        wordNull.setDefaultValue("null");
        assertEquals(database.getAddColumnSql("app", "items", column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0)),
                database.getAddColumnSql("app", "items", wordNull));

        var wordNullUpper = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        wordNullUpper.setDefaultValue("NULL");
        assertEquals(database.getAddColumnSql("app", "items", column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0)),
                database.getAddColumnSql("app", "items", wordNullUpper));

        var declaredDefault = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        evidence(declaredDefault, MetadataValue.unknown(), MetadataValue.absent(), MetadataValue.value("'x'"), MetadataValue.value(true));
        assertRefused(declaredDefault, "defaults");

        var unsupportedDefault = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        evidence(unsupportedDefault, MetadataValue.unknown(), MetadataValue.absent(), MetadataValue.unsupported(), MetadataValue.value(true));
        assertRefused(unsupportedDefault, "defaults");

        var increment = column("id", Types.BIGINT, ColumnMetaData.TYPE_INTEGER, 15, 0);
        increment.setAutoIncrement(true);
        assertRefused(increment, "identity");

        var identity = column("id", Types.BIGINT, ColumnMetaData.TYPE_INTEGER, 15, 0);
        evidence(identity, MetadataValue.value("GENERATED ALWAYS AS IDENTITY"), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.value(true));
        assertRefused(identity, "identity");

        var generated = column("v", Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0);
        evidence(generated, MetadataValue.absent(), MetadataValue.value("GENERATED ALWAYS AS (ID+1)"), MetadataValue.absent(), MetadataValue.value(true));
        assertRefused(generated, "generated");
    }

    @Test
    void evidenceThatTheColumnIsNotNullIsRefusedEvenWhenTheJdbcFlagIsNullable() {
        var column = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        evidence(column, MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.value(false));
        assertRefused(column, "NOT NULL");
    }

    @Test
    void physicalIdentifierRulesRejectBlankNulAndOverlongNamesBeforeSqlIsEmitted() {
        var column = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql(null, "ITEMS", column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("APP", "", column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("APP", "ITEMS", column("bad\0name", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0)));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("APP", "I".repeat(129), column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("APP", "ITEMS", null));
    }

    private void assertRefused(ColumnDescription column, String reason) {
        var failure = assertThrows(SQLFeatureNotSupportedException.class, () -> database.getAddColumnSql("app", "items", column));
        assertTrue(failure.getMessage().contains(reason), failure.getMessage());
    }

    private static ColumnDescription column(String name, int sqlType, int valueType, int length, int precision) {
        var description = new ColumnDescription();
        description.setFieldName(name);
        description.setDbType(ProductTypeEnum.DB2);
        description.setFieldType(sqlType);
        description.setValType(valueType);
        description.setLength(length);
        description.setPrecision(precision);
        description.setNullable(true);
        return description;
    }

    private static void evidence(ColumnDescription column, MetadataValue<String> identity, MetadataValue<String> generated,
            MetadataValue<String> defaultExpression, MetadataValue<Boolean> nullable) {
        column.setMetadataEvidence(new ColumnMetadataEvidence(MetadataProvenance.unknown(), MetadataValue.unknown(),
                defaultExpression, nullable, MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(),
                identity, generated, MetadataValue.unknown(), MetadataValue.unknown()));
    }
}

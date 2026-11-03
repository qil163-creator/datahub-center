package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqlserver2000Impl;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqlserverImpl;
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
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SQL text only. No SQL Server connection, so this does not certify that a server accepts the statement. */
class SqlServerAddColumnTest {
    private final DatabaseSqlserverImpl database = new DatabaseSqlserverImpl();

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "Note;" + ColumnMetaData.TYPE_STRING + ";20;0;NVARCHAR(20)",
            "Body;" + ColumnMetaData.TYPE_STRING + ";4001;0;NVARCHAR(MAX)",
            "Short;" + ColumnMetaData.TYPE_STRING + ";0;0;NVARCHAR(100)",
            "Qty;" + ColumnMetaData.TYPE_INTEGER + ";9;0;INT",
            "Big;" + ColumnMetaData.TYPE_INTEGER + ";15;0;BIGINT",
            "Wide;" + ColumnMetaData.TYPE_BIGNUMBER + ";20;0;DECIMAL(20,0)",
            "Amt;" + ColumnMetaData.TYPE_NUMBER + ";10;2;DECIMAL(10,2)",
            "Ratio;" + ColumnMetaData.TYPE_NUMBER + ";-1;-1;FLOAT(53)",
            "Ts;" + ColumnMetaData.TYPE_TIMESTAMP + ";0;0;DATETIME2(7)",
            "At;" + ColumnMetaData.TYPE_TIME + ";0;0;TIME",
            "Day;" + ColumnMetaData.TYPE_DATE + ";0;0;DATE",
            "Flag;" + ColumnMetaData.TYPE_BOOLEAN + ";0;0;BIT",
            "Bin;" + ColumnMetaData.TYPE_BINARY + ";0;0;VARBINARY(MAX)"
    })
    void nullableAddReusesFieldDefinitionAndBrackets(String name, int valueType, int length, int precision, String type) {
        ColumnDescription column = column(name, valueType, length, precision);
        String sql = database.getAddColumnSql("Hr", "Emp", column);
        String definition = database.getFieldDefinition(column.getMetaData(), null, false, false, true);
        String quoted = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.SQLSERVER, name);
        assertTrue(definition.startsWith(" " + quoted + "  "), definition);
        assertEquals(type, definition.substring((" " + quoted + "  ").length()).strip());
        assertEquals("ALTER TABLE [Hr].[Emp] ADD " + quoted + " " + type, sql);
        assertFalse(sql.toUpperCase(Locale.ROOT).contains("ADD COLUMN"));
        assertFalse(sql.contains("`"));
        assertFalse(sql.contains("\""));
        assertFalse(sql.toUpperCase(Locale.ROOT).contains("NOT NULL"));
        assertFalse(sql.toUpperCase(Locale.ROOT).contains(" DEFAULT"));
    }

    @Test
    void unknownValueTypeDoesNotSilentlyAddATextColumn() {
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> database.getAddColumnSql("Hr", "Emp", column("Misc", ColumnMetaData.TYPE_NONE, 0, 0)));
    }

    @Test
    void bracketsPreserveSpellingAndDoubleClosingBrackets() {
        ColumnDescription column = column("e]f", ColumnMetaData.TYPE_STRING, 20, 0);
        assertEquals("ALTER TABLE [a]]b].[c]]d] ADD [e]]f] NVARCHAR(20)",
                database.getAddColumnSql("a]b", "c]d", column));
        assertEquals("ALTER TABLE [my schema].[my table] ADD [my col] NVARCHAR(20)",
                database.getAddColumnSql("my schema", "my table", column("my col", ColumnMetaData.TYPE_STRING, 20, 0)));
        String longest = "N".repeat(128);
        assertEquals("ALTER TABLE [s].[t] ADD [" + longest + "] INT",
                database.getAddColumnSql("s", "t", column(longest, ColumnMetaData.TYPE_INTEGER, 9, 0)));
    }

    @Test
    void notNullPrimaryKeyDefaultIdentityAndGeneratedAreRefused() {
        ColumnDescription required = column("Note", ColumnMetaData.TYPE_STRING, 20, 0);
        required.setNullable(false);
        assertRefused("NOT NULL", required);

        ColumnDescription evidenceNotNull = column("Note", ColumnMetaData.TYPE_STRING, 20, 0);
        evidence(evidenceNotNull, MetadataValue.value(false), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.unknown());
        assertRefused("NOT NULL", evidenceNotNull);

        ColumnDescription key = column("Note", ColumnMetaData.TYPE_STRING, 20, 0);
        key.setPk(true);
        assertRefused("primary key", key);

        ColumnDescription literalDefault = column("Note", ColumnMetaData.TYPE_STRING, 20, 0);
        literalDefault.setDefaultValue("null");
        assertRefused("default", literalDefault);

        ColumnDescription evidenceDefault = column("Note", ColumnMetaData.TYPE_STRING, 20, 0);
        evidence(evidenceDefault, MetadataValue.unknown(), MetadataValue.value("0"),
                MetadataValue.unknown(), MetadataValue.unknown());
        assertRefused("default", evidenceDefault);

        ColumnDescription identity = column("Note", ColumnMetaData.TYPE_INTEGER, 9, 0);
        identity.setAutoIncrement(true);
        assertRefused("identity", identity);

        ColumnDescription evidenceIdentity = column("Note", ColumnMetaData.TYPE_INTEGER, 9, 0);
        evidence(evidenceIdentity, MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.value("IDENTITY(1,1)"), MetadataValue.unknown());
        assertRefused("identity", evidenceIdentity);

        ColumnDescription generated = column("Note", ColumnMetaData.TYPE_INTEGER, 9, 0);
        evidence(generated, MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.value("AS (id+1)"));
        assertRefused("generated", generated);
    }

    @Test
    void absentNullableEvidenceStillAddsWhenTheJdbcFlagIsNullable() {
        ColumnDescription column = column("Note", ColumnMetaData.TYPE_STRING, 20, 0);
        evidence(column, MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent());
        assertEquals("ALTER TABLE [Hr].[Emp] ADD [Note] NVARCHAR(20)", database.getAddColumnSql("Hr", "Emp", column));
    }

    @Test
    void invalidPhysicalNamesAreNotFoldedIntoSql() {
        ColumnDescription column = column("Note", ColumnMetaData.TYPE_STRING, 20, 0);
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql(null, "Emp", column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql(" ", "Emp", column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("H\u0000r", "Emp", column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("N".repeat(129), "Emp", column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("Hr", "Emp", column(" ", ColumnMetaData.TYPE_STRING, 20, 0)));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("Hr", "Emp", column(null, ColumnMetaData.TYPE_STRING, 20, 0)));
    }

    @Test
    void sqlServer2000DoesNotSpliceUnquotedNames() {
        DatabaseSqlserver2000Impl legacy = new DatabaseSqlserver2000Impl();
        ColumnDescription column = column("Note", ColumnMetaData.TYPE_STRING, 20, 0);
        assertEquals("ALTER TABLE [Hr].[Emp] ADD [Note] NVARCHAR(20) NULL",
                legacy.getAddColumnSql("Hr", "Emp", column));
        column.setNullable(false);
        assertThrows(SQLFeatureNotSupportedException.class, () -> legacy.getAddColumnSql("Hr", "Emp", column));
        var sqlserver2000 = DbSwitchProductRegistry.discover().products().stream()
                .filter(product -> product.product() == ProductTypeEnum.SQLSERVER2000)
                .findFirst().orElseThrow();
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        cn.iocoder.yudao.framework.dbswitch.product.sqlserver2000.Sqlserver2000AddColumn.REASON),
                sqlserver2000.capabilities().get(DatabaseOperation.DDL_ALTER));
    }

    @Test
    void providerDeclaresQuotedNullableAddWithoutCertifyingADatabase() {
        var sqlServer = DbSwitchProductRegistry.discover().products().stream()
                .filter(product -> product.product() == ProductTypeEnum.SQLSERVER)
                .findFirst().orElseThrow();
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "SQLSERVER_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED"),
                sqlServer.capabilities().get(DatabaseOperation.DDL_ALTER));
    }

    private void assertRefused(String fragment, ColumnDescription column) {
        SQLFeatureNotSupportedException failure = assertThrows(SQLFeatureNotSupportedException.class,
                () -> database.getAddColumnSql("Hr", "Emp", column));
        assertEquals("0A000", failure.getSQLState());
        assertTrue(failure.getMessage().contains(fragment), failure.getMessage());
    }

    private static ColumnDescription column(String name, int valueType, int length, int precision) {
        ColumnDescription description = new ColumnDescription();
        description.setFieldName(name);
        description.setDbType(ProductTypeEnum.SQLSERVER);
        description.setValType(valueType);
        description.setLength(length);
        description.setPrecision(precision);
        description.setNullable(true);
        return description;
    }

    private static void evidence(ColumnDescription column, MetadataValue<Boolean> nullable,
            MetadataValue<String> defaultExpression, MetadataValue<String> identity, MetadataValue<String> generated) {
        column.setMetadataEvidence(new ColumnMetadataEvidence(MetadataProvenance.unknown(), MetadataValue.unknown(),
                defaultExpression, nullable, MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(),
                identity, generated, MetadataValue.unknown(), MetadataValue.unknown()));
    }
}

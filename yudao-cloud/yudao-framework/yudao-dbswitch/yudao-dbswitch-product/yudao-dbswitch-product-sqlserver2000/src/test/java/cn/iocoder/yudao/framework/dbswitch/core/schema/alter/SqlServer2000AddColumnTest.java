package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseSqlserver2000Impl;
import cn.iocoder.yudao.framework.dbswitch.core.model.*;
import cn.iocoder.yudao.framework.dbswitch.product.sqlserver2000.Sqlserver2000AddColumn;
import cn.iocoder.yudao.framework.dbswitch.spi.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.SQLFeatureNotSupportedException;

import static org.junit.jupiter.api.Assertions.*;

class SqlServer2000AddColumnTest {
    private final DatabaseSqlserver2000Impl database = new DatabaseSqlserver2000Impl();

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "" + ColumnMetaData.TYPE_INTEGER + ";9;0;INT",
            "" + ColumnMetaData.TYPE_NUMBER + ";10;2;DECIMAL(10,2)",
            "" + ColumnMetaData.TYPE_BIGNUMBER + ";38;0;DECIMAL(38,0)",
            "" + ColumnMetaData.TYPE_NUMBER + ";38;38;DECIMAL(38,38)",
            "" + ColumnMetaData.TYPE_STRING + ";1;0;NVARCHAR(1)",
            "" + ColumnMetaData.TYPE_STRING + ";4000;0;NVARCHAR(4000)"
    })
    void publicProductEmitsOnlyFiniteTypesAndExplicitNull(int type, int length, int scale, String nativeType) {
        var metadata = DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.SQLSERVER2000);
        assertEquals("ALTER TABLE [Owner].[Rows] ADD [Added] " + nativeType + " NULL",
                metadata.getAddColumnSql("Owner", "Rows", column("Added", type, length, scale)));
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "" + ColumnMetaData.TYPE_STRING + ";4001;0",
            "" + ColumnMetaData.TYPE_INTEGER + ";15;0",
            "" + ColumnMetaData.TYPE_NUMBER + ";39;2",
            "" + ColumnMetaData.TYPE_NUMBER + ";10;11",
            "" + ColumnMetaData.TYPE_NUMBER + ";-1;-1",
            "" + ColumnMetaData.TYPE_DATE + ";0;0",
            "" + ColumnMetaData.TYPE_TIME + ";0;0",
            "" + ColumnMetaData.TYPE_TIMESTAMP + ";0;0",
            "" + ColumnMetaData.TYPE_BINARY + ";0;0",
            "" + ColumnMetaData.TYPE_BOOLEAN + ";0;0",
            "" + ColumnMetaData.TYPE_NONE + ";0;0"
    })
    void unprovedModernAndLegacyTypesAreRejected(int type, int length, int scale) {
        refused(() -> database.getAddColumnSql("dbo", "rows", column("added", type, length, scale)));
    }

    @Test void existingQuotingEscapesNamesAndEnforcesTheBudget() {
        assertEquals("ALTER TABLE [a]]b].[c]]d] ADD [e]]f] INT NULL",
                database.getAddColumnSql("a]b", "c]d", column("e]f", ColumnMetaData.TYPE_INTEGER, 9, 0)));
        assertEquals("ALTER TABLE [my owner].[my table] ADD [my col] INT NULL",
                database.getAddColumnSql("my owner", "my table", column("my col", ColumnMetaData.TYPE_INTEGER, 9, 0)));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("n".repeat(129), "rows", ordinary()));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("dbo", "rows", column("x\0", ColumnMetaData.TYPE_INTEGER, 9, 0)));
    }

    @Test void constraintsAndEvidenceAreRejectedBeforeTheFieldDefinitionIsRendered() {
        var required = ordinary(); required.setNullable(false); refused(required);
        var key = ordinary(); key.setPk(true); refused(key);
        var defaulted = ordinary(); defaulted.setDefaultValue("0"); refused(defaulted);
        var identity = ordinary(); identity.setAutoIncrement(true); refused(identity);
        var generated = ordinary();
        generated.setMetadataEvidence(new ColumnMetadataEvidence(MetadataProvenance.unknown(), MetadataValue.unknown(),
                MetadataValue.absent(), MetadataValue.value(true), MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.absent(), MetadataValue.value("id+1"),
                MetadataValue.unknown(), MetadataValue.unknown()));
        refused(generated);
    }

    @Test void capabilityIsAnExplicitFiniteDeclaration() {
        var descriptor = DbSwitchProductRegistry.discover().products().stream()
                .filter(product -> product.product() == ProductTypeEnum.SQLSERVER2000).findFirst().orElseThrow();
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED, Sqlserver2000AddColumn.REASON),
                descriptor.capabilities().get(DatabaseOperation.DDL_ALTER));
    }

    private void refused(ColumnDescription column) {
        refused(() -> Sqlserver2000AddColumn.sql("dbo", "rows", column, meta -> {
            fail("Unsupported semantics must be refused before rendering");
            return "";
        }));
    }

    private static void refused(org.junit.jupiter.api.function.Executable call) {
        assertEquals("0A000", assertThrows(SQLFeatureNotSupportedException.class, call).getSQLState());
    }

    private static ColumnDescription ordinary() { return column("added", ColumnMetaData.TYPE_INTEGER, 9, 0); }

    private static ColumnDescription column(String name, int type, int length, int scale) {
        var column = new ColumnDescription();
        column.setDbType(ProductTypeEnum.SQLSERVER2000); column.setFieldName(name);
        column.setValType(type); column.setLength(length); column.setPrecision(scale); column.setNullable(true);
        return column;
    }
}

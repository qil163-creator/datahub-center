package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDmImpl;
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

import static org.junit.jupiter.api.Assertions.*;

/** SQL shape only. No DM connection is opened. */
class DmAddColumnTest {
    private final DatabaseDmImpl database = new DatabaseDmImpl();

    @Test void providerDeclaresQuotedNullableAddWithoutCertifyingAServer() {
        var dm = DbSwitchProductRegistry.discover().products().stream()
                .filter(product -> product.product() == ProductTypeEnum.DM).findFirst().orElseThrow();
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED, "DM_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED"),
                dm.capabilities().get(DatabaseOperation.DDL_ALTER));
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "note;" + Types.VARCHAR + ";" + ColumnMetaData.TYPE_STRING + ";20;0;VARCHAR(20)",
            "amount;" + Types.DECIMAL + ";" + ColumnMetaData.TYPE_BIGNUMBER + ";10;2;DECIMAL(10, 2)",
            "ratio;" + Types.NUMERIC + ";" + ColumnMetaData.TYPE_BIGNUMBER + ";8;0;NUMERIC(8, 0)",
            "seen;" + Types.TIMESTAMP + ";" + ColumnMetaData.TYPE_TIMESTAMP + ";3;0;TIMESTAMP(3)",
            "at;" + Types.TIME + ";" + ColumnMetaData.TYPE_TIME + ";6;0;TIME(6)",
            "day;" + Types.DATE + ";" + ColumnMetaData.TYPE_DATE + ";0;0;DATE",
            "flag;" + Types.BIT + ";" + ColumnMetaData.TYPE_BOOLEAN + ";0;0;BIT",
            "id;" + Types.BIGINT + ";" + ColumnMetaData.TYPE_INTEGER + ";15;0;BIGINT",
            "payload;" + Types.BLOB + ";" + ColumnMetaData.TYPE_BINARY + ";0;0;BLOB",
            "body;" + Types.VARCHAR + ";" + ColumnMetaData.TYPE_STRING + ";2048;0;TEXT"
    })
    void nullableAddReusesFieldDefinitionAndQuotesUppercase(String column, int sqlType, int valueType,
            int length, int precision, String type) {
        var description = column(column, sqlType, valueType, length, precision);
        String sql = database.getAddColumnSql("app", "items", description);
        String rendered = database.getFieldDefinition(description.getMetaData(), null, false, false, false);
        assertTrue(rendered.endsWith(type), rendered);
        assertEquals("ALTER TABLE "
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.DM, "app") + "."
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.DM, "items") + " ADD "
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.DM, column) + " " + type, sql);
        assertEquals("ALTER TABLE \"APP\".\"ITEMS\" ADD \"" + column.toUpperCase(java.util.Locale.ROOT) + "\" " + type, sql);
        assertFalse(sql.contains("ADD COLUMN"));
        assertFalse(sql.contains("`"));
        assertFalse(sql.contains("NOT NULL"));
        assertFalse(sql.toUpperCase(java.util.Locale.ROOT).contains(" DEFAULT"));
    }

    @Test void notNullPrimaryKeyDefaultIdentityAndGeneratedAreRefused() {
        var required = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        required.setNullable(false);
        assertRefused(required, "NOT NULL");

        var key = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        key.setPk(true);
        assertRefused(key, "primary keys");

        var fallback = column("day", Types.DATE, ColumnMetaData.TYPE_DATE, 0, 0);
        fallback.setDefaultValue("SYSDATE");
        assertRefused(fallback, "defaults");

        var wordNull = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        wordNull.setDefaultValue("null");
        assertEquals(database.getAddColumnSql("app", "items", column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0)),
                database.getAddColumnSql("app", "items", wordNull));

        var declaredDefault = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        evidence(declaredDefault, MetadataValue.unknown(), MetadataValue.absent(), MetadataValue.value("'x'"), MetadataValue.value(true));
        assertRefused(declaredDefault, "defaults");

        var increment = column("id", Types.BIGINT, ColumnMetaData.TYPE_INTEGER, 15, 0);
        increment.setAutoIncrement(true);
        assertRefused(increment, "identity");

        var identity = column("id", Types.BIGINT, ColumnMetaData.TYPE_INTEGER, 15, 0);
        evidence(identity, MetadataValue.value("IDENTITY(1,1)"), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.value(true));
        assertRefused(identity, "identity");

        var generated = column("v", Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0);
        evidence(generated, MetadataValue.absent(), MetadataValue.value("AS(ID+1)"), MetadataValue.absent(), MetadataValue.value(true));
        assertRefused(generated, "generated");
    }

    @Test void evidenceThatTheColumnIsNotNullIsRefusedEvenWhenTheJdbcFlagIsNullable() {
        var column = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        evidence(column, MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.value(false));
        assertRefused(column, "NOT NULL");
    }

    @Test void ordinaryIdentifierRulesRejectQuotedOrEmptyNamesBeforeSqlIsEmitted() {
        var column = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("\"APP\"", "ITEMS", column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("APP", "", column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("APP", "ITEMS", column("bad-name", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0)));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql(null, "ITEMS", column));
    }

    private void assertRefused(ColumnDescription column, String reason) {
        var failure = assertThrows(SQLFeatureNotSupportedException.class, () -> database.getAddColumnSql("app", "items", column));
        assertTrue(failure.getMessage().contains(reason), failure.getMessage());
    }

    private static ColumnDescription column(String name, int sqlType, int valueType, int length, int precision) {
        var description = new ColumnDescription();
        description.setFieldName(name);
        description.setDbType(ProductTypeEnum.DM);
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

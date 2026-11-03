package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMysqlImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnSequenceMetadata;
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

/** Generated SQL and refusal only. No MySQL connection is opened. */
class MysqlAddColumnTest {
    private final DatabaseMysqlImpl database = new DatabaseMysqlImpl();

    @Test
    void providerDeclaresQuotedNullableAddWithoutCertifyingAServer() {
        var mysql = DbSwitchProductRegistry.discover().products().stream()
                .filter(product -> product.product() == ProductTypeEnum.MYSQL).findFirst().orElseThrow();
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "MYSQL_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED"),
                mysql.capabilities().get(DatabaseOperation.DDL_ALTER));
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "note;" + Types.VARCHAR + ";" + ColumnMetaData.TYPE_STRING + ";20;0;VARCHAR(20)",
            "amount;" + Types.DECIMAL + ";" + ColumnMetaData.TYPE_BIGNUMBER + ";10;2;DECIMAL(10, 2)",
            "seen;" + Types.TIMESTAMP + ";" + ColumnMetaData.TYPE_TIMESTAMP + ";0;0;DATETIME",
            "at;" + Types.TIME + ";" + ColumnMetaData.TYPE_TIME + ";0;0;TIME",
            "day;" + Types.DATE + ";" + ColumnMetaData.TYPE_DATE + ";0;0;DATE",
            "flag;" + Types.BIT + ";" + ColumnMetaData.TYPE_BOOLEAN + ";0;0;TINYINT",
            "qty;" + Types.INTEGER + ";" + ColumnMetaData.TYPE_INTEGER + ";9;0;INT",
            "id;" + Types.BIGINT + ";" + ColumnMetaData.TYPE_INTEGER + ";15;0;BIGINT",
            "payload;" + Types.BLOB + ";" + ColumnMetaData.TYPE_BINARY + ";0;0;LONGBLOB",
            "body;" + Types.VARCHAR + ";" + ColumnMetaData.TYPE_STRING + ";2048;0;TEXT",
            "code;" + Types.CHAR + ";" + ColumnMetaData.TYPE_STRING + ";1;0;CHAR(1)"
    })
    void nullableAddReusesFieldDefinitionAndQuotesEachIdentifier(String column, int sqlType, int valueType,
            int length, int precision, String type) {
        var description = column(column, sqlType, valueType, length, precision);
        description.setRemarks("not emitted");
        String sql = database.getAddColumnSql("app", "items", description);
        String rendered = database.getFieldDefinition(description.getMetaData(), null, false, false, false);
        String quotedColumn = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.MYSQL, column);
        assertEquals(" " + quotedColumn + "  " + type, rendered);
        assertEquals("ALTER TABLE "
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.MYSQL, "app") + "."
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.MYSQL, "items")
                + " ADD COLUMN " + quotedColumn + " " + type, sql);
        assertFalse(sql.contains("app.items"));
        assertFalse(sql.contains("NOT NULL"));
        assertFalse(sql.toUpperCase(java.util.Locale.ROOT).contains("DEFAULT"));
        assertFalse(sql.toUpperCase(java.util.Locale.ROOT).contains("AUTO_INCREMENT"));
        assertFalse(sql.toUpperCase(java.util.Locale.ROOT).contains("GENERATED"));
        assertFalse(sql.contains("COMMENT"));
        assertFalse(sql.endsWith(";"));
    }

    @Test
    void quotesSchemaTableAndColumnSeparatelyIncludingDotAndBacktick() {
        var description = column("MiX", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        String sql = database.getAddColumnSql("Gov.Db", "A`B", description);
        assertEquals("ALTER TABLE `Gov.Db`.`A``B` ADD COLUMN `MiX` VARCHAR(20)", sql);
        assertEquals("ALTER TABLE `app`.`items` ADD COLUMN `bad-name` VARCHAR(20)",
                database.getAddColumnSql("app", "items", column("bad-name", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0)));
    }

    @Test
    void notNullPrimaryKeyDefaultIdentityAndGeneratedAreRefused() {
        var required = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        required.setNullable(false);
        assertRefused(required, "NOT NULL");

        var temporal = column("day", Types.DATE, ColumnMetaData.TYPE_DATE, 0, 0);
        temporal.setNullable(false);
        assertRefused(temporal, "NOT NULL");

        var key = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        key.setPk(true);
        assertRefused(key, "primary key");

        var fallback = column("day", Types.DATE, ColumnMetaData.TYPE_DATE, 0, 0);
        fallback.setDefaultValue("CURRENT_TIMESTAMP");
        assertRefused(fallback, "default");

        var plain = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        var wordNull = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        wordNull.setDefaultValue("null");
        assertEquals(database.getAddColumnSql("app", "items", plain), database.getAddColumnSql("app", "items", wordNull));
        var wordNullUpper = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        wordNullUpper.setDefaultValue("NULL");
        assertEquals(database.getAddColumnSql("app", "items", plain), database.getAddColumnSql("app", "items", wordNullUpper));

        var emptyDefault = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        emptyDefault.setDefaultValue("");
        assertRefused(emptyDefault, "default");

        var declaredDefault = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        evidence(declaredDefault, MetadataValue.absent(), MetadataValue.absent(), MetadataValue.value("'x'"), MetadataValue.value(true));
        assertRefused(declaredDefault, "default");

        var increment = column("id", Types.BIGINT, ColumnMetaData.TYPE_INTEGER, 15, 0);
        increment.setAutoIncrement(true);
        assertRefused(increment, "identity");

        var identity = column("id", Types.BIGINT, ColumnMetaData.TYPE_INTEGER, 15, 0);
        evidence(identity, MetadataValue.value("AUTO_INCREMENT"), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.value(true));
        assertRefused(identity, "identity");

        var sequence = column("id", Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0);
        sequence.setMetadataEvidence(new ColumnMetadataEvidence(MetadataProvenance.unknown(), MetadataValue.unknown(),
                MetadataValue.absent(), MetadataValue.value(true), MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.value(new ColumnSequenceMetadata(
                        ColumnSequenceMetadata.Kind.IDENTITY_BY_DEFAULT, "app", "seq_id", "integer",
                        1, 1, 1, Integer.MAX_VALUE, 1, false, MetadataValue.absent()))));
        assertRefused(sequence, "identity");

        var generated = column("v", Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0);
        evidence(generated, MetadataValue.absent(), MetadataValue.value("(`a` + 1)"), MetadataValue.absent(), MetadataValue.value(true));
        assertRefused(generated, "generated");

        var namedGenerated = column("v", Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0);
        namedGenerated.setFieldTypeName("int generated always");
        assertRefused(namedGenerated, "generated");

        assertThrows(SQLFeatureNotSupportedException.class, () -> database.getAddColumnSql("app", "items", null));
    }

    @Test
    void evidenceThatTheColumnIsNotNullIsRefusedEvenWhenTheJdbcFlagIsNullable() {
        var column = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        evidence(column, MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.value(false));
        assertRefused(column, "NOT NULL");
    }

    @Test
    void physicalIdentifierRulesRejectEmptyOrOversizedNamesBeforeSqlIsEmitted() {
        var column = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql(null, "items", column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("app", "", column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("app", "items",
                column("n".repeat(65), Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0)));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("a\0b", "items", column));
    }

    private void assertRefused(ColumnDescription column, String reason) {
        var failure = assertThrows(SQLFeatureNotSupportedException.class,
                () -> database.getAddColumnSql("app", "items", column));
        assertTrue(failure.getMessage().contains(reason), failure.getMessage());
        assertFalse(failure.getMessage().contains("ALTER TABLE"), failure.getMessage());
        assertEquals("0A000", failure.getSQLState());
    }

    private static ColumnDescription column(String name, int sqlType, int valueType, int length, int precision) {
        var description = new ColumnDescription();
        description.setFieldName(name);
        description.setDbType(ProductTypeEnum.MYSQL);
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

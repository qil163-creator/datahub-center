package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOracleImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import org.junit.jupiter.api.Test;

import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SQL text only. No Oracle connection, so this does not certify that a server accepts the statement. */
class OracleAddColumnTest {
    private final DatabaseOracleImpl database = new DatabaseOracleImpl();

    @Test
    void nullableAddQuotesPhysicalNamesAndDoesNotUseAddColumn() {
        String sql = database.getAddColumnSql("Hr", "Emp", column("Note", Types.VARCHAR, 20, 0, 0));
        assertEquals("ALTER TABLE \"Hr\".\"Emp\" ADD (\"Note\" VARCHAR2(20))", sql);
        assertFalse(sql.toUpperCase(Locale.ROOT).contains("ADD COLUMN"));
        assertFalse(sql.contains("\"HR\""));
        assertFalse(sql.contains("\"EMP\""));
        assertFalse(sql.contains("\"NOTE\""));
        assertFalse(sql.toUpperCase(Locale.ROOT).contains("NOT NULL"));
        assertFalse(sql.toUpperCase(Locale.ROOT).contains(" DEFAULT"));
    }

    @Test
    void typesFollowExistingFieldDefinition() {
        assertEquals("ALTER TABLE \"Hr\".\"Emp\" ADD (\"Qty\" INTEGER)",
                database.getAddColumnSql("Hr", "Emp", column("Qty", Types.INTEGER, 0, 0, 0)));
        assertEquals("ALTER TABLE \"Hr\".\"Emp\" ADD (\"Amt\" NUMBER(10, 2))",
                database.getAddColumnSql("Hr", "Emp", column("Amt", Types.NUMERIC, 0, 10, 2)));
        assertEquals("ALTER TABLE \"Hr\".\"Emp\" ADD (\"Day\" DATE)",
                database.getAddColumnSql("Hr", "Emp", column("Day", Types.DATE, 0, 0, 0)));
        assertEquals("ALTER TABLE \"Hr\".\"Emp\" ADD (\"Ts\" TIMESTAMP(9))",
                database.getAddColumnSql("Hr", "Emp", column("Ts", Types.TIMESTAMP, 0, 0, 0)));
        assertEquals("ALTER TABLE \"Hr\".\"Emp\" ADD (\"Flag\" NUMBER(1))",
                database.getAddColumnSql("Hr", "Emp", column("Flag", Types.BOOLEAN, 0, 0, 0)));
        assertEquals("ALTER TABLE \"Hr\".\"Emp\" ADD (\"Bin\" BLOB)",
                database.getAddColumnSql("Hr", "Emp", column("Bin", Types.BLOB, 0, 0, 0)));
        ColumnDescription text = column("Note", Types.VARCHAR, 20, 0, 0);
        String quoted = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.ORACLE, "Note");
        String definition = database.getFieldDefinition(text.getMetaData(), null, false, false, true).trim();
        assertTrue(definition.startsWith(quoted));
        assertEquals("ALTER TABLE \"Hr\".\"Emp\" ADD (" + quoted + " " + definition.substring(quoted.length()).trim() + ")",
                database.getAddColumnSql("Hr", "Emp", text));
    }

    @Test
    void notNullPrimaryKeyDefaultIdentityAndGeneratedAreRefused() {
        ColumnDescription required = column("Note", Types.VARCHAR, 20, 0, 0);
        required.setNullable(false);
        assertRefused("NOT NULL", required);

        ColumnDescription evidenceNotNull = column("Note", Types.VARCHAR, 20, 0, 0);
        evidenceNotNull.setMetadataEvidence(evidence(MetadataValue.value(false), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.unknown()));
        assertRefused("NOT NULL", evidenceNotNull);

        ColumnDescription key = column("Note", Types.VARCHAR, 20, 0, 0);
        key.setPk(true);
        assertRefused("primary key", key);

        ColumnDescription literalDefault = column("Note", Types.VARCHAR, 20, 0, 0);
        literalDefault.setDefaultValue("null");
        assertRefused("default", literalDefault);

        ColumnDescription evidenceDefault = column("Note", Types.VARCHAR, 20, 0, 0);
        evidenceDefault.setMetadataEvidence(evidence(MetadataValue.unknown(), MetadataValue.value("0"),
                MetadataValue.unknown(), MetadataValue.unknown()));
        assertRefused("default", evidenceDefault);

        ColumnDescription identity = column("Note", Types.INTEGER, 0, 0, 0);
        identity.setAutoIncrement(true);
        assertRefused("identity", identity);

        ColumnDescription evidenceIdentity = column("Note", Types.INTEGER, 0, 0, 0);
        evidenceIdentity.setMetadataEvidence(evidence(MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.value("ALWAYS"), MetadataValue.unknown()));
        assertRefused("identity", evidenceIdentity);

        ColumnDescription generated = column("Note", Types.INTEGER, 0, 0, 0);
        generated.setMetadataEvidence(evidence(MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.value("1")));
        assertRefused("generated", generated);
    }

    @Test
    void invalidPhysicalNamesAreNotFoldedIntoSql() {
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("H\"r", "Emp", column("Note", Types.VARCHAR, 20, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("Hr", "Emp", column("No\"te", Types.VARCHAR, 20, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("Hr", "Emp", column(" ", Types.VARCHAR, 20, 0, 0)));
    }

    @Test
    void providerDeclaresQuotedNullableAddWithoutCertifyingADatabase() {
        var oracle = DbSwitchProductRegistry.discover().products().stream()
                .filter(product -> product.product() == ProductTypeEnum.ORACLE)
                .findFirst().orElseThrow();
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "ORACLE_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED"),
                oracle.capabilities().get(DatabaseOperation.DDL_ALTER));
    }

    private void assertRefused(String fragment, ColumnDescription column) {
        SQLFeatureNotSupportedException failure = assertThrows(SQLFeatureNotSupportedException.class,
                () -> database.getAddColumnSql("Hr", "Emp", column));
        assertEquals("0A000", failure.getSQLState());
        assertTrue(failure.getMessage().contains(fragment));
    }

    private static ColumnDescription column(String name, int jdbcType, int displaySize, int precision, int scale) {
        ColumnDescription column = new ColumnDescription();
        column.setFieldName(name);
        column.setFieldType(jdbcType);
        column.setDisplaySize(displaySize);
        column.setPrecisionSize(precision);
        column.setScaleSize(scale);
        column.setNullable(true);
        column.setDbType(ProductTypeEnum.ORACLE);
        return column;
    }

    private static ColumnMetadataEvidence evidence(MetadataValue<Boolean> nullable, MetadataValue<String> defaultExpression,
            MetadataValue<String> identity, MetadataValue<String> generated) {
        return new ColumnMetadataEvidence(MetadataProvenance.unknown(), MetadataValue.unknown(), defaultExpression, nullable,
                MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(), identity, generated,
                MetadataValue.unknown(), MetadataValue.unknown());
    }
}

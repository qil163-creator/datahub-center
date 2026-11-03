package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseGbase8aImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.product.gbase8a.Gbase8aProductProvider;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import org.junit.jupiter.api.Test;

import java.sql.SQLFeatureNotSupportedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SQL shape only. No GBase connection, so a passing test is not a server certificate. */
class Gbase8aAddColumnTest {
    private final DatabaseGbase8aImpl database = new DatabaseGbase8aImpl();

    @Test
    void nullableAddQuotesIdentifiersAndDropsMysqlClauses() {
        ColumnDescription column = column("note", ColumnMetaData.TYPE_STRING, 32, 0);
        column.setRemarks("keep me");
        String sql = database.getAddColumnSql("sales", "orders", column);
        assertEquals("ALTER TABLE " + quote("sales") + "." + quote("orders")
                + " ADD COLUMN " + quote("note") + "  VARCHAR(32)", sql);
        assertFalse(sql.toUpperCase().contains("COMMENT"));
        assertFalse(sql.contains("NOT NULL"));
        assertFalse(sql.toUpperCase().contains("DEFAULT"));
        assertFalse(sql.contains("AUTO_INCREMENT"));
        assertFalse(sql.toUpperCase().contains("BINARY"));
        assertFalse(sql.contains("ENGINE"));
        assertFalse(sql.toUpperCase().contains("CHARSET"));
        assertFalse(sql.toUpperCase().contains("COLLATE"));
        assertFalse(sql.toUpperCase().contains("ALGORITHM"));
        assertFalse(sql.toUpperCase().contains("AFTER"));
    }

    @Test
    void provedFieldDefinitionTypesStayInTheAddStatement() {
        assertTrue(sql(column("qty", ColumnMetaData.TYPE_INTEGER, 9, 0)).endsWith(" INT"));
        assertTrue(sql(column("qty", ColumnMetaData.TYPE_INTEGER, 15, 0)).endsWith(" BIGINT"));
        assertTrue(sql(column("amount", ColumnMetaData.TYPE_NUMBER, 10, 2)).endsWith(" DECIMAL(10, 2)"));
        assertTrue(sql(column("flag", ColumnMetaData.TYPE_BOOLEAN, 1, 0)).endsWith(" TINYINT"));
        assertTrue(sql(column("day", ColumnMetaData.TYPE_DATE, 0, 0)).endsWith(" DATE"));
        assertTrue(sql(column("ts", ColumnMetaData.TYPE_TIMESTAMP, 0, 0)).endsWith(" DATETIME"));
        assertTrue(sql(column("clock", ColumnMetaData.TYPE_TIME, 0, 0)).endsWith(" TIME"));
        assertTrue(sql(column("body", ColumnMetaData.TYPE_STRING, 4000, 0)).endsWith(" TEXT"));
        assertTrue(sql(column("short", ColumnMetaData.TYPE_STRING, 0, 0)).endsWith(" TINYTEXT"));
        ColumnDescription wordNull = column("note", ColumnMetaData.TYPE_STRING, 8, 0);
        wordNull.setDefaultValue("NULL");
        assertFalse(database.getAddColumnSql("sales", "orders", wordNull).contains("DEFAULT"));
    }

    @Test
    void backtickInTheColumnNameIsEscapedByTheGuard() {
        ColumnDescription column = column("a`b", ColumnMetaData.TYPE_STRING, 8, 0);
        assertEquals("ALTER TABLE " + quote("sales") + "." + quote("orders")
                + " ADD COLUMN " + quote("a`b") + "  VARCHAR(8)", database.getAddColumnSql("sales", "orders", column));
    }

    @Test
    void notNullPrimaryKeyDefaultIdentityAndGeneratedAreRejected() {
        ColumnDescription required = column("note", ColumnMetaData.TYPE_STRING, 8, 0);
        required.setNullable(false);
        assertRejected(required, "NOT NULL");
        ColumnDescription dated = column("day", ColumnMetaData.TYPE_DATE, 0, 0);
        dated.setNullable(false);
        assertRejected(dated, "NOT NULL");
        ColumnDescription evidenced = column("note", ColumnMetaData.TYPE_STRING, 8, 0);
        evidenced.setMetadataEvidence(evidence(MetadataValue.unknown(), MetadataValue.value(false),
                MetadataValue.unknown(), MetadataValue.unknown()));
        assertRejected(evidenced, "NOT NULL");
        ColumnDescription key = column("id", ColumnMetaData.TYPE_INTEGER, 9, 0);
        key.setPk(true);
        assertRejected(key, "primary key");
        ColumnDescription literal = column("note", ColumnMetaData.TYPE_STRING, 8, 0);
        literal.setDefaultValue("x");
        assertRejected(literal, "default");
        ColumnDescription temporal = column("ts", ColumnMetaData.TYPE_TIMESTAMP, 0, 0);
        temporal.setDefaultValue("2020-01-01");
        assertRejected(temporal, "default");
        ColumnDescription expressed = column("note", ColumnMetaData.TYPE_STRING, 8, 0);
        expressed.setMetadataEvidence(evidence(MetadataValue.value("0"), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.unknown()));
        assertRejected(expressed, "default");
        ColumnDescription identity = column("id", ColumnMetaData.TYPE_INTEGER, 9, 0);
        identity.setAutoIncrement(true);
        assertRejected(identity, "identity");
        ColumnDescription identityFact = column("id", ColumnMetaData.TYPE_INTEGER, 9, 0);
        identityFact.setMetadataEvidence(evidence(MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.value("AUTO_INCREMENT"), MetadataValue.unknown()));
        assertRejected(identityFact, "identity");
        ColumnDescription generated = column("calc", ColumnMetaData.TYPE_INTEGER, 9, 0);
        generated.setFieldTypeName("int generated");
        assertRejected(generated, "generated column");
        ColumnDescription generatedFact = column("calc", ColumnMetaData.TYPE_INTEGER, 9, 0);
        generatedFact.setMetadataEvidence(evidence(MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.value("a+1")));
        assertRejected(generatedFact, "generated column");
    }

    @Test
    void unprovedMysqlTypesAndOversizeTextAreRejected() {
        assertRejected(column("payload", ColumnMetaData.TYPE_BINARY, 100, 0), "unproven type or MySQL clause");
        assertRejected(column("body", ColumnMetaData.TYPE_STRING, 70_000, 0), "unproven type or MySQL clause");
        assertRejected(column("body", ColumnMetaData.TYPE_STRING, 9_000, 0), "8191");
        assertRejected(column("body", ColumnMetaData.TYPE_STRING, -1, 0), "8191");
        assertRejected(column("wide", ColumnMetaData.TYPE_INTEGER, 66, 0), "decimal");
        assertRejected(column("wide", ColumnMetaData.TYPE_NUMBER, 40, 31), "decimal");
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql(" ", "orders",
                column("note", ColumnMetaData.TYPE_STRING, 8, 0)));
    }

    @Test
    void providerDeclaresOnlyTheQuotedNullableAddScope() {
        var product = new Gbase8aProductProvider().products().iterator().next();
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "GBASE8A_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED"),
                product.capabilities().get(DatabaseOperation.DDL_ALTER));
    }

    private String sql(ColumnDescription column) {
        return database.getAddColumnSql("sales", "orders", column);
    }

    private void assertRejected(ColumnDescription column, String feature) {
        SQLFeatureNotSupportedException error = assertThrows(SQLFeatureNotSupportedException.class,
                () -> database.getAddColumnSql("sales", "orders", column));
        assertTrue(error.getMessage().contains(feature), error.getMessage());
        assertEquals("0A000", error.getSQLState());
    }

    private static String quote(String name) {
        return RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.GBASE8A, name);
    }

    private static ColumnDescription column(String name, int valType, int length, int precision) {
        ColumnDescription column = new ColumnDescription();
        column.setFieldName(name);
        column.setValType(valType);
        column.setLength(length);
        column.setPrecision(precision);
        column.setNullable(true);
        column.setDbType(ProductTypeEnum.GBASE8A);
        return column;
    }

    private static ColumnMetadataEvidence evidence(MetadataValue<String> defaults, MetadataValue<Boolean> nullable,
                                                   MetadataValue<String> identity, MetadataValue<String> generated) {
        return new ColumnMetadataEvidence(MetadataProvenance.unknown(), MetadataValue.unknown(), defaults, nullable,
                MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(), identity, generated,
                MetadataValue.unknown(), MetadataValue.unknown());
    }
}

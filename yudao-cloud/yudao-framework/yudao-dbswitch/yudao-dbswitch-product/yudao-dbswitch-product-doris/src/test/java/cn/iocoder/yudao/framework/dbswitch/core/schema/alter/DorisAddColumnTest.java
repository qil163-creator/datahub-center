package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseDorisImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnSequenceMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.product.doris.DorisAddColumn;
import cn.iocoder.yudao.framework.dbswitch.product.doris.DorisProductProvider;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import org.junit.jupiter.api.Test;

import java.sql.SQLFeatureNotSupportedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SQL shape only. No Doris connection, so a passing test is not a server certificate. */
class DorisAddColumnTest {
    private final DatabaseDorisImpl database = new DatabaseDorisImpl();

    @Test
    void nullableAddQuotesIdentifiersAndDropsMysqlClauses() throws Exception {
        ColumnDescription column = column("note", ColumnMetaData.TYPE_STRING, 32, 0);
        column.setRemarks("o'brien");
        String sql = database.getAddColumnSql("sales", "orders", column);
        assertEquals(DorisAddColumn.sql(database, "sales", "orders", column), sql);
        assertEquals("ALTER TABLE " + quote("sales") + "." + quote("orders")
                + " ADD COLUMN " + quote("note") + " VARCHAR(96) NULL", sql);
        assertFalse(sql.contains("COMMENT"));
        assertFalse(sql.contains("NOT NULL"));
        assertFalse(sql.toUpperCase().contains("DEFAULT"));
        assertFalse(sql.contains("AUTO_INCREMENT"));
        assertFalse(sql.toUpperCase().contains("BINARY"));
        assertFalse(sql.contains(" KEY"));
        assertFalse(sql.contains("ENGINE"));
        assertFalse(sql.toUpperCase().contains("CHARSET"));
        assertFalse(sql.toUpperCase().contains("COLLATE"));
        assertFalse(sql.toUpperCase().contains("AFTER"));
        assertFalse(sql.endsWith(";"));
    }

    @Test
    void provedFieldDefinitionTypesStayInTheAddStatement() {
        assertTrue(sql(column("qty", ColumnMetaData.TYPE_INTEGER, 9, 0)).endsWith(" INT NULL"));
        assertTrue(sql(column("qty", ColumnMetaData.TYPE_INTEGER, 15, 0)).endsWith(" BIGINT NULL"));
        assertTrue(sql(column("wide", ColumnMetaData.TYPE_INTEGER, 19, 0)).endsWith(" DECIMAL(19,0) NULL"));
        assertTrue(sql(column("amount", ColumnMetaData.TYPE_NUMBER, 10, 2)).endsWith(" DOUBLE NULL"));
        assertTrue(sql(column("flag", ColumnMetaData.TYPE_BOOLEAN, 1, 0)).endsWith(" TINYINT NULL"));
        assertTrue(sql(column("day", ColumnMetaData.TYPE_DATE, 0, 0)).endsWith(" DATE NULL"));
        assertTrue(sql(column("ts", ColumnMetaData.TYPE_TIMESTAMP, 0, 0)).endsWith(" DATETIME NULL"));
        assertTrue(sql(column("body", ColumnMetaData.TYPE_STRING, 0, 0)).endsWith(" STRING NULL"));
        assertTrue(sql(column("body", ColumnMetaData.TYPE_STRING, 30_000, 0)).endsWith(" STRING NULL"));
        ColumnDescription wordNull = column("note", ColumnMetaData.TYPE_STRING, 8, 0);
        wordNull.setDefaultValue("NULL");
        assertEquals(sql(column("note", ColumnMetaData.TYPE_STRING, 8, 0)), database.getAddColumnSql("sales", "orders", wordNull));
        assertFalse(database.getAddColumnSql("sales", "orders", wordNull).contains("DEFAULT"));
    }

    @Test
    void backtickAndKeyColumnNameAreEscapedByTheGuard() {
        ColumnDescription column = column("a`b", ColumnMetaData.TYPE_INTEGER, 9, 0);
        assertEquals("ALTER TABLE " + quote("sales") + "." + quote("orders")
                + " ADD COLUMN " + quote("a`b") + " INT NULL", database.getAddColumnSql("sales", "orders", column));
        assertEquals("ALTER TABLE " + quote("Gov.Db") + "." + quote("A`B")
                + " ADD COLUMN " + quote("key") + " INT NULL",
                database.getAddColumnSql("Gov.Db", "A`B", column("key", ColumnMetaData.TYPE_INTEGER, 9, 0)));
    }

    @Test
    void keyModelAggregateNotNullPrimaryKeyDefaultIdentityAndGeneratedAreRejected() {
        ColumnDescription keyModel = column("note", ColumnMetaData.TYPE_STRING, 8, 0);
        keyModel.setFieldTypeName("INT KEY");
        assertRejected(keyModel, "KEY model");
        ColumnDescription duplicate = column("id", ColumnMetaData.TYPE_INTEGER, 9, 0);
        duplicate.setFieldTypeName("DUPLICATE KEY");
        assertRejected(duplicate, "KEY model");
        ColumnDescription summed = column("qty", ColumnMetaData.TYPE_INTEGER, 9, 0);
        summed.setFieldTypeName("BIGINT SUM");
        assertRejected(summed, "aggregate column");
        ColumnDescription replaced = column("note", ColumnMetaData.TYPE_STRING, 8, 0);
        replaced.setFieldTypeName("VARCHAR(8) REPLACE_IF_NOT_NULL");
        assertRejected(replaced, "aggregate column");

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
        ColumnDescription namedPrimary = column("id", ColumnMetaData.TYPE_INTEGER, 9, 0);
        namedPrimary.setFieldTypeName("INT PRIMARY KEY");
        assertRejected(namedPrimary, "primary key");

        ColumnDescription literal = column("note", ColumnMetaData.TYPE_STRING, 8, 0);
        literal.setDefaultValue("x");
        assertRejected(literal, "default");
        ColumnDescription empty = column("note", ColumnMetaData.TYPE_STRING, 8, 0);
        empty.setDefaultValue("");
        assertRejected(empty, "default");
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
        ColumnDescription namedIdentity = column("id", ColumnMetaData.TYPE_INTEGER, 9, 0);
        namedIdentity.setFieldTypeName("BIGINT AUTO_INCREMENT");
        assertRejected(namedIdentity, "identity");
        ColumnDescription sequence = column("id", ColumnMetaData.TYPE_INTEGER, 9, 0);
        sequence.setMetadataEvidence(new ColumnMetadataEvidence(MetadataProvenance.unknown(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.value(new ColumnSequenceMetadata(
                        ColumnSequenceMetadata.Kind.IDENTITY_BY_DEFAULT, "app", "seq_id", "integer",
                        1, 1, 1, Integer.MAX_VALUE, 1, false, MetadataValue.unknown()))));
        assertRejected(sequence, "identity");

        ColumnDescription generated = column("calc", ColumnMetaData.TYPE_INTEGER, 9, 0);
        generated.setFieldTypeName("int generated");
        assertRejected(generated, "generated column");
        ColumnDescription generatedFact = column("calc", ColumnMetaData.TYPE_INTEGER, 9, 0);
        generatedFact.setMetadataEvidence(evidence(MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.value("a+1")));
        assertRejected(generatedFact, "generated column");
    }

    @Test
    void unprovedMysqlTypesAndOversizeDecimalAreRejected() {
        assertRejected(column("payload", ColumnMetaData.TYPE_BINARY, 100, 0), "BINARY");
        assertRejected(column("clock", ColumnMetaData.TYPE_TIME, 0, 0), "TIME");
        ColumnDescription timestampName = column("ts", ColumnMetaData.TYPE_TIMESTAMP, 0, 0);
        timestampName.setFieldTypeName("timestamp");
        assertRejected(timestampName, "TIME");
        ColumnDescription fractional = column("ts", ColumnMetaData.TYPE_TIMESTAMP, 0, 0);
        fractional.setFieldTypeName("datetime(6)");
        assertRejected(fractional, "unproven type or MySQL clause");
        ColumnDescription binaryName = column("payload", ColumnMetaData.TYPE_STRING, 8, 0);
        binaryName.setFieldTypeName("blob");
        assertRejected(binaryName, "BINARY");
        assertRejected(column("wide", ColumnMetaData.TYPE_INTEGER, 39, 0), "decimal");
        assertRejected(column("obj", ColumnMetaData.TYPE_SERIALIZABLE, 0, 0), "unproven type or MySQL clause");
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql(" ", "orders",
                column("note", ColumnMetaData.TYPE_STRING, 8, 0)));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("sales", "orders",
                column("n".repeat(65), ColumnMetaData.TYPE_STRING, 8, 0)));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("sales", "orders", null));
        assertThrows(IllegalArgumentException.class, () -> DorisAddColumn.sql(null, "sales", "orders",
                column("note", ColumnMetaData.TYPE_STRING, 8, 0)));
    }

    @Test
    void providerDeclaresOnlyTheQuotedNullableAddScope() {
        var product = new DorisProductProvider().products().iterator().next();
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "DORIS_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED"),
                product.capabilities().get(DatabaseOperation.DDL_ALTER));
    }

    private String sql(ColumnDescription column) {
        return database.getAddColumnSql("sales", "orders", column);
    }

    private void assertRejected(ColumnDescription column, String feature) {
        SQLFeatureNotSupportedException error = assertThrows(SQLFeatureNotSupportedException.class,
                () -> database.getAddColumnSql("sales", "orders", column));
        assertTrue(error.getMessage().contains(feature), error.getMessage());
        assertFalse(error.getMessage().contains("ALTER TABLE"), error.getMessage());
        assertEquals("0A000", error.getSQLState());
    }

    private static String quote(String name) {
        return RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.DORIS, name);
    }

    private static ColumnDescription column(String name, int valType, int length, int precision) {
        ColumnDescription column = new ColumnDescription();
        column.setFieldName(name);
        column.setValType(valType);
        column.setLength(length);
        column.setPrecision(precision);
        column.setNullable(true);
        column.setDbType(ProductTypeEnum.DORIS);
        return column;
    }

    private static ColumnMetadataEvidence evidence(MetadataValue<String> defaults, MetadataValue<Boolean> nullable,
                                                   MetadataValue<String> identity, MetadataValue<String> generated) {
        return new ColumnMetadataEvidence(MetadataProvenance.unknown(), MetadataValue.unknown(), defaults, nullable,
                MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(), identity, generated,
                MetadataValue.unknown(), MetadataValue.unknown());
    }
}

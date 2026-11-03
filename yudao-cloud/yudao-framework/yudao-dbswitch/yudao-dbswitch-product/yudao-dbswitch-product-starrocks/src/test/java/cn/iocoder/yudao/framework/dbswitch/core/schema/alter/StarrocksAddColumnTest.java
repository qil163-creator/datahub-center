package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseStarrocksImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.product.starrocks.StarrocksAddColumn;
import cn.iocoder.yudao.framework.dbswitch.product.starrocks.StarrocksProductProvider;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import org.junit.jupiter.api.Test;

import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SQL shape only. No StarRocks connection, so a passing test is not a server certificate. */
class StarrocksAddColumnTest {
    private final DatabaseStarrocksImpl database = new DatabaseStarrocksImpl();

    @Test
    void quotesOrdinaryNamesAndUsesThisClassFieldDefinition() {
        assertEquals(alter("app", "orders", "note") + " VARCHAR(96)",
                database.getAddColumnSql("app", "orders", text("note", 32)));
        assertEquals(alter("app", "orders", "body") + " STRING",
                database.getAddColumnSql("app", "orders", text("body", 0)));
        assertEquals(alter("app", "orders", "wide") + " STRING",
                database.getAddColumnSql("app", "orders", text("wide", 21845)));
        assertEquals(alter("app", "orders", "qty") + " INT",
                database.getAddColumnSql("app", "orders", typed("qty", Types.INTEGER)));
        assertEquals(alter("app", "orders", "small") + " INT",
                database.getAddColumnSql("app", "orders", typed("small", Types.SMALLINT)));
        assertEquals(alter("app", "orders", "id") + " BIGINT",
                database.getAddColumnSql("app", "orders", typed("id", Types.BIGINT)));
        assertEquals(alter("app", "orders", "exact") + " DECIMAL(20,0)",
                database.getAddColumnSql("app", "orders", decimal("exact", 20, 0)));
        assertEquals(alter("app", "orders", "amount") + " DOUBLE",
                database.getAddColumnSql("app", "orders", decimal("amount", 10, 2)));
        assertEquals(alter("app", "orders", "ratio") + " DOUBLE",
                database.getAddColumnSql("app", "orders", typed("ratio", Types.FLOAT)));
        assertEquals(alter("app", "orders", "flag") + " BOOLEAN",
                database.getAddColumnSql("app", "orders", typed("flag", Types.BOOLEAN)));
        assertEquals(alter("app", "orders", "day") + " DATE",
                database.getAddColumnSql("app", "orders", typed("day", Types.DATE)));
        assertEquals(alter("app", "orders", "seen") + " DATETIME",
                database.getAddColumnSql("app", "orders", typed("seen", Types.TIMESTAMP)));
        assertEquals(alter("app", "orders", "clock") + " DATETIME",
                database.getAddColumnSql("app", "orders", typed("clock", Types.TIME)));
        assertEquals(alter("MiX", "orders", "select") + " INT",
                database.getAddColumnSql("MiX", "orders", typed("select", Types.INTEGER)));
        assertEquals(alter("app", "orders", "a".repeat(63)) + " INT",
                database.getAddColumnSql("app", "orders", typed("a".repeat(63), Types.INTEGER)));
        ColumnDescription remarked = text("note", 32);
        remarked.setRemarks("it's; DROP TABLE t");
        String remarkedSql = database.getAddColumnSql("app", "orders", remarked);
        assertEquals(alter("app", "orders", "note") + " VARCHAR(96)", remarkedSql);
        assertFalse(remarkedSql.contains("COMMENT"));
        assertFalse(remarkedSql.contains("DROP"));
        assertFalse(remarkedSql.contains("NOT NULL"));
        assertFalse(remarkedSql.contains("NULL"));
        assertFalse(remarkedSql.contains("DEFAULT"));
        assertFalse(remarkedSql.contains("TINYINT"));
        assertFalse(remarkedSql.contains(" CHAR("));
        assertFalse(remarkedSql.contains("TEXT"));
        assertFalse(remarkedSql.contains("DECIMAL(10, 2)"));
        assertFalse(remarkedSql.contains("AUTO_INCREMENT"));
        assertFalse(remarkedSql.contains("KEY"));
        assertFalse(remarkedSql.contains("AFTER"));
        assertFalse(remarkedSql.contains("ALGORITHM"));
    }

    @Test
    void refusesPrimaryKeyModelAggregateNotNullDefaultAndGenerated() {
        ColumnDescription primaryKey = typed("id", Types.INTEGER);
        primaryKey.setPk(true);
        assertRefused(primaryKey, "primary key model");
        ColumnDescription primaryClause = typed("id", Types.BIGINT);
        primaryClause.setFieldTypeName("bigint PRIMARY KEY");
        assertRefused(primaryClause, "primary key model");
        ColumnDescription keyClause = typed("id", Types.INTEGER);
        keyClause.setFieldTypeName("int KEY");
        assertRefused(keyClause, "primary key model");

        ColumnDescription aggregate = typed("hits", Types.BIGINT);
        aggregate.setFieldTypeName("bigint SUM");
        assertRefused(aggregate, "aggregate");
        ColumnDescription replace = typed("hits", Types.BIGINT);
        replace.setFieldTypeName("bigint REPLACE_IF_NOT_NULL");
        assertRefused(replace, "aggregate");
        ColumnDescription aggregateKey = typed("id", Types.INTEGER);
        aggregateKey.setFieldTypeName("AGGREGATE KEY");
        assertRefused(aggregateKey, "aggregate");

        ColumnDescription required = text("note", 8);
        required.setNullable(false);
        assertRefused(required, "NOT NULL");
        ColumnDescription notNullClause = text("note", 8);
        notNullClause.setFieldTypeName("varchar(8) NOT NULL");
        assertRefused(notNullClause, "NOT NULL");
        ColumnDescription evidenceNotNull = text("note", 8);
        evidenceNotNull.setMetadataEvidence(facts(MetadataValue.value(false), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown()));
        assertRefused(evidenceNotNull, "NOT NULL");

        ColumnDescription jdbcDefault = typed("qty", Types.INTEGER);
        jdbcDefault.setDefaultValue("0");
        assertRefused(jdbcDefault, "default");
        ColumnDescription nullToken = typed("qty", Types.INTEGER);
        nullToken.setDefaultValue("NULL");
        assertRefused(nullToken, "default");
        ColumnDescription emptyDefault = typed("qty", Types.INTEGER);
        emptyDefault.setDefaultValue("");
        assertRefused(emptyDefault, "default");
        ColumnDescription evidenceDefault = typed("qty", Types.INTEGER);
        evidenceDefault.setMetadataEvidence(facts(MetadataValue.unknown(), MetadataValue.value("0"),
                MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown()));
        assertRefused(evidenceDefault, "default");
        ColumnDescription defaultClause = typed("qty", Types.INTEGER);
        defaultClause.setFieldTypeName("int DEFAULT \"0\"");
        assertRefused(defaultClause, "default");

        ColumnDescription generated = typed("total", Types.INTEGER);
        generated.setMetadataEvidence(facts(MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.value("a + 1"), MetadataValue.unknown(), MetadataValue.unknown()));
        assertRefused(generated, "generated column");
        ColumnDescription generatedClause = typed("total", Types.DOUBLE);
        generatedClause.setFieldTypeName("double AS (a + 1)");
        assertRefused(generatedClause, "generated column");
        ColumnDescription generatedWord = typed("total", Types.INTEGER);
        generatedWord.setFieldTypeName("int GENERATED ALWAYS AS (a)");
        assertRefused(generatedWord, "generated column");
    }

    @Test
    void refusesDroppedMysqlClausesUnmappedTypesAndIllegalIdentifiers() {
        ColumnDescription autoIncrement = typed("id", Types.BIGINT);
        autoIncrement.setAutoIncrement(true);
        assertRefused(autoIncrement, "AUTO_INCREMENT");
        ColumnDescription identity = typed("id", Types.BIGINT);
        identity.setMetadataEvidence(facts(MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.value("AUTO_INCREMENT"), MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown()));
        assertRefused(identity, "AUTO_INCREMENT");
        ColumnDescription charset = text("note", 8);
        charset.setMetadataEvidence(facts(MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.value("utf8mb4"), MetadataValue.unknown()));
        assertRefused(charset, "character set or collation");
        ColumnDescription unsigned = typed("qty", Types.INTEGER);
        unsigned.setFieldTypeName("int unsigned");
        assertRefused(unsigned, "MySQL clause");
        ColumnDescription after = text("note", 8);
        after.setFieldTypeName("varchar(8) AFTER id");
        assertRefused(after, "MySQL clause");
        ColumnDescription otherProduct = typed("qty", Types.INTEGER);
        otherProduct.setDbType(ProductTypeEnum.MYSQL);
        assertRefused(otherProduct, "another product column");
        assertRefused(typed("payload", Types.BINARY), "mapped field type");
        assertRefused(typed("other", Types.OTHER), "mapped field type");
        assertRefused(text("overflow", 800_000_000), "unproven type or MySQL clause");

        assertThrows(IllegalArgumentException.class,
                () -> database.getAddColumnSql(" ", "orders", typed("qty", Types.INTEGER)));
        assertThrows(IllegalArgumentException.class,
                () -> database.getAddColumnSql("app", "b.c", typed("qty", Types.INTEGER)));
        assertThrows(IllegalArgumentException.class,
                () -> database.getAddColumnSql("app", "orders", typed("a`b", Types.INTEGER)));
        assertThrows(IllegalArgumentException.class,
                () -> database.getAddColumnSql("app", "orders", typed("a".repeat(64), Types.INTEGER)));
        assertThrows(IllegalArgumentException.class,
                () -> StarrocksAddColumn.sql(null, "app", "orders", typed("qty", Types.INTEGER)));
    }

    @Test
    void declaresScopedAlterOnTheStarRocksProviderOnly() {
        var product = new StarrocksProductProvider().products().iterator().next();
        assertEquals(ProductTypeEnum.STARROCKS, product.product());
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "STARROCKS_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED"),
                product.capabilities().get(DatabaseOperation.DDL_ALTER));
    }

    private void assertRefused(ColumnDescription column, String messagePart) {
        var thrown = assertThrows(SQLFeatureNotSupportedException.class,
                () -> database.getAddColumnSql("app", "orders", column));
        assertEquals("0A000", thrown.getSQLState());
        assertTrue(thrown.getMessage().contains(messagePart), thrown.getMessage());
        var direct = assertThrows(SQLFeatureNotSupportedException.class,
                () -> StarrocksAddColumn.sql(database, "app", "orders", column));
        assertEquals(thrown.getMessage(), direct.getMessage());
    }

    private static String alter(String schema, String table, String column) {
        return "ALTER TABLE " + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.STARROCKS, schema)
                + "." + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.STARROCKS, table)
                + " ADD COLUMN " + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.STARROCKS, column);
    }

    private static ColumnDescription text(String name, int displaySize) {
        ColumnDescription column = typed(name, Types.VARCHAR);
        column.setDisplaySize(displaySize);
        return column;
    }

    private static ColumnDescription decimal(String name, int precision, int scale) {
        ColumnDescription column = typed(name, Types.DECIMAL);
        column.setPrecisionSize(precision);
        column.setScaleSize(scale);
        return column;
    }

    private static ColumnDescription typed(String name, int jdbcType) {
        ColumnDescription column = new ColumnDescription();
        column.setFieldName(name);
        column.setNullable(true);
        column.setSigned(true);
        column.setFieldType(jdbcType);
        column.setDbType(ProductTypeEnum.STARROCKS);
        return column;
    }

    private static ColumnMetadataEvidence facts(MetadataValue<Boolean> nullable, MetadataValue<String> defaults,
            MetadataValue<String> identity, MetadataValue<String> generated, MetadataValue<String> charset,
            MetadataValue<String> collation) {
        return new ColumnMetadataEvidence(MetadataProvenance.unknown(), MetadataValue.unknown(), defaults, nullable,
                MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(), identity, generated,
                charset, collation);
    }
}

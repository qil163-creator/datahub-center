package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseTidbImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.product.tidb.TidbAddColumn;
import cn.iocoder.yudao.framework.dbswitch.product.tidb.TidbProductProvider;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import org.junit.jupiter.api.Test;

import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TidbAddColumnTest {
    @Test
    void quotesSchemaTableAndColumnSeparatelyAndUsesFieldDefinition() {
        var database = new DatabaseTidbImpl();
        assertEquals(alter("app", "orders", "note") + " VARCHAR(32)",
                database.getAddColumnSql("app", "orders", text("note", 32)));
        assertEquals(alter("app", "orders", "qty") + " INT",
                database.getAddColumnSql("app", "orders", integer("qty")));
        assertEquals(alter("app", "orders", "amount") + " DECIMAL(10, 2)",
                database.getAddColumnSql("app", "orders", decimal("amount")));
        assertEquals(alter("app", "orders", "seen") + " DATETIME",
                database.getAddColumnSql("app", "orders", typed("seen", Types.TIMESTAMP)));
        assertEquals(alter("app", "orders", "flag") + " TINYINT",
                database.getAddColumnSql("app", "orders", typed("flag", Types.BOOLEAN)));
        assertEquals(alter("a`b", "order", "c`d") + " INT",
                database.getAddColumnSql("a`b", "order", integer("c`d")));
        assertEquals(alter("db", "b.c", "select") + " INT",
                database.getAddColumnSql("db", "b.c", integer("select")));
        ColumnDescription remarked = text("note", 32);
        remarked.setRemarks("it's; DROP TABLE t");
        String remarkedSql = database.getAddColumnSql("app", "orders", remarked);
        assertEquals(alter("app", "orders", "note") + " VARCHAR(32)", remarkedSql);
        assertFalse(remarkedSql.contains("COMMENT"));
        assertFalse(remarkedSql.contains("DROP"));
        assertFalse(remarkedSql.contains(" NOT NULL"));
        assertFalse(remarkedSql.contains(" DEFAULT"));
    }

    @Test
    void refusesNotNullPrimaryKeyDefaultAutoAndGenerated() {
        ColumnDescription omittedNullability = text("note", 32);
        omittedNullability.setNullable(false);
        assertRefused(omittedNullability, "NOT NULL");
        ColumnDescription notNullClause = text("note", 32);
        notNullClause.setFieldTypeName("varchar(32) NOT NULL");
        assertRefused(notNullClause, "NOT NULL");
        ColumnDescription evidenceNotNull = text("note", 32);
        evidenceNotNull.setMetadataEvidence(facts(MetadataValue.value(false), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown()));
        assertRefused(evidenceNotNull, "NOT NULL");

        ColumnDescription primaryKey = integer("id");
        primaryKey.setPk(true);
        assertRefused(primaryKey, "primary key");
        ColumnDescription primaryClause = integer("id");
        primaryClause.setFieldTypeName("bigint PRIMARY KEY");
        assertRefused(primaryClause, "primary key");

        ColumnDescription jdbcDefault = integer("qty");
        jdbcDefault.setDefaultValue("0");
        assertRefused(jdbcDefault, "default");
        ColumnDescription nullToken = integer("qty");
        nullToken.setDefaultValue("NULL");
        assertRefused(nullToken, "default");
        ColumnDescription evidenceDefault = integer("qty");
        evidenceDefault.setMetadataEvidence(facts(MetadataValue.unknown(), MetadataValue.value("0"),
                MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown()));
        assertRefused(evidenceDefault, "default");

        ColumnDescription autoIncrement = integer("id");
        autoIncrement.setAutoIncrement(true);
        assertRefused(autoIncrement, "AUTO_RANDOM or AUTO_INCREMENT");
        ColumnDescription autoRandom = integer("id");
        autoRandom.setMetadataEvidence(facts(MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.value("AUTO_RANDOM"), MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown()));
        assertRefused(autoRandom, "AUTO_RANDOM or AUTO_INCREMENT");
        ColumnDescription autoClause = integer("id");
        autoClause.setFieldTypeName("bigint AUTO_INCREMENT");
        assertRefused(autoClause, "AUTO_RANDOM or AUTO_INCREMENT");

        ColumnDescription generated = integer("total");
        generated.setMetadataEvidence(facts(MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.value("(a + 1)"), MetadataValue.unknown(), MetadataValue.unknown()));
        assertRefused(generated, "generated column");
        ColumnDescription generatedClause = integer("total");
        generatedClause.setFieldTypeName("int GENERATED ALWAYS AS (a) VIRTUAL");
        assertRefused(generatedClause, "generated column");

        ColumnDescription charset = text("note", 32);
        charset.setMetadataEvidence(facts(MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.value("utf8mb4"), MetadataValue.unknown()));
        assertRefused(charset, "character set or collation");
        ColumnDescription unmapped = integer("note");
        unmapped.setFieldType(Types.OTHER);
        assertRefused(unmapped, "mapped field type");

        assertThrows(IllegalArgumentException.class,
                () -> new DatabaseTidbImpl().getAddColumnSql(" ", "orders", integer("qty")));
        assertThrows(IllegalArgumentException.class,
                () -> new DatabaseTidbImpl().getAddColumnSql("app", "orders", integer("a".repeat(65))));
    }

    @Test
    void declaresScopedAlterOnTheTidbProviderOnly() {
        var product = new TidbProductProvider().products().iterator().next();
        assertEquals(ProductTypeEnum.TIDB, product.product());
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                "TIDB_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED"), product.capabilities().get(DatabaseOperation.DDL_ALTER));
    }

    private static void assertRefused(ColumnDescription column, String messagePart) {
        var database = new DatabaseTidbImpl();
        var thrown = assertThrows(SQLFeatureNotSupportedException.class,
                () -> database.getAddColumnSql("app", "orders", column));
        assertEquals("0A000", thrown.getSQLState());
        assertTrue(thrown.getMessage().contains(messagePart), thrown.getMessage());
        var direct = assertThrows(SQLFeatureNotSupportedException.class,
                () -> TidbAddColumn.sql(database, "app", "orders", column));
        assertEquals(thrown.getMessage(), direct.getMessage());
    }

    private static String alter(String schema, String table, String column) {
        return "ALTER TABLE " + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.TIDB, schema)
                + "." + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.TIDB, table)
                + " ADD COLUMN " + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.TIDB, column);
    }

    private static ColumnDescription integer(String name) {
        return typed(name, Types.INTEGER);
    }

    private static ColumnDescription text(String name, int displaySize) {
        ColumnDescription column = typed(name, Types.VARCHAR);
        column.setDisplaySize(displaySize);
        return column;
    }

    private static ColumnDescription decimal(String name) {
        ColumnDescription column = typed(name, Types.DECIMAL);
        column.setPrecisionSize(10);
        column.setScaleSize(2);
        return column;
    }

    private static ColumnDescription typed(String name, int jdbcType) {
        ColumnDescription column = new ColumnDescription();
        column.setFieldName(name);
        column.setNullable(true);
        column.setFieldType(jdbcType);
        column.setDbType(ProductTypeEnum.TIDB);
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

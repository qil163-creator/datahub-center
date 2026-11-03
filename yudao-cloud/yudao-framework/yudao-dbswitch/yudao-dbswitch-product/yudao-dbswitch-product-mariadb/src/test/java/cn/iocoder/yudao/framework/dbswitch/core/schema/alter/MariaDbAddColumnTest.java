package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseMariaDBImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.product.mariadb.MariadbProductProvider;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import org.junit.jupiter.api.Test;

import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SQL shape only. This class does not open a MariaDB connection. */
class MariaDbAddColumnTest {
    private final DatabaseMariaDBImpl database = new DatabaseMariaDBImpl();

    @Test
    void nullableAddQuotesSchemaTableAndColumnSeparately() {
        var column = varchar("no`te", 32);
        column.setRemarks("it's; DROP TABLE t");
        String sql = database.getAddColumnSql("app.db", "ord`ers", column);
        String expected = "ALTER TABLE " + quote("app.db") + "." + quote("ord`ers")
                + " ADD COLUMN " + quote("no`te") + " " + parentType(column);
        assertEquals(expected, sql);
        assertEquals("VARCHAR(32)", parentType(column));
        assertEquals("ALTER TABLE `app.db`.`ord``ers` ADD COLUMN `no``te` VARCHAR(32)", sql);
        assertFalse(sql.contains("COMMENT"));
        assertFalse(sql.contains("DROP"));
        assertFalse(sql.contains("`app`.`db`"));
        assertFalse(sql.contains("`app.db.ord``ers`"));
        assertFalse(sql.contains(" NOT NULL"));
        assertFalse(sql.contains(" DEFAULT"));
        assertFalse(sql.contains("AUTO_INCREMENT"));
        assertFalse(sql.contains("GENERATED"));
    }

    @Test
    void typeTextComesFromTheExistingFieldDefinition() {
        var quantity = column(Types.INTEGER, "qty");
        var amount = column(Types.DECIMAL, "amount");
        amount.setPrecisionSize(12);
        amount.setScaleSize(2);
        var day = column(Types.DATE, "day");
        assertEquals("ALTER TABLE " + quote("app") + "." + quote("orders") + " ADD COLUMN " + quote("qty") + " INT",
                database.getAddColumnSql("app", "orders", quantity));
        assertEquals("INT", parentType(quantity));
        assertEquals("ALTER TABLE " + quote("app") + "." + quote("orders") + " ADD COLUMN " + quote("amount") + " DECIMAL(12, 2)",
                database.getAddColumnSql("app", "orders", amount));
        assertEquals("DECIMAL(12, 2)", parentType(amount));
        assertEquals("ALTER TABLE " + quote("app") + "." + quote("orders") + " ADD COLUMN " + quote("day") + " DATE",
                database.getAddColumnSql("app", "orders", day));
        assertEquals("DATE", parentType(day));
    }

    @Test
    void rejectedShapesThrowBeforeSqlIsReturned() {
        assertRejected("NOT NULL", notNull());
        var dated = column(Types.DATE, "day");
        dated.setNullable(false);
        assertRejected("NOT NULL", dated);
        var evidencedNotNull = varchar("note", 32);
        evidence(evidencedNotNull, MetadataValue.absent(), MetadataValue.value(false), MetadataValue.absent(), MetadataValue.absent());
        assertRejected("NOT NULL", evidencedNotNull);
        var key = varchar("note", 32);
        key.setPk(true);
        assertRejected("primary key", key);
        for (String defaultValue : new String[] {"0", "null", "NULL", ""}) {
            var withDefault = varchar("note", 32);
            withDefault.setDefaultValue(defaultValue);
            assertRejected("default", withDefault);
        }
        var evidencedDefault = varchar("note", 32);
        evidence(evidencedDefault, MetadataValue.value("0"), MetadataValue.value(true), MetadataValue.absent(), MetadataValue.absent());
        assertRejected("default", evidencedDefault);
        var increment = varchar("note", 32);
        increment.setAutoIncrement(true);
        assertRejected("identity", increment);
        var evidencedIdentity = varchar("note", 32);
        evidence(evidencedIdentity, MetadataValue.absent(), MetadataValue.value(true), MetadataValue.value("AUTO_INCREMENT"), MetadataValue.absent());
        assertRejected("identity", evidencedIdentity);
        var generated = varchar("note", 32);
        evidence(generated, MetadataValue.absent(), MetadataValue.value(true), MetadataValue.absent(), MetadataValue.value("(`a` + `b`)"));
        assertRejected("generated column", generated);
        var namedGenerated = varchar("note", 32);
        namedGenerated.setFieldTypeName("int generated always");
        assertRejected("generated column", namedGenerated);
        var namedIdentity = varchar("note", 32);
        namedIdentity.setFieldTypeName("bigint auto_increment");
        assertRejected("identity", namedIdentity);
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("app", "orders", null));
    }

    @Test
    void physicalIdentifierRulesStayWithTheGuard() {
        String tooLong = "a".repeat(65);
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql(tooLong, "orders", varchar("note", 32)));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("app", tooLong, varchar("note", 32)));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("app", "orders", varchar(tooLong, 32)));
    }

    @Test
    void providerDeclaresOnlyTheNullableAddScope() {
        var product = new MariadbProductProvider().products().iterator().next();
        assertEquals(ProductTypeEnum.MARIADB, product.product());
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "MARIADB_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED"),
                product.capabilities().get(DatabaseOperation.DDL_ALTER));
    }

    private void assertRejected(String reason, ColumnDescription column) {
        var failure = assertThrows(SQLFeatureNotSupportedException.class,
                () -> database.getAddColumnSql("app", "orders", column));
        assertEquals("0A000", failure.getSQLState());
        assertTrue(failure.getMessage().contains(reason), failure.getMessage());
    }

    private String parentType(ColumnDescription column) {
        String quoted = quote(column.getFieldName());
        String definition = database.getFieldDefinition(column.getMetaData(), null, false, false, false);
        String prefix = " " + quoted + "  ";
        assertTrue(definition.startsWith(prefix), definition);
        return definition.substring(prefix.length()).strip();
    }

    private static String quote(String name) {
        return RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.MARIADB, name);
    }

    private static ColumnDescription varchar(String name, int length) {
        var column = column(Types.VARCHAR, name);
        column.setDisplaySize(length);
        return column;
    }

    private static ColumnDescription column(int jdbcType, String name) {
        var column = new ColumnDescription();
        column.setFieldName(name);
        column.setFieldType(jdbcType);
        column.setNullable(true);
        column.setDbType(ProductTypeEnum.MARIADB);
        return column;
    }

    private static ColumnDescription notNull() {
        var column = varchar("note", 32);
        column.setNullable(false);
        return column;
    }

    private static void evidence(ColumnDescription column, MetadataValue<String> defaultExpression,
                                 MetadataValue<Boolean> nullable, MetadataValue<String> identity,
                                 MetadataValue<String> generated) {
        column.setMetadataEvidence(new ColumnMetadataEvidence(MetadataProvenance.unknown(), MetadataValue.unknown(),
                defaultExpression, nullable, MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(),
                identity, generated, MetadataValue.unknown(), MetadataValue.unknown()));
    }
}

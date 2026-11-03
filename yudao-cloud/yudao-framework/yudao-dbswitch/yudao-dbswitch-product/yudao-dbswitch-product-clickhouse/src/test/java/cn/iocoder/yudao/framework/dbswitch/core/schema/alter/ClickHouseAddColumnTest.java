package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseClickhouseImpl;
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
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Generated SQL and refusal only. No ClickHouse connection is opened. */
class ClickHouseAddColumnTest {
    private static final String REASON = "CLICKHOUSE_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED";
    private final DatabaseClickhouseImpl database = new DatabaseClickhouseImpl();

    @Test
    void providerDeclaresQuotedNullableAddWithoutCertifyingAServer() {
        var clickhouse = DbSwitchProductRegistry.discover().products().stream()
                .filter(product -> product.product() == ProductTypeEnum.CLICKHOUSE).findFirst().orElseThrow();
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED, REASON),
                clickhouse.capabilities().get(DatabaseOperation.DDL_ALTER));
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "note;" + Types.VARCHAR + ";" + ColumnMetaData.TYPE_STRING + ";20;0;Nullable(String)",
            "code;" + Types.CHAR + ";" + ColumnMetaData.TYPE_STRING + ";1;0;Nullable(String)",
            "body;" + Types.CLOB + ";" + ColumnMetaData.TYPE_STRING + ";2048;0;Nullable(String)",
            "payload;" + Types.BLOB + ";" + ColumnMetaData.TYPE_BINARY + ";0;0;Nullable(String)",
            "amount;" + Types.DECIMAL + ";" + ColumnMetaData.TYPE_BIGNUMBER + ";10;2;Nullable(Decimal(10,2))",
            "ratio;" + Types.NUMERIC + ";" + ColumnMetaData.TYPE_BIGNUMBER + ";8;0;Nullable(Decimal(8,0))",
            "wide;" + Types.BIGINT + ";" + ColumnMetaData.TYPE_BIGNUMBER + ";20;0;Nullable(Decimal(20,0))",
            "qty;" + Types.INTEGER + ";" + ColumnMetaData.TYPE_INTEGER + ";9;0;Nullable(Int64)",
            "id;" + Types.BIGINT + ";" + ColumnMetaData.TYPE_INTEGER + ";15;0;Nullable(Int64)",
            "small;" + Types.SMALLINT + ";" + ColumnMetaData.TYPE_INTEGER + ";4;0;Nullable(Int32)",
            "tiny;" + Types.TINYINT + ";" + ColumnMetaData.TYPE_INTEGER + ";2;0;Nullable(Int16)",
            "real;" + Types.REAL + ";" + ColumnMetaData.TYPE_NUMBER + ";0;0;Nullable(Float32)",
            "measure;" + Types.DOUBLE + ";" + ColumnMetaData.TYPE_NUMBER + ";0;0;Nullable(Float64)",
            "flag;" + Types.BIT + ";" + ColumnMetaData.TYPE_BOOLEAN + ";0;0;Nullable(Bool)",
            "day;" + Types.DATE + ";" + ColumnMetaData.TYPE_DATE + ";0;0;Nullable(Date32)",
            "seen;" + Types.TIMESTAMP + ";" + ColumnMetaData.TYPE_TIMESTAMP + ";0;0;Nullable(DateTime64(9))"
    })
    void nullableAddReusesFieldDefinitionAndBacktickQuotes(String column, int sqlType, int valueType,
            int length, int precision, String type) {
        var description = column(column, sqlType, valueType, length, precision);
        description.setRemarks("not emitted");
        String sql = database.getAddColumnSql("app", "items", description);
        String rendered = database.getFieldDefinition(description.getMetaData(), null, false, false, false);
        String quotedColumn = RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.CLICKHOUSE, column);
        assertEquals(" " + quotedColumn + " " + type, rendered);
        assertEquals("ALTER TABLE "
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.CLICKHOUSE, "app") + "."
                + RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.CLICKHOUSE, "items")
                + " ADD COLUMN " + quotedColumn + " " + type, sql);
        assertEquals("ALTER TABLE `app`.`items` ADD COLUMN `" + column + "` " + type, sql);
        assertFalse(sql.contains("app.items"));
        assertFalse(sql.contains("\""));
        assertFalse(sql.contains("NOT NULL"));
        assertFalse(sql.toUpperCase(java.util.Locale.ROOT).contains("DEFAULT"));
        assertFalse(sql.contains("MATERIALIZED") || sql.contains("ALIAS") || sql.contains("EPHEMERAL"));
        assertFalse(sql.contains("LowCardinality"));
        assertFalse(sql.contains("PRIMARY KEY") || sql.contains("CODEC") || sql.contains("COMMENT"));
        assertFalse(sql.contains("IF NOT EXISTS") || sql.contains("ON CLUSTER"));
        assertFalse(sql.endsWith(";"));
    }

    @Test
    void quotesOrdinaryNamesWithoutFoldingCase() {
        var description = column("Note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        assertEquals("ALTER TABLE `App`.`MiX` ADD COLUMN `Note` Nullable(String)",
                database.getAddColumnSql("App", "MiX", description));
        String name = "c" + "a".repeat(62);
        assertEquals(63, name.length());
        assertTrue(database.getAddColumnSql("app", "items", column(name, Types.VARCHAR, ColumnMetaData.TYPE_STRING, 8, 0))
                .contains("`" + name + "`"));
    }

    @Test
    void catalogNullableStringIsEmittedWhenItMatchesTheFieldDefinition() {
        var description = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        description.setMetadataEvidence(new ColumnMetadataEvidence(clickHouse(),
                MetadataValue.value("Nullable(String)"), MetadataValue.absent(), MetadataValue.value(true),
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.unknown(),
                MetadataValue.absent(), MetadataValue.absent(), MetadataValue.absent()));
        assertEquals("ALTER TABLE `app`.`items` ADD COLUMN `note` Nullable(String)",
                database.getAddColumnSql("app", "items", description));
    }

    @Test
    void notNullPrimaryKeyDefaultAliasAndLowCardinalityAreRefused() {
        var required = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        required.setNullable(false);
        assertRefused(required, "NOT NULL");

        var evidenced = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        evidence(evidenced, MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.absent(), MetadataValue.value(false));
        assertRefused(evidenced, "NOT NULL");

        var key = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        key.setPk(true);
        assertRefused(key, "primary key");

        var fallback = column("day", Types.DATE, ColumnMetaData.TYPE_DATE, 0, 0);
        fallback.setDefaultValue("today()");
        assertRefused(fallback, "DEFAULT");

        var wordNull = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        wordNull.setDefaultValue("null");
        assertRefused(wordNull, "DEFAULT");
        var wordNullUpper = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        wordNullUpper.setDefaultValue("NULL");
        assertRefused(wordNullUpper, "DEFAULT");

        var declaredDefault = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        evidence(declaredDefault, MetadataValue.unknown(), MetadataValue.absent(), MetadataValue.value("1"), MetadataValue.value(true));
        assertRefused(declaredDefault, "DEFAULT");

        var materialized = column("v", Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0);
        evidence(materialized, MetadataValue.unknown(), MetadataValue.unsupported(), MetadataValue.unsupported(), MetadataValue.value(true));
        assertRefused(materialized, "MATERIALIZED");
        assertRefused(materialized, "ALIAS");

        var alias = column("v", Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0);
        evidence(alias, MetadataValue.unknown(), MetadataValue.value("id + 1"), MetadataValue.absent(), MetadataValue.value(true));
        assertRefused(alias, "ALIAS");

        var named = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        named.setFieldTypeName("Nullable(String) ALIAS city");
        assertRefused(named, "ALIAS");

        var lowRead = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        lowRead.setFieldTypeName("LowCardinality(Nullable(String))");
        assertRefused(lowRead, "LowCardinality");

        var lowNative = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        lowNative.setMetadataEvidence(new ColumnMetadataEvidence(clickHouse(),
                MetadataValue.value("LowCardinality(String)"), MetadataValue.absent(), MetadataValue.value(true),
                MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.absent(), MetadataValue.unknown(), MetadataValue.unknown()));
        assertRefused(lowNative, "LowCardinality");

        var lowNullable = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        lowNullable.setMetadataEvidence(new ColumnMetadataEvidence(clickHouse(),
                MetadataValue.value("LowCardinality(Nullable(String))"), MetadataValue.absent(), MetadataValue.value(true),
                MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.absent(), MetadataValue.unknown(), MetadataValue.unknown()));
        assertRefused(lowNullable, "LowCardinality");

        var rewritten = column("qty", Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0);
        rewritten.setMetadataEvidence(new ColumnMetadataEvidence(clickHouse(),
                MetadataValue.value("Nullable(Int32)"), MetadataValue.absent(), MetadataValue.value(true),
                MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.absent(), MetadataValue.unknown(), MetadataValue.unknown()));
        assertRefused(rewritten, "native type");

        var spacedDecimal = column("amount", Types.DECIMAL, ColumnMetaData.TYPE_BIGNUMBER, 10, 2);
        spacedDecimal.setMetadataEvidence(new ColumnMetadataEvidence(clickHouse(),
                MetadataValue.value("Nullable(Decimal(10, 2))"), MetadataValue.absent(), MetadataValue.value(true),
                MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.absent(), MetadataValue.unknown(), MetadataValue.unknown()));
        assertRefused(spacedDecimal, "native type");

        var increment = column("id", Types.BIGINT, ColumnMetaData.TYPE_INTEGER, 15, 0);
        increment.setAutoIncrement(true);
        assertRefused(increment, "identity");

        var sequence = column("id", Types.BIGINT, ColumnMetaData.TYPE_INTEGER, 15, 0);
        sequence.setMetadataEvidence(new ColumnMetadataEvidence(MetadataProvenance.unknown(), MetadataValue.unknown(),
                MetadataValue.absent(), MetadataValue.value(true), MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.absent(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.unsupported()));
        assertRefused(sequence, "identity");

        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("app", "items", null));
        assertThrows(UnsupportedOperationException.class, () -> database.getAddColumnSql("app", "items",
                column("at", Types.TIME, ColumnMetaData.TYPE_TIME, 0, 0)));
    }

    @Test
    void ordinaryIdentifierRulesRejectQuotedEmptyOrOversizedNamesBeforeSqlIsEmitted() {
        var column = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql(null, "items", column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("app", "", column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("`app`", "items", column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("app", "bad-name", column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("app", "items",
                column("1bad", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0)));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("app", "items",
                column("c" + "a".repeat(63), Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0)));
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
        description.setDbType(ProductTypeEnum.CLICKHOUSE);
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

    private static MetadataProvenance clickHouse() {
        return new MetadataProvenance(ProductTypeEnum.CLICKHOUSE, "ClickHouse", "25.8.33.6",
                "CLICKHOUSE_CATALOG_V1", Instant.EPOCH);
    }
}

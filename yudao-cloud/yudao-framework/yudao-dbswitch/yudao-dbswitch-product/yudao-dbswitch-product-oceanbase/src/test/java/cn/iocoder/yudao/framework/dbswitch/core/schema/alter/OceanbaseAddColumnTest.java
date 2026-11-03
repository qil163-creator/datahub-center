package cn.iocoder.yudao.framework.dbswitch.core.schema.alter;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.database.impl.DatabaseOceanbaseImpl;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetadataEvidence;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnSequenceMetadata;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataProvenance;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.service.RestrictedSelectGuard;
import cn.iocoder.yudao.framework.dbswitch.product.oceanbase.OceanbaseAddColumn;
import cn.iocoder.yudao.framework.dbswitch.product.oceanbase.OceanbaseProductProvider;
import cn.iocoder.yudao.framework.dbswitch.spi.DatabaseOperation;
import cn.iocoder.yudao.framework.dbswitch.spi.OperationCapability;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Generated SQL and refusal only. No OceanBase connection is opened. */
class OceanbaseAddColumnTest {
    @Test
    void providerDeclaresMysqlNullableAddWithoutCertifyingAServer() {
        var product = new OceanbaseProductProvider().products().iterator().next();
        assertEquals(ProductTypeEnum.OCEANBASE, product.product());
        assertEquals(new OperationCapability(OperationCapability.Status.DECLARED,
                        "OCEANBASE_OBSERVED_MODE_NULLABLE_ADD_COLUMN_NO_DEFAULT_QUOTED"),
                product.capabilities().get(DatabaseOperation.DDL_ALTER));
    }

    @Test
    void mysqlModeReusesFieldDefinitionAndQuotesEachIdentifier() throws Exception {
        Connection connection = mode("MYSQL");
        DatabaseOceanbaseImpl database = new DatabaseOceanbaseImpl();
        database.prepareDialect(connection);
        clearInvocations(connection);

        assertAdd(database, column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0),
                "ALTER TABLE `app`.`items` ADD COLUMN `note`  VARCHAR(20)");
        assertAdd(database, column("amount", Types.DECIMAL, ColumnMetaData.TYPE_BIGNUMBER, 10, 2),
                "ALTER TABLE `app`.`items` ADD COLUMN `amount`  DECIMAL(10, 2)");
        assertAdd(database, column("qty", Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0),
                "ALTER TABLE `app`.`items` ADD COLUMN `qty`  INT");
        assertAdd(database, column("id", Types.BIGINT, ColumnMetaData.TYPE_INTEGER, 15, 0),
                "ALTER TABLE `app`.`items` ADD COLUMN `id`  BIGINT");
        assertAdd(database, column("flag", Types.BIT, ColumnMetaData.TYPE_BOOLEAN, 0, 0),
                "ALTER TABLE `app`.`items` ADD COLUMN `flag`  TINYINT");
        assertAdd(database, column("payload", Types.BLOB, ColumnMetaData.TYPE_BINARY, 0, 0),
                "ALTER TABLE `app`.`items` ADD COLUMN `payload`  LONGBLOB");
        assertAdd(database, column("body", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 2048, 0),
                "ALTER TABLE `app`.`items` ADD COLUMN `body`  TEXT");
        assertAdd(database, column("code", Types.CHAR, ColumnMetaData.TYPE_STRING, 1, 0),
                "ALTER TABLE `app`.`items` ADD COLUMN `code`  CHAR(1)");
        assertAdd(database, column("day", Types.DATE, ColumnMetaData.TYPE_DATE, 0, 0),
                "ALTER TABLE `app`.`items` ADD COLUMN `day` DATE");
        assertAdd(database, column("seen", Types.TIMESTAMP, ColumnMetaData.TYPE_TIMESTAMP, 6, 0),
                "ALTER TABLE `app`.`items` ADD COLUMN `seen` DATETIME(6)");
        assertAdd(database, column("at", Types.TIME, ColumnMetaData.TYPE_TIME, 0, 0),
                "ALTER TABLE `app`.`items` ADD COLUMN `at` TIME(0)");

        ColumnDescription remarked = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        remarked.setRemarks("it's; DROP TABLE t");
        String remarkedSql = database.getAddColumnSql("app", "items", remarked);
        assertEquals("ALTER TABLE `app`.`items` ADD COLUMN `note`  VARCHAR(20)", remarkedSql);
        assertEquals(remarkedSql, OceanbaseAddColumn.sql(database, "app", "items", remarked));
        assertFalse(remarkedSql.contains("COMMENT"));
        assertFalse(remarkedSql.contains("DROP"));
        assertFalse(remarkedSql.contains("\""));
        assertFalse(remarkedSql.endsWith(";"));
        assertFalse(remarkedSql.contains("NOT NULL"));
        assertFalse(remarkedSql.toUpperCase(java.util.Locale.ROOT).contains("DEFAULT"));
        assertFalse(remarkedSql.contains("AUTO_INCREMENT"));
        assertFalse(remarkedSql.contains("GENERATED"));
        verifyNoInteractions(connection);
    }

    @Test
    void ordinaryNamesAreBacktickQuotedAndARejectedModeChangeDoesNotDropMysqlMode() throws Exception {
        DatabaseOceanbaseImpl database = mysql();
        assertThrows(IllegalStateException.class, () -> database.prepareDialect(mode("ORACLE")));
        String name = "n".repeat(63);
        ColumnDescription column = column(name, Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0);
        String sql = database.getAddColumnSql("app_db", "order", column);
        assertEquals("ALTER TABLE " + quote("app_db") + "." + quote("order") + " ADD COLUMN " + quote(name) + "  INT", sql);
        assertEquals("ALTER TABLE `app_db`.`order` ADD COLUMN `" + name + "`  INT", sql);
        assertEquals(sql, OceanbaseAddColumn.sql(database, "app_db", "order", column));
    }

    @Test
    void oracleModeReusesOracleTypeAndNullableAddRenderer() throws Exception {
        Connection connection = mode("ORACLE");
        DatabaseOceanbaseImpl database = new DatabaseOceanbaseImpl();
        database.prepareDialect(connection);
        clearInvocations(connection);
        ColumnDescription column = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        assertEquals("ALTER TABLE \"app\".\"items\" ADD (\"note\" VARCHAR2(20))",
                database.getAddColumnSql("app", "items", column));
        column.setNullable(false);
        assertThrows(SQLFeatureNotSupportedException.class, () -> database.getAddColumnSql("app", "items", column));
        verifyNoInteractions(connection);
    }

    @Test
    void unverifiedModeDoesNotGenerateTheStatement() {
        ColumnDescription column = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        var failure = assertThrows(IllegalStateException.class,
                () -> new DatabaseOceanbaseImpl().getAddColumnSql("app", "items", column));
        assertTrue(failure.getMessage().contains("compatibility mode"), failure.getMessage());
        assertFalse(failure.getMessage().contains("ALTER"), failure.getMessage());
        assertThrows(IllegalStateException.class,
                () -> OceanbaseAddColumn.sql(new DatabaseOceanbaseImpl(), "app", "items", column));
    }

    @Test
    void notNullPrimaryKeyDefaultIdentityAndGeneratedAreRefused() throws Exception {
        DatabaseOceanbaseImpl database = mysql();
        ColumnDescription required = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        required.setNullable(false);
        assertRefused(database, required, "NOT NULL");

        ColumnDescription dated = column("day", Types.DATE, ColumnMetaData.TYPE_DATE, 0, 0);
        dated.setNullable(false);
        assertRefused(database, dated, "NOT NULL");
        ColumnDescription timed = column("seen", Types.TIMESTAMP, ColumnMetaData.TYPE_TIMESTAMP, 6, 0);
        timed.setNullable(false);
        assertRefused(database, timed, "NOT NULL");

        ColumnDescription evidencedNotNull = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        evidence(evidencedNotNull, MetadataValue.absent(), MetadataValue.value(false), MetadataValue.absent(), MetadataValue.absent());
        assertRefused(database, evidencedNotNull, "NOT NULL");
        ColumnDescription unsupportedNull = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        evidence(unsupportedNull, MetadataValue.absent(), MetadataValue.unsupported(), MetadataValue.absent(), MetadataValue.absent());
        assertRefused(database, unsupportedNull, "NOT NULL");
        ColumnDescription namedNotNull = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        namedNotNull.setFieldTypeName("varchar(20) NOT NULL");
        assertRefused(database, namedNotNull, "NOT NULL");

        ColumnDescription key = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        key.setPk(true);
        assertRefused(database, key, "primary key");
        ColumnDescription namedKey = column("id", Types.BIGINT, ColumnMetaData.TYPE_INTEGER, 15, 0);
        namedKey.setFieldTypeName("bigint PRIMARY KEY");
        assertRefused(database, namedKey, "primary key");

        for (String defaultValue : new String[] {"0", "", " CURRENT_TIMESTAMP"}) {
            ColumnDescription withDefault = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
            withDefault.setDefaultValue(defaultValue);
            assertRefused(database, withDefault, "default");
        }
        ColumnDescription temporalDefault = column("seen", Types.TIMESTAMP, ColumnMetaData.TYPE_TIMESTAMP, 6, 0);
        temporalDefault.setDefaultValue("CURRENT_TIMESTAMP");
        assertRefused(database, temporalDefault, "default");
        ColumnDescription evidencedDefault = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        evidence(evidencedDefault, MetadataValue.value("'x'"), MetadataValue.value(true), MetadataValue.absent(), MetadataValue.absent());
        assertRefused(database, evidencedDefault, "default");
        ColumnDescription unsupportedDefault = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        evidence(unsupportedDefault, MetadataValue.unsupported(), MetadataValue.value(true), MetadataValue.absent(), MetadataValue.absent());
        assertRefused(database, unsupportedDefault, "default");
        ColumnDescription namedDefault = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        namedDefault.setFieldTypeName("int DEFAULT 0");
        assertRefused(database, namedDefault, "default");

        ColumnDescription increment = column("id", Types.BIGINT, ColumnMetaData.TYPE_INTEGER, 15, 0);
        increment.setAutoIncrement(true);
        assertRefused(database, increment, "identity");
        ColumnDescription evidencedIdentity = column("id", Types.BIGINT, ColumnMetaData.TYPE_INTEGER, 15, 0);
        evidence(evidencedIdentity, MetadataValue.absent(), MetadataValue.value(true), MetadataValue.value("AUTO_INCREMENT"), MetadataValue.absent());
        assertRefused(database, evidencedIdentity, "identity");
        ColumnDescription namedIdentity = column("id", Types.BIGINT, ColumnMetaData.TYPE_INTEGER, 15, 0);
        namedIdentity.setFieldTypeName("bigint auto_increment");
        assertRefused(database, namedIdentity, "identity");
        ColumnDescription sequence = column("id", Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0);
        sequence.setMetadataEvidence(new ColumnMetadataEvidence(MetadataProvenance.unknown(), MetadataValue.unknown(),
                MetadataValue.absent(), MetadataValue.value(true), MetadataValue.unknown(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.absent(), MetadataValue.absent(), MetadataValue.unknown(),
                MetadataValue.unknown(), MetadataValue.value(new ColumnSequenceMetadata(
                        ColumnSequenceMetadata.Kind.IDENTITY_BY_DEFAULT, "app", "seq_id", "integer",
                        1, 1, 1, Integer.MAX_VALUE, 1, false, MetadataValue.absent()))));
        assertRefused(database, sequence, "identity");

        ColumnDescription generated = column("v", Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0);
        evidence(generated, MetadataValue.absent(), MetadataValue.value(true), MetadataValue.absent(), MetadataValue.value("(`a` + 1)"));
        assertRefused(database, generated, "generated");
        ColumnDescription namedGenerated = column("v", Types.INTEGER, ColumnMetaData.TYPE_INTEGER, 9, 0);
        namedGenerated.setFieldTypeName("int generated always");
        assertRefused(database, namedGenerated, "generated");

        ColumnDescription otherProduct = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        otherProduct.setDbType(ProductTypeEnum.ORACLE);
        assertRefused(database, otherProduct, "another product");
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("app", "items", null));
        assertThrows(IllegalArgumentException.class,
                () -> OceanbaseAddColumn.sql(null, "app", "items", column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0)));
    }

    @Test
    void jdbcNullWordIsNotADefaultForTemporalOrPlainColumns() throws Exception {
        DatabaseOceanbaseImpl database = mysql();
        ColumnDescription plain = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        ColumnDescription wordNull = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        wordNull.setDefaultValue("null");
        ColumnDescription wordNullUpper = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        wordNullUpper.setDefaultValue("NULL");
        assertEquals(database.getAddColumnSql("app", "items", plain), database.getAddColumnSql("app", "items", wordNull));
        assertEquals(database.getAddColumnSql("app", "items", plain), database.getAddColumnSql("app", "items", wordNullUpper));

        ColumnDescription seen = column("seen", Types.TIMESTAMP, ColumnMetaData.TYPE_TIMESTAMP, 3, 0);
        ColumnDescription seenNull = column("seen", Types.TIMESTAMP, ColumnMetaData.TYPE_TIMESTAMP, 3, 0);
        seenNull.setDefaultValue("NULL");
        String sql = database.getAddColumnSql("app", "items", seenNull);
        assertEquals(database.getAddColumnSql("app", "items", seen), sql);
        assertEquals("ALTER TABLE `app`.`items` ADD COLUMN `seen` DATETIME(3)", sql);
        assertFalse(sql.contains("DEFAULT"));
    }

    @Test
    void unprovedTemporalShapeIsNotEmittedAsAddColumn() throws Exception {
        DatabaseOceanbaseImpl database = mysql();
        ColumnDescription zone = column("seen", Types.TIMESTAMP_WITH_TIMEZONE, ColumnMetaData.TYPE_TIMESTAMP, 6, 0);
        var zoneFailure = assertThrows(UnsupportedOperationException.class,
                () -> database.getAddColumnSql("app", "items", zone));
        assertFalse(zoneFailure.getMessage().contains("ALTER"), zoneFailure.getMessage());

        ColumnDescription wide = column("seen", Types.TIMESTAMP, ColumnMetaData.TYPE_TIMESTAMP, 7, 0);
        var wideFailure = assertThrows(IllegalArgumentException.class,
                () -> database.getAddColumnSql("app", "items", wide));
        assertTrue(wideFailure.getMessage().contains("0 and 6"), wideFailure.getMessage());
        assertFalse(wideFailure.getMessage().contains("ALTER"), wideFailure.getMessage());
    }

    @Test
    void physicalIdentifierRulesRejectNonOrdinaryNamesBeforeSqlIsEmitted() throws Exception {
        DatabaseOceanbaseImpl database = mysql();
        ColumnDescription column = column("note", Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0);
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql(null, "items", column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("", "items", column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("app", "items",
                column("n".repeat(64), Types.VARCHAR, ColumnMetaData.TYPE_STRING, 20, 0)));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("Gov.Db", "items", column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("app", "A`B", column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("app", "bad-name", column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("a\0b", "items", column));
        assertThrows(IllegalArgumentException.class, () -> database.getAddColumnSql("1app", "items", column));
    }

    private static void assertAdd(DatabaseOceanbaseImpl database, ColumnDescription column, String expected) {
        String definition = database.getFieldDefinition(column.getMetaData(), null, false, false, false);
        String sql = database.getAddColumnSql("app", "items", column);
        assertEquals("ALTER TABLE " + quote("app") + "." + quote("items") + " ADD COLUMN" + definition, sql);
        assertEquals(expected, sql);
        assertEquals(sql, OceanbaseAddColumn.sql(database, "app", "items", column));
    }

    private static void assertRefused(DatabaseOceanbaseImpl database, ColumnDescription column, String reason) {
        var failure = assertThrows(SQLFeatureNotSupportedException.class,
                () -> database.getAddColumnSql("app", "items", column));
        assertEquals("0A000", failure.getSQLState());
        assertTrue(failure.getMessage().contains(reason), failure.getMessage());
        assertFalse(failure.getMessage().contains("ALTER"), failure.getMessage());
        var direct = assertThrows(SQLFeatureNotSupportedException.class,
                () -> OceanbaseAddColumn.sql(database, "app", "items", column));
        assertEquals(failure.getMessage(), direct.getMessage());
    }

    private static DatabaseOceanbaseImpl mysql() throws Exception {
        DatabaseOceanbaseImpl database = new DatabaseOceanbaseImpl();
        database.prepareDialect(mode("MYSQL"));
        return database;
    }

    private static Connection mode(String compatibility) throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getString(2)).thenReturn(compatibility);
        return connection;
    }

    private static String quote(String name) {
        return RestrictedSelectGuard.quotePhysicalIdentifier(ProductTypeEnum.OCEANBASE, name);
    }

    private static ColumnDescription column(String name, int sqlType, int valueType, int length, int precision) {
        ColumnDescription description = new ColumnDescription();
        description.setFieldName(name);
        description.setDbType(ProductTypeEnum.OCEANBASE);
        description.setFieldType(sqlType);
        description.setValType(valueType);
        description.setLength(length);
        description.setPrecision(precision);
        description.setNullable(true);
        return description;
    }

    private static void evidence(ColumnDescription column, MetadataValue<String> defaultExpression,
            MetadataValue<Boolean> nullable, MetadataValue<String> identity, MetadataValue<String> generated) {
        column.setMetadataEvidence(new ColumnMetadataEvidence(MetadataProvenance.unknown(), MetadataValue.unknown(),
                defaultExpression, nullable, MetadataValue.unknown(), MetadataValue.unknown(), MetadataValue.unknown(),
                identity, generated, MetadataValue.unknown(), MetadataValue.unknown()));
    }
}

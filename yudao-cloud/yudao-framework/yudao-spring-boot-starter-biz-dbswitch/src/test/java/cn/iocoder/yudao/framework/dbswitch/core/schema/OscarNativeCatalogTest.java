package cn.iocoder.yudao.framework.dbswitch.core.schema;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.MetadataValue;
import cn.iocoder.yudao.framework.dbswitch.core.schema.nativecatalog.OscarNativeCatalog;
import cn.iocoder.yudao.framework.dbswitch.core.service.impl.MetaDataByJdbcServiceImpl;
import cn.iocoder.yudao.framework.dbswitch.driver.VerifiedDriverTestHandle;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import cn.iocoder.yudao.framework.dbswitch.spi.WriteMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Self-owned ShenTong V8.0.251217 schemas. Registration is tested through the public registry. */
@Timeout(30)
@EnabledIfSystemProperty(named = "datahub.test.oscar.url", matches = "jdbc:oscar://127[.]0[.]0[.]1:[0-9]+/OSRDB")
@EnabledIfSystemProperty(named = "datahub.test.oscar.driver", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DATAHUB_TEST_OSCAR_PASSWORD", matches = ".+")
class OscarNativeCatalogTest {
    @Test void droppedMiddleColumnKeepsVisiblePhysicalOrderAndCompositeKeyOrder() throws Exception {
        try (var fixture = fixture(); var connection = fixture.source.getConnection()) {
            execute(connection, "CREATE TABLE " + fixture.relation("DROPPED")
                    + "(A INTEGER,B VARCHAR(40),C BIGINT,D NUMERIC(12,3),PRIMARY KEY(C,A))");
            var beforeDrop = NativeSchemaReader.read(connection, ProductTypeEnum.OSCAR, fixture.schema, "DROPPED", 5);
            assertEquals(List.of("A", "B", "C", "D"),
                    beforeDrop.columns().stream().map(column -> column.getFieldName()).toList());
            assertEquals("INT8", beforeDrop.columns().get(2).getMetadataEvidence().nativeType().value());
            execute(connection, "ALTER TABLE " + fixture.relation("DROPPED") + " DROP COLUMN B");
            var schema = NativeSchemaReader.read(connection, ProductTypeEnum.OSCAR, fixture.schema, "DROPPED", 5);
            assertEquals(List.of("A", "C", "D"), schema.columns().stream().map(column -> column.getFieldName()).toList());
            assertEquals(List.of("C", "A"), schema.primaryKeys());
            assertEquals(Types.BIGINT, schema.columns().get(1).getFieldType());
            assertEquals("bigint", schema.columns().get(1).getFieldTypeName());
            assertEquals("INT8", schema.columns().get(1).getMetadataEvidence().nativeType().value());
            assertEquals(12, schema.columns().getLast().getMetadataEvidence().numericPrecision().value());
            assertEquals(3, schema.columns().getLast().getMetadataEvidence().numericScale().value());
        }
    }

    @Test void publicSchemaFacadeReadsVisibleOrderedColumnsSqlDefaultsAndCompositePrimaryKey() throws Exception {
        try (var fixture = fixture()) {
            try (var connection = fixture.source.getConnection()) {
                execute(connection, "CREATE TABLE " + fixture.relation("SOURCE")
                        + "(Z INTEGER,A NUMERIC(12,3) DEFAULT 1.250,NOTE VARCHAR(40) DEFAULT 'left;''quote',"
                        + "D DATE,B BLOB,PRIMARY KEY(A,Z))");
                execute(connection, "INSERT INTO " + fixture.relation("SOURCE") + "(Z) VALUES(1)");
                try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                        "SELECT * FROM " + fixture.relation("SOURCE") + " WHERE 1=0")) {
                    assertFalse(rows.next());
                    assertEquals(5, rows.getMetaData().getColumnCount());
                }
            }
            var products = DbSwitchProductRegistry.discover();
            var facade = new MetaDataByJdbcServiceImpl(ProductTypeEnum.OSCAR, products,
                    (url, user, password) -> fixture.source.getConnection());
            var schema = facade.queryTableSchema(url(), user(), password(), fixture.schema, "SOURCE", 5000);
            assertEquals(List.of("Z", "A", "NOTE", "D", "B"),
                    schema.columns().stream().map(column -> column.getFieldName()).toList());
            assertEquals(List.of("A", "Z"), schema.primaryKeys());
            assertEquals(OscarNativeCatalog.METHOD, schema.provenance().method());
            assertTrue(schema.provenance().nativeProduct(ProductTypeEnum.OSCAR));
            assertEquals(Boolean.TRUE, schema.existence().value());
            assertEquals(MetadataValue.State.UNKNOWN, schema.additionalObjects().state());
            var decimal = schema.columns().get(1).getMetadataEvidence();
            assertEquals(12, decimal.numericPrecision().value());
            assertEquals(3, decimal.numericScale().value());
            assertEquals("1.25", decimal.defaultExpression().value());
            assertEquals(Boolean.FALSE, decimal.nullable().value());
            assertEquals("'left;''quote'", schema.columns().get(2).getMetadataEvidence().defaultExpression().value());
            for (var column : schema.columns()) {
                var facts = column.getMetadataEvidence();
                assertEquals(MetadataValue.State.UNKNOWN, facts.identity().state());
                assertEquals(MetadataValue.State.UNKNOWN, facts.generatedExpression().state());
                assertEquals(MetadataValue.State.UNKNOWN, facts.charset().state());
                assertEquals(MetadataValue.State.UNKNOWN, facts.collation().state());
                assertEquals(MetadataValue.State.UNKNOWN, facts.sequence().state());
            }
            var missing = assertThrows(IllegalStateException.class, () -> facade.queryTableSchema(
                    url(), user(), password(), fixture.schema, "MISSING", 5000));
            assertInstanceOf(SQLException.class, missing.getCause());
            assertThrows(IllegalStateException.class, () -> facade.queryTableSchema(
                    url(), user(), password(), fixture.schema + "X", "SOURCE", 5000));
            var basic = facade.queryTableColumnsAndKeys(url(), user(), password(), fixture.schema, "SOURCE");
            assertEquals(5, basic.columns().size());
            assertEquals(List.of("A", "Z"), basic.primaryKeys());
        }
    }

    @Test void exactPhysicalCaseAndLegacyUnknownContractDoNotSwitchObjectsOrOwnTheTransaction() throws Exception {
        try (var fixture = fixture(); var connection = fixture.source.getConnection()) {
            execute(connection, "CREATE TABLE " + fixture.relation("same") + "(LOW_COL INTEGER)");
            execute(connection, "CREATE TABLE " + fixture.relation("SAME") + "(UP_COL INTEGER)");
            execute(connection, "CREATE TABLE " + fixture.relation("name space") + "(LEGACY_COL INTEGER)");
            assertEquals("LOW_COL", NativeSchemaReader.read(connection, ProductTypeEnum.OSCAR,
                    fixture.schema, "same", 5).columns().getFirst().getFieldName());
            assertEquals("UP_COL", NativeSchemaReader.read(connection, ProductTypeEnum.OSCAR,
                    fixture.schema, "SAME", 5).columns().getFirst().getFieldName());
            assertThrows(SQLFeatureNotSupportedException.class,
                    () -> NativeSchemaReader.read(connection, ProductTypeEnum.OSCAR, fixture.schema, "name space", 5));
            var facade = new MetaDataByJdbcServiceImpl(ProductTypeEnum.OSCAR, DbSwitchProductRegistry.discover(),
                    (url, user, password) -> fixture.source.getConnection());
            var legacy = facade.queryTableSchema(url(), user(), password(), fixture.schema, "name space", 5000);
            assertEquals("UNKNOWN", legacy.provenance().method());
            assertEquals("LEGACY_COL", legacy.columns().getFirst().getFieldName());
            int isolation = connection.getTransactionIsolation();
            String currentSchema = currentSchema(connection);
            connection.setAutoCommit(false);
            execute(connection, "INSERT INTO " + fixture.relation("SAME") + " VALUES(7)");
            try {
                NativeSchemaReader.read(connection, ProductTypeEnum.OSCAR, fixture.schema, "SAME", 5);
                assertFalse(connection.getAutoCommit());
                assertEquals(isolation, connection.getTransactionIsolation());
                assertEquals(currentSchema, currentSchema(connection));
            } finally { connection.rollback(); connection.setAutoCommit(true); }
            assertEquals(0, count(connection, fixture.relation("SAME")));
        }
    }

    @Test void registryDdlRestoresFiveBasicTypesAndOrdinaryWriterReaderAndSyncExecuteNatively() throws Exception {
        try (var fixture = fixture(); var connection = fixture.source.getConnection()) {
            var products = DbSwitchProductRegistry.discover();
            var metadata = products.metadata(ProductTypeEnum.OSCAR);
            execute(connection, "CREATE TABLE " + fixture.relation("DDL_SOURCE")
                    + "(ID INTEGER,AMOUNT NUMERIC(12,3),NOTE VARCHAR(40),D DATE,B BLOB)");
            String ddl = metadata.getTableDDL(connection, fixture.schema, "DDL_SOURCE");
            assertNotNull(ddl);
            execute(connection, ddl.replace("DDL_SOURCE", "DDL_CLONE"));
            assertEquals(metadata.queryTableColumnMetaOnly(connection, fixture.schema, "DDL_SOURCE")
                            .stream().map(column -> column.getFieldType()).toList(),
                    metadata.queryTableColumnMetaOnly(connection, fixture.schema, "DDL_CLONE")
                            .stream().map(column -> column.getFieldType()).toList());
            execute(connection, "CREATE TABLE " + fixture.relation("DATA")
                    + "(ID INTEGER PRIMARY KEY,NOTE VARCHAR(40),AMOUNT NUMERIC(12,3))");
            var fields = List.of("ID", "NOTE", "AMOUNT");
            var writer = products.writer(fixture.source, ProductTypeEnum.OSCAR, WriteMode.INSERT);
            writer.prepareWrite(fixture.schema, "DATA", fields);
            assertEquals(2, writer.write(fields, List.<Object[]>of(new Object[]{1, "first", new BigDecimal("1.125")},
                    new Object[]{2, "second", new BigDecimal("2.250")})));
            var reader = products.reader(fixture.source, ProductTypeEnum.OSCAR);
            try (var result = reader.queryTableData(fixture.schema, "DATA", fields, List.of("ID"))) {
                assertTrue(result.getResultset().next());
                assertEquals("first", result.getResultset().getString("NOTE"));
                assertEquals(0, new BigDecimal("1.125").compareTo(result.getResultset().getBigDecimal("AMOUNT")));
                assertTrue(result.getResultset().next());
                assertEquals(2, result.getResultset().getInt("ID"));
                assertFalse(result.getResultset().next());
            }
            var sync = products.synchronizer(fixture.source, ProductTypeEnum.OSCAR);
            sync.prepare(fixture.schema, "DATA", fields, List.of("ID"));
            assertEquals(1, sync.executeUpdate(List.<Object[]>of(new Object[]{1, "updated", new BigDecimal("3.375")})));
            assertEquals(1, sync.executeDelete(List.<Object[]>of(new Object[]{2, "second", new BigDecimal("2.250")})));
            assertEquals(1, sync.executeInsert(List.<Object[]>of(new Object[]{3, "third", new BigDecimal("4.500")})));
            assertEquals(2, count(connection, fixture.relation("DATA")));
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT NOTE,AMOUNT FROM " + fixture.relation("DATA") + " WHERE ID=1")) {
                assertTrue(rows.next());
                assertEquals("updated", rows.getString(1));
                assertEquals(0, new BigDecimal("3.375").compareTo(rows.getBigDecimal(2)));
            }
            var upsert = products.upsertWriter(fixture.source, ProductTypeEnum.OSCAR);
            upsert.prepareUpsert(fixture.schema, "DATA", fields, List.of("ID"));
            assertEquals(2, upsert.write(fields, List.<Object[]>of(new Object[]{1, "replayed", new BigDecimal("6.125")},
                    new Object[]{4, "inserted", new BigDecimal("7.250")})));
            assertEquals(3, count(connection, fixture.relation("DATA")));
        }
    }

    private Fixture fixture() throws Exception { return new Fixture(); }
    private static String url() { return System.getProperty("datahub.test.oscar.url"); }
    private static String user() { return System.getenv("DATAHUB_TEST_OSCAR_USER"); }
    private static String password() { return System.getenv("DATAHUB_TEST_OSCAR_PASSWORD"); }
    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.setQueryTimeout(5); statement.execute(sql); }
    }
    private static long count(Connection connection, String relation) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM " + relation)) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }
    private static String currentSchema(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM DUAL")) { assertTrue(rows.next()); return rows.getString(1); }
    }
    private static final class Fixture implements AutoCloseable {
        final String schema = "AUD80_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        final VerifiedDriverTestHandle driver = new VerifiedDriverTestHandle(
                Path.of(System.getProperty("datahub.test.oscar.driver")), ProductTypeEnum.OSCAR);
        final DataSource source;
        Fixture() throws Exception {
            source = driver.dataSource(url(), "com.oscar.Driver", user(), password(), new Properties());
            try (var connection = source.getConnection()) { execute(connection, "CREATE SCHEMA " + schema); }
        }
        String relation(String table) { return "\"" + schema + "\".\"" + table + "\""; }
        @Override public void close() throws Exception {
            try (var connection = source.getConnection()) { execute(connection, "DROP SCHEMA " + schema + " CASCADE"); }
            finally { driver.close(); }
        }
    }
}

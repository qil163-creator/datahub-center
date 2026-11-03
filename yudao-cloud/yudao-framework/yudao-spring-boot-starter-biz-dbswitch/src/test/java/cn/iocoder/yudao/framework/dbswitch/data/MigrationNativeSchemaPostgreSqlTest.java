package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader;
import cn.iocoder.yudao.framework.dbswitch.core.schema.SchemaFingerprint;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(80)
@EnabledIfSystemProperty(named = "datahub.test.postgres.url", matches = "jdbc:postgresql://127[.]0[.]0[.]1:.*")
class MigrationNativeSchemaPostgreSqlTest {
    private final String sourceSchema = "migration_src_" + UUID.randomUUID().toString().replace("-", "");
    private final String targetSchema = "migration_dst_" + UUID.randomUUID().toString().replace("-", "");

    @BeforeEach void setup() throws Exception {
        try (var connection = open("postgres"); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + sourceSchema); statement.execute("CREATE SCHEMA " + targetSchema);
        }
    }
    @AfterEach void cleanup() throws Exception {
        try (var connection = open("postgres"); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + sourceSchema + " CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS " + targetSchema + " CASCADE");
        }
    }

    @Test void actualOnlyCreatePreservesNativeDefaultsIdentityArraysAndDecimal() throws Exception {
        try (var connection = open("postgres"); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + sourceSchema + ".source (id bigint GENERATED ALWAYS AS IDENTITY "
                    + "(START WITH 5 INCREMENT BY 3), code integer NOT NULL, note text DEFAULT 'left;  right'::text, "
                    + "amount numeric(100,30), created timestamp(3) with time zone DEFAULT now(), numbers integer[], "
                    + "document jsonb, PRIMARY KEY(code,id))");
            var request = request(ProductTypeEnum.POSTGRESQL, targetSchema);
            request.getTarget().setOnlyCreate(true); request.getTarget().setSyncDefaultVal(true);
            run(request);
            var before = NativeSchemaReader.read(connection, ProductTypeEnum.POSTGRESQL, sourceSchema, "source", 10);
            var after = NativeSchemaReader.read(connection, ProductTypeEnum.POSTGRESQL, targetSchema, "copy", 10);
            assertEquals(SchemaFingerprint.of(before), SchemaFingerprint.of(after));
            try (var values = statement.executeQuery("INSERT INTO " + targetSchema
                    + ".copy(code,numbers) VALUES (1,ARRAY[1,NULL,3]) RETURNING id,note,numbers,created")) {
                assertTrue(values.next()); assertEquals(5L, values.getLong(1));
                assertEquals("left;  right", values.getString(2));
                var array = values.getArray(3);
                try { assertArrayEquals(new Integer[]{1, null, 3}, (Object[]) array.getArray()); }
                finally { array.free(); }
                assertNotNull(values.getObject(4));
            }
        }
    }

    @Test void explicitDefaultOptOutDoesNotLeaveNativeDefaultEvidenceActive() throws Exception {
        try (var connection = open("postgres"); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + sourceSchema + ".source(id int PRIMARY KEY, note text DEFAULT 'source-default')");
            statement.execute("INSERT INTO " + sourceSchema + ".source VALUES (1,'value')");
            var request = request(ProductTypeEnum.POSTGRESQL, targetSchema); request.getTarget().setSyncDefaultVal(false);
            run(request);
            try (var values = statement.executeQuery("INSERT INTO " + targetSchema + ".copy(id) VALUES(2) RETURNING note")) {
                assertTrue(values.next()); assertNull(values.getString(1));
            }
            try (var values = statement.executeQuery("SELECT note FROM " + targetSchema + ".copy WHERE id=1")) {
                assertTrue(values.next()); assertEquals("value", values.getString(1));
            }
        }
    }

    @Test void actualMigrationKeepsNullEmptyAndNonNullIntegerArrayValues() throws Exception {
        try (var connection = open("postgres"); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + sourceSchema + ".source(id int PRIMARY KEY, numbers int[])");
            statement.execute("INSERT INTO " + sourceSchema + ".source VALUES(1,NULL),(2,ARRAY[]::int[]),(3,ARRAY[1,NULL,3])");
            run(request(ProductTypeEnum.POSTGRESQL, targetSchema));
            try (var values = statement.executeQuery("SELECT numbers FROM " + targetSchema + ".copy ORDER BY id")) {
                assertTrue(values.next()); assertNull(values.getArray(1));
                assertTrue(values.next()); var empty = values.getArray(1);
                try { assertArrayEquals(new Integer[]{}, (Object[]) empty.getArray()); } finally { empty.free(); }
                assertTrue(values.next()); var populated = values.getArray(1);
                try { assertArrayEquals(new Integer[]{1, null, 3}, (Object[]) populated.getArray()); } finally { populated.free(); }
                assertFalse(values.next());
            }
        }
    }

    @Test void identityDataAutoCreateRejectsBeforeDropOrCreateAndPreservesBothSides() throws Exception {
        try (var connection = open("postgres"); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + sourceSchema + ".source(id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, note text)");
            statement.execute("INSERT INTO " + sourceSchema + ".source(note) VALUES('source-value')");
            statement.execute("CREATE TABLE " + targetSchema + ".copy(id bigint PRIMARY KEY, note text)");
            statement.execute("INSERT INTO " + targetSchema + ".copy VALUES(99,'keep-target')");
            var request = request(ProductTypeEnum.POSTGRESQL, targetSchema); request.getTarget().setTargetDrop(true);
            assertThrows(IllegalStateException.class, () -> run(request));
            try (var values = statement.executeQuery("SELECT id,note FROM " + targetSchema + ".copy")) {
                assertTrue(values.next()); assertEquals(99L, values.getLong(1)); assertEquals("keep-target", values.getString(2));
                assertFalse(values.next());
            }
            statement.execute("DROP TABLE " + targetSchema + ".copy"); request.getTarget().setTargetDrop(false);
            assertThrows(IllegalStateException.class, () -> run(request));
            try (var values = statement.executeQuery("SELECT to_regclass('" + targetSchema + ".copy')")) {
                assertTrue(values.next()); assertNull(values.getObject(1));
            }
            try (var values = statement.executeQuery("SELECT id,note FROM " + sourceSchema + ".source")) {
                assertTrue(values.next()); assertEquals(1L, values.getLong(1)); assertEquals("source-value", values.getString(2));
                assertFalse(values.next());
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "datahub.test.mysql.url", matches = "jdbc:mysql://127[.]0[.]0[.]1:.*")
    void crossProductAutoCreateUsesTargetDialectAndRejectsUnmappedNativeBeforeDrop() throws Exception {
        String mysqlSchema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        try (var source = open("postgres"); var pg = source.createStatement();
             var target = open("mysql"); var mysql = target.createStatement()) {
            mysql.execute("CREATE DATABASE " + mysqlSchema);
            try {
                pg.execute("CREATE TABLE " + sourceSchema + ".source(id int PRIMARY KEY, amount numeric(20,4), note varchar(40), whole numeric(10,0))");
                pg.execute("INSERT INTO " + sourceSchema + ".source VALUES(1,1234567890123456.1234,'中文',9999999999),(2,NULL,'',NULL)");
                var request = request(ProductTypeEnum.MYSQL, mysqlSchema);
                run(request);
                try (var values = mysql.executeQuery("SELECT id,amount,note,whole FROM " + mysqlSchema + ".copy ORDER BY id")) {
                    assertTrue(values.next()); assertEquals(1, values.getInt(1));
                    assertEquals(new java.math.BigDecimal("1234567890123456.1234"), values.getBigDecimal(2));
                    assertEquals("中文", values.getString(3));
                    assertEquals(new java.math.BigDecimal("9999999999"), values.getBigDecimal(4));
                    assertTrue(values.next());
                    assertNull(values.getBigDecimal(2)); assertEquals("", values.getString(3)); assertFalse(values.next());
                }
                pg.execute("DROP TABLE " + sourceSchema + ".source");
                pg.execute("CREATE TABLE " + sourceSchema + ".source(id int PRIMARY KEY, numbers int[])");
                request.getTarget().setTargetDrop(true);
                assertThrows(IllegalStateException.class, () -> run(request));
                try (var values = mysql.executeQuery("SELECT count(*) FROM " + mysqlSchema + ".copy")) {
                    assertTrue(values.next()); assertEquals(2, values.getInt(1), "Unsupported native mapping must fail before DROP");
                }
            } finally { mysql.execute("DROP DATABASE IF EXISTS " + mysqlSchema); }
        }
    }

    private MigrationRequest request(ProductTypeEnum targetType, String schema) {
        var request = new MigrationRequest();
        var source = new SourceDataSourceProperties(); source.setSourceProductType(ProductTypeEnum.POSTGRESQL);
        source.setUrl(System.getProperty("datahub.test.postgres.url")); source.setUsername(user("postgres"));
        source.setPassword(password("postgres")); source.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName());
        source.setSourceSchema(sourceSchema); source.setSourceIncludes("source"); source.setPoolSize(1); source.setFetchSize(5);
        source.setRegexTableMapper(List.of(new PatternMapper("^source$", "copy"))); request.getSource().add(source);
        var target = request.getTarget(); String fixture = targetType == ProductTypeEnum.POSTGRESQL ? "postgres" : "mysql";
        target.setTargetProductType(targetType); target.setUrl(System.getProperty("datahub.test." + fixture + ".url"));
        target.setUsername(user(fixture)); target.setPassword(password(fixture)); target.setDriverClassName(targetType.getDriveClassName());
        target.setTargetSchema(schema); target.setTargetTable("copy"); target.setPoolSize(1); target.setWriterEngineInsert(true);
        return request;
    }
    private static void run(MigrationRequest request) throws Exception {
        try (var runtime = DbSwitchRuntime.standalone()) {
            var result = new MigrationService(request, ignored -> { }, null, runtime).run();
            if (!Boolean.TRUE.equals(result.getIfAllSuccess().get())) throw new IllegalStateException("Migration did not succeed");
        }
    }
    private static Connection open(String fixture) throws Exception {
        return DriverManager.getConnection(System.getProperty("datahub.test." + fixture + ".url"), user(fixture), password(fixture));
    }
    private static String user(String fixture) {
        return System.getProperty("datahub.test." + fixture + ".user", fixture.equals("mysql") ? "root" : "dataforge_test");
    }
    private static String password(String fixture) { return System.getProperty("datahub.test." + fixture + ".password", ""); }
}

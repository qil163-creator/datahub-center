package cn.iocoder.yudao.framework.dbswitch.core.database.impl;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnMetaData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KingbaseES V008R006C009B0014 oracle-mode metadata/DDL certification: JDBC metadata reading,
 * catalog DDL/view reconstruction and the generic column definition renderer execute on the real server.
 */
@EnabledIfSystemProperty(named = "datahub.test.kingbase.url", matches = "jdbc:kingbase8://127[.]0[.]0[.]1:[0-9]+/test")
@EnabledIfEnvironmentVariable(named = "DATAHUB_KINGBASE_TEST_PASSWORD", matches = ".+")
class KingbaseMetadataKingbaseTest {
    private Connection connection;
    private String schema;

    @BeforeEach
    void create() throws Exception {
        schema = "q8k_" + UUID.randomUUID().toString().replace("-", "");
        Class.forName("com.kingbase8.Driver");
        connection = DriverManager.getConnection(System.getProperty("datahub.test.kingbase.url"),
                "system", System.getenv("DATAHUB_KINGBASE_TEST_PASSWORD"));
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(30);
            sql.execute("CREATE SCHEMA " + schema);
        }
    }

    @AfterEach
    void drop() throws Exception {
        if (connection == null) return;
        try (var owned = connection; var sql = owned.createStatement()) {
            sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void jdbcMetadataReadsFieldsAndPrimaryKeysOfAnOrdinaryTable() throws Exception {
        var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.KINGBASE);
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(30);
            sql.execute("CREATE TABLE " + schema + ".sample(id bigint, amount numeric(20,2), note varchar(40), ts timestamp(6), PRIMARY KEY(id))");
            sql.execute("INSERT INTO " + schema + ".sample VALUES(1,2.50,'ok','2026-01-02 03:04:05.123456')");
        }
        var fields = database.queryTableColumnName(connection, schema, "sample");
        assertEquals(java.util.Set.of("id", "amount", "note", "ts"), new java.util.HashSet<>(fields));
        assertEquals(4, database.queryTableColumnMeta(connection, schema, "sample").size());
        assertEquals(List.of("id"), database.queryTablePrimaryKeys(connection, schema, "sample"));
        var preview = database.querySelectSqlColumnMeta(connection, "SELECT * FROM " + schema + ".sample");
        assertEquals(4, preview.size());
        String ddl = database.getTableDDL(connection, schema, "sample");
        assertNotNull(ddl);
        // The catalog-reconstructed DDL must actually recreate the relation on the real server.
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(30);
            sql.execute("DROP TABLE " + schema + ".sample");
            sql.execute(ddl);
            try (var rows = sql.executeQuery("SELECT count(*) FROM " + schema + ".sample")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getLong(1));
            }
        }
    }

    @Test
    void catalogViewDdlIsReconstructed() throws Exception {
        var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.KINGBASE);
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(30);
            sql.execute("CREATE TABLE " + schema + ".base(id bigint)");
            sql.execute("CREATE VIEW " + schema + ".v AS SELECT id FROM " + schema + ".base");
        }
        String ddl = database.getViewDDL(connection, schema, "v");
        assertNotNull(ddl);
        assertTrue(ddl.contains("base"), ddl);
    }

    @Test
    void columnDefinitionRendererProducesExecutableDdlOnTheRealServer() throws Exception {
        var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.KINGBASE);
        var definitions = new StringBuilder();
        for (var definition : List.of(
                database.getFieldDefinition(column("id", ColumnMetaData.TYPE_BIGNUMBER, 19, 0, false, null), List.of("id"), true, false, false),
                database.getFieldDefinition(column("amount", ColumnMetaData.TYPE_NUMBER, 20, 2, true, null), List.of("id"), false, false, false),
                database.getFieldDefinition(column("note", ColumnMetaData.TYPE_STRING, 40, 0, true, null), List.of("id"), false, false, false),
                database.getFieldDefinition(column("stamp", ColumnMetaData.TYPE_TIMESTAMP, 6, 0, true, "CURRENT_TIMESTAMP"), List.of("id"), false, false, false))) {
            if (definitions.length() > 0) definitions.append(',');
            definitions.append(definition);
        }
        assertFalse(definitions.toString().contains("DEFAULT DEFAULT"), definitions.toString());
        try (var sql = connection.createStatement()) {
            sql.setQueryTimeout(30);
            sql.execute("CREATE TABLE " + schema + ".rendered(" + definitions + ",PRIMARY KEY (id))");
            sql.execute("INSERT INTO " + schema + ".rendered(id, amount, note) VALUES(1,2.50,'ok')");
            try (var rows = sql.executeQuery("SELECT stamp IS NOT NULL FROM " + schema + ".rendered")) {
                assertTrue(rows.next());
                assertTrue(rows.getBoolean(1));
            }
        }
    }

    private static ColumnMetaData column(String name, int type, int length, int precision, boolean nullable, String defaultValue) {
        var description = new cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription();
        description.setFieldName(name);
        description.setValType(type);
        description.setLength(length);
        description.setPrecision(precision);
        description.setDisplaySize(length);
        description.setSigned(true);
        description.setNullable(nullable);
        description.setDefaultValue(defaultValue);
        return new ColumnMetaData(description);
    }
}

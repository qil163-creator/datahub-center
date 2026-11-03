package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.schema.NativeSchemaReader;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.LossyColumnAcceptance;
import cn.iocoder.yudao.framework.dbswitch.core.schema.mapping.TypeFidelity;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
@EnabledIfSystemProperty(named="datahub.test.mysql.url", matches="jdbc:mysql://127[.]0[.]0[.]1:.*")
@EnabledIfSystemProperty(named="datahub.test.postgres.url", matches="jdbc:postgresql://127[.]0[.]0[.]1:.*")
class MigrationMysqlPostgreSqlNumericTest {
    private final String schema = "numeric_transfer_" + UUID.randomUUID().toString().replace("-", "");
    private Connection mysql, postgres;
    private DbSwitchRuntime runtime;

    @BeforeEach void setup() throws Exception {
        mysql = DriverManager.getConnection(url("mysql"), user("mysql"), password("mysql"));
        postgres = DriverManager.getConnection(url("postgres"), user("postgres"), password("postgres"));
        runtime = DbSwitchRuntime.standalone();
        sql(mysql, "CREATE DATABASE " + schema); sql(postgres, "CREATE SCHEMA " + schema);
    }
    @AfterEach void cleanup() throws Exception {
        if (runtime != null) runtime.close();
        try {
            if (mysql != null) try (var owned = mysql) { sql(owned, "DROP DATABASE IF EXISTS " + schema); }
        } finally {
            if (postgres != null) try (var owned = postgres) { sql(owned, "DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
        }
    }

    @ParameterizedTest @ValueSource(booleans={true, false})
    void actualInsertAndCopyPreserveUnsignedMaximumDecimalBoundsAndCompositePrimaryKey(boolean insert) throws Exception {
        sql(mysql, "CREATE TABLE " + schema + ".source(s_tiny tinyint,u_tiny tinyint unsigned,s_small smallint,u_small smallint unsigned,"
                + "s_medium mediumint,u_medium mediumint unsigned,s_int int,u_int int unsigned,s_big bigint,u_big bigint unsigned,"
                + "dec65 decimal(65,30),dec20 decimal(20,0),u_dec decimal(12,2) unsigned,PRIMARY KEY(u_big,s_big)) ENGINE=InnoDB");
        String maximumDecimal = "9".repeat(35) + "." + "9".repeat(30);
        String[][] values = {
                {"-128","0","-32768","0","-8388608","0","-2147483648","0","-9223372036854775808","0",
                        "-" + maximumDecimal,"-99999999999999999999","0"},
                {null,null,null,null,null,null,null,null,"0","12345678901234567890",null,"9007199254740993",null},
                {"127","255","32767","65535","8388607","16777215","2147483647","4294967295","9223372036854775807",
                        "18446744073709551615",maximumDecimal,"99999999999999999999","9999999999.99"}
        };
        for (String[] row : values) sql(mysql, "INSERT INTO " + schema + ".source VALUES ("
                + String.join(",", Arrays.stream(row).map(v -> v == null ? "NULL" : v).toList()) + ")");
        try (var statement = mysql.createStatement(); var row = statement.executeQuery("SELECT u_big FROM " + schema + ".source ORDER BY u_big DESC LIMIT 1")) {
            assertTrue(row.next()); assertInstanceOf(BigInteger.class, row.getObject(1));
            assertEquals(new BigInteger("18446744073709551615"), row.getObject(1));
        }
        // Keep native metadata/DDL failures visible in the fixture without weakening runtime log sanitization.
        var nativeMetadata = NativeSchemaReader.read(mysql, ProductTypeEnum.MYSQL, schema, "source", 10);
        var typePlan = runtime.products().typeMappings().plan(ProductTypeEnum.POSTGRESQL, nativeMetadata.columns(),
                nativeMetadata.primaryKeys(), LossyColumnAcceptance.rejectAll());
        for (var column : typePlan.columns()) assertEquals(TypeFidelity.EXACT, column.fidelity(), column::describe);
        var medium = nativeMetadata.columns().stream().filter(c -> c.getFieldName().equals("u_medium")).findFirst().orElseThrow();
        assertEquals(7, medium.getMetadataEvidence().numericPrecision().value());
        assertEquals(8, medium.getPrecisionSize());
        assertDoesNotThrow(() -> GenerateSqlUtils.getDDLCreateTableSQL(runtime.products().metadata(ProductTypeEnum.POSTGRESQL),
                nativeMetadata.columns(), nativeMetadata.primaryKeys(), schema, "copied", false));
        var request = request(insert);
        var result = new MigrationService(request, ignored -> { }, null, runtime).run();
        assertTrue(result.getIfAllSuccess().get(), () -> result.getTableResultList().toString());
        assertEquals(3, result.getTotalRowCount().get());
        assertTargetCatalogPrimaryKeyOrder();
        var keys = runtime.jdbcMetadata(ProductTypeEnum.POSTGRESQL).queryTablePrimaryKeys(
                url("postgres"), user("postgres"), password("postgres"), schema, "copied");
        assertEquals(List.of("u_big", "s_big"), keys);
        try (var statement = postgres.createStatement(); var rows = statement.executeQuery("SELECT * FROM " + schema + ".copied ORDER BY s_big")) {
            for (String[] expected : values) {
                assertTrue(rows.next());
                for (int c = 0; c < expected.length; c++) {
                    BigDecimal actual = rows.getBigDecimal(c + 1);
                    if (expected[c] == null) assertNull(actual);
                    else assertEquals(0, new BigDecimal(expected[c]).compareTo(actual), "column " + (c + 1));
                }
            }
            assertFalse(rows.next());
        }
        try (var statement = postgres.prepareStatement("SELECT pg_catalog.format_type(a.atttypid,a.atttypmod) "
                + "FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class c ON c.oid=a.attrelid "
                + "JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname='copied' "
                + "AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum")) {
            statement.setString(1, schema);
            try (var rows = statement.executeQuery()) {
                for (String type : List.of("smallint","smallint","smallint","integer","integer","integer","integer","bigint","bigint",
                        "numeric(20,0)","numeric(65,30)","numeric(20,0)","numeric(12,2)")) {
                    assertTrue(rows.next()); assertEquals(type, rows.getString(1));
                }
                assertFalse(rows.next());
            }
        }
        assertRangeRejected("u_tiny=-1"); assertRangeRejected("s_tiny=128");
        assertRangeRejected("u_big=18446744073709551616"); assertRangeRejected("u_dec=-0.01");
    }

    @ParameterizedTest @ValueSource(strings={"default", "identity", "generated", "boolean-driver"})
    void unsupportedNativeSemanticsFailBeforeExistingTargetIsDropped(String kind) throws Exception {
        String ddl = switch (kind) {
            case "identity" -> "id int AUTO_INCREMENT PRIMARY KEY,value int";
            case "generated" -> "id int PRIMARY KEY,value int GENERATED ALWAYS AS(id+1) STORED";
            case "boolean-driver" -> "id int PRIMARY KEY,value tinyint(1)";
            default -> "id int PRIMARY KEY,value int DEFAULT 7";
        };
        sql(mysql, "CREATE TABLE " + schema + ".source(" + ddl + ") ENGINE=InnoDB");
        sql(postgres, "CREATE TABLE " + schema + ".copied(keep integer)");
        sql(postgres, "INSERT INTO " + schema + ".copied VALUES (77)");
        var request = request(true); request.getTarget().setTargetDrop(true); request.getTarget().setSyncDefaultVal(true);
        if (kind.equals("boolean-driver")) request.getSource().getFirst().setUrl(
                url("mysql") + (url("mysql").contains("?") ? "&" : "?") + "tinyInt1isBit=true&transformedBitIsBoolean=true");
        var result = new MigrationService(request, ignored -> { }, null, runtime).run();
        assertFalse(result.getIfAllSuccess().get());
        try (var statement = postgres.createStatement(); var rows = statement.executeQuery("SELECT keep FROM " + schema + ".copied")) {
            assertTrue(rows.next()); assertEquals(77, rows.getInt(1)); assertFalse(rows.next());
        }
    }

    private void assertRangeRejected(String assignment) {
        SQLException failure = assertThrows(SQLException.class, () -> sql(postgres, "UPDATE " + schema + ".copied SET " + assignment + " WHERE s_big=0"));
        assertEquals("23514", failure.getSQLState());
    }
    private void assertTargetCatalogPrimaryKeyOrder() throws SQLException {
        // Independent server evidence distinguishes generated DDL order from JDBC metadata ordering.
        String sql = "SELECT con.conkey::text,i.indkey::text,a.attname FROM pg_catalog.pg_constraint con "
                + "JOIN pg_catalog.pg_class t ON t.oid=con.conrelid JOIN pg_catalog.pg_namespace n ON n.oid=t.relnamespace "
                + "JOIN pg_catalog.pg_index i ON i.indexrelid=con.conindid "
                + "CROSS JOIN LATERAL unnest(con.conkey) WITH ORDINALITY k(attnum,ordinality) "
                + "JOIN pg_catalog.pg_attribute a ON a.attrelid=t.oid AND a.attnum=k.attnum "
                + "WHERE n.nspname=? AND t.relname='copied' AND con.contype='p' ORDER BY k.ordinality";
        try (var statement = postgres.prepareStatement(sql)) {
            statement.setString(1, schema);
            try (var rows = statement.executeQuery()) {
                for (String key : List.of("u_big", "s_big")) {
                    assertTrue(rows.next());
                    assertEquals("{10,9}", rows.getString(1));
                    assertEquals("10 9", rows.getString(2));
                    assertEquals(key, rows.getString(3));
                }
                assertFalse(rows.next());
            }
        }
    }
    private MigrationRequest request(boolean insert) {
        var request = new MigrationRequest(); var source = new SourceDataSourceProperties();
        source.setSourceProductType(ProductTypeEnum.MYSQL); source.setDriverClassName(ProductTypeEnum.MYSQL.getDriveClassName());
        source.setUrl(url("mysql")); source.setUsername(user("mysql")); source.setPassword(password("mysql")); source.setPoolSize(1);
        source.setSourceSchema(schema); source.setSourceIncludes("source"); source.setFetchSize(2);
        source.setRegexTableMapper(List.of(new PatternMapper("^source$", "copied"))); request.getSource().add(source);
        var target = request.getTarget(); target.setTargetProductType(ProductTypeEnum.POSTGRESQL);
        target.setDriverClassName(ProductTypeEnum.POSTGRESQL.getDriveClassName()); target.setPoolSize(1);
        target.setUrl(url("postgres")); target.setUsername(user("postgres")); target.setPassword(password("postgres"));
        target.setTargetSchema(schema); target.setWriterEngineInsert(insert); return request;
    }
    private static void sql(Connection connection, String sql) throws SQLException { try (var statement = connection.createStatement()) { statement.execute(sql); } }
    private static String url(String product) { return System.getProperty("datahub.test." + product + ".url"); }
    private static String user(String product) { return System.getProperty("datahub.test." + product + ".user", product.equals("mysql") ? "root" : "dataforge_test"); }
    private static String password(String product) { return System.getProperty("datahub.test." + product + ".password", ""); }
}

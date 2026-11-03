package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.common.util.OceanbaseUtils;
import cn.iocoder.yudao.framework.dbswitch.core.model.ColumnDescription;
import cn.iocoder.yudao.framework.dbswitch.core.util.GenerateSqlUtils;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.*;
import java.util.UUID;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "datahub.test.oceanbase.url", matches = "jdbc:oceanbase://127[.]0[.]0[.]1:[0-9]+/")
class OceanbaseMetadataOceanbaseTest {
    @ParameterizedTest
    @ValueSource(strings={"STRICT_ALL_TABLES","STRICT_ALL_TABLES,ANSI_QUOTES,NO_BACKSLASH_ESCAPES"})
    void delegatedCommentsRetainQuotedNamesAndExactTextUnderBothEscapeModes(String mode) throws Exception {
        Class.forName(ProductTypeEnum.OCEANBASE.getDriveClassName());
        String schema="ob_comments88_"+UUID.randomUUID().toString().replace("-","");
        String table="table`'; name",field="column`'; name";
        String tableText="中文 O'Reilly \"table\"\n'; DROP TABLE never_execute; --",columnText="字段 'value' \"quoted\" 中文";
        // The observer query binds physical names on the server: Connector/J client emulation caches
        // the connection's original escape mode and cannot track a later session sql_mode change.
        try(var connection=DriverManager.getConnection(System.getProperty("datahub.test.oceanbase.url")+"?useServerPrepStmts=true","root@test","");var sql=connection.createStatement()) {
            sql.setQueryTimeout(15);sql.execute("SET SESSION sql_mode='"+mode+"'");sql.execute("CREATE DATABASE "+schema);
            try {
                var dialect=DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.OCEANBASE);dialect.prepareDialect(connection);
                var column=new ColumnDescription();column.setFieldName(field);column.setFieldType(Types.INTEGER);column.setPrecisionSize(9);
                column.setScaleSize(0);column.setNullable(true);column.setRemarks(columnText);
                sql.execute(GenerateSqlUtils.getDDLCreateTableSQL(dialect,List.of(column),List.of(),schema,table,true,tableText,false,Map.of()));
                try(var query=connection.prepareStatement("SELECT TABLE_COMMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
                    query.setString(1,schema);query.setString(2,table);
                    try(var rows=query.executeQuery()){assertTrue(rows.next());assertEquals(tableText,rows.getString(1));assertFalse(rows.next());}
                }
                try(var query=connection.prepareStatement("SELECT COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND COLUMN_NAME=?")) {
                    query.setString(1,schema);query.setString(2,table);query.setString(3,field);
                    try(var rows=query.executeQuery()){assertTrue(rows.next());assertEquals(columnText,rows.getString(1));assertFalse(rows.next());}
                }
            } finally {sql.execute("DROP DATABASE "+schema);}
        }
    }

    @Test void exactNativeDriverReadsMetadataAndRetainsCallerOwnership() throws Exception {
        Class.forName(ProductTypeEnum.OCEANBASE.getDriveClassName());
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.oceanbase.url"), "root@test", "");
             var statement = connection.createStatement()) {
            statement.setQueryTimeout(15);
            assertEquals("OceanBase", connection.getMetaData().getDatabaseProductName());
            assertTrue(connection.getMetaData().getDriverVersion().startsWith("2.4.12"));
            assertTrue(OceanbaseUtils.isOceanBaseUseMysqlMode(connection));
            assertTrue(connection.getAutoCommit()); assertFalse(connection.isClosed());
            try (var rows = statement.executeQuery("SELECT VERSION(),@@ob_compatibility_mode,@@ob_read_consistency")) {
                assertTrue(rows.next()); assertEquals("5.7.25-OceanBase_CE-v4.3.5.6", rows.getString(1));
                assertEquals("MYSQL", rows.getString(2)); assertEquals("STRONG", rows.getString(3));
            }
            // A successful mode/metadata probe is not sufficient relation-generation or key evidence.
            var database = cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.OCEANBASE);
            assertThrows(SQLFeatureNotSupportedException.class, () -> database.queryRelationFingerprint(connection, "unproven"));
            assertThrows(UnsupportedOperationException.class, () -> database.integerKeyProjection("k", "id"));
        }
    }

    @Test void tableIdAloneDoesNotDetectTruncationAndTemporaryNamesNeedActualResolution() throws Exception {
        Class.forName(ProductTypeEnum.OCEANBASE.getDriveClassName());
        String schema = "ob_native_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getProperty("datahub.test.oceanbase.url"), "root@test", "");
             var sql = connection.createStatement()) {
            sql.setQueryTimeout(15); sql.execute("CREATE DATABASE " + schema);
            try {
                sql.execute("CREATE TABLE " + schema + ".sample(id BIGINT PRIMARY KEY,val VARCHAR(40))");
                var before = generation(sql, schema);
                sql.execute("INSERT INTO " + schema + ".sample VALUES(1,'x')");
                assertArrayEquals(before, generation(sql, schema));
                sql.execute("TRUNCATE TABLE " + schema + ".sample");
                var after = generation(sql, schema);
                assertEquals(before[0], after[0], "native table ID stays unchanged after TRUNCATE");
                assertNotEquals(before[1], after[1], "tablet generation is necessary");
                sql.execute("CREATE TEMPORARY TABLE " + schema + ".sample(id BIGINT)");
                try {
                    try (var rows = sql.executeQuery("SHOW CREATE TABLE " + schema + ".sample")) {
                        assertTrue(rows.next()); assertTrue(rows.getString(2).startsWith("CREATE TEMPORARY TABLE"));
                    }
                } finally { sql.execute("DROP TEMPORARY TABLE " + schema + ".sample"); }
                assertArrayEquals(after, generation(sql, schema));
            } finally { sql.execute("DROP DATABASE " + schema); }
        }
    }

    private String[] generation(Statement sql, String schema) throws SQLException {
        try (var rows = sql.executeQuery("SELECT TABLE_ID,TABLET_ID FROM oceanbase.DBA_OB_TABLE_LOCATIONS"
                + " WHERE DATABASE_NAME='" + schema + "' AND TABLE_NAME='sample' AND TABLE_TYPE='USER TABLE'")) {
            assertTrue(rows.next());
            String[] result = {rows.getString(1), rows.getString(2)};
            assertTrue(result[0].matches("[1-9][0-9]*")); assertTrue(result[1].matches("[1-9][0-9]*"));
            assertFalse(rows.next()); return result;
        }
    }
}


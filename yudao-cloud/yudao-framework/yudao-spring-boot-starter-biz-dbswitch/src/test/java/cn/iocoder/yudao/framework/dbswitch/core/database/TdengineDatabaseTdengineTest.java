package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="datahub.test.tdengine.url",matches="jdbc:TAOS-RS://127[.]0[.]0[.]1:[0-9]+/")
@EnabledIfEnvironmentVariable(named="DATAHUB_TDENGINE_TEST_PASSWORD",matches=".+")
class TdengineDatabaseTdengineTest {
    private Connection connection;
    private Statement sql;
    private String schema;
    private IDatabaseInterface database;

    @BeforeEach void setup() throws Exception {
        Class.forName("com.taosdata.jdbc.rs.RestfulDriver");
        connection=DriverManager.getConnection(System.getProperty("datahub.test.tdengine.url"),"root",
                System.getenv("DATAHUB_TDENGINE_TEST_PASSWORD"));
        sql=connection.createStatement(); sql.setQueryTimeout(10);
        schema="q11td_"+UUID.randomUUID().toString().replace("-","");
        sql.execute("CREATE DATABASE "+schema+" VGROUPS 1 BUFFER 8");
        sql.execute("CREATE TABLE "+schema+".sample(ts TIMESTAMP,id BIGINT,org_code INT,amount BIGINT)");
        database=cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.TDENGINE);
    }
    @AfterEach void cleanup() throws Exception {
        try {if(sql!=null&&schema!=null) sql.execute("DROP DATABASE IF EXISTS "+schema);}
        finally {if(sql!=null)sql.close();if(connection!=null)connection.close();}
    }
    @Test void metadataAndDdlDescribeTheActualTable() throws Exception {
        assertTrue(database.querySchemaList(connection).contains(schema));
        assertTrue(database.queryTableList(connection,schema).stream().anyMatch(t->"sample".equals(t.getTableName())));
        assertEquals(Set.of("ts","id","org_code","amount"),new HashSet<>(database.queryTableColumnName(connection,schema,"sample")));
        assertEquals(List.of("ts"),database.queryTablePrimaryKeys(connection,schema,"sample"));
        String ddl=database.getTableDDL(connection,schema,"sample");
        assertNotNull(ddl);assertTrue(ddl.toUpperCase(Locale.ROOT).contains("CREATE TABLE"),ddl);
        assertTrue(ddl.contains("amount"),ddl);
    }
    @Test void jdbcPreservesLargeIntegersAndNativeSessionIdentity() throws Exception {
        sql.execute("INSERT INTO "+schema+".sample VALUES(now,9007199254740992,9,NULL)(now+1s,9007199254740993,9,1)");
        try(var rows=sql.executeQuery("SELECT id FROM "+schema+".sample ORDER BY ts")){
            assertTrue(rows.next());assertEquals(9007199254740992L,rows.getLong(1));
            assertTrue(rows.next());assertEquals(9007199254740993L,rows.getLong(1));assertFalse(rows.next());
        }
        try(var rows=sql.executeQuery("SELECT CAST(id AS VARCHAR(64)),name,version FROM information_schema.ins_cluster")){
            assertTrue(rows.next());assertTrue(rows.getString(1).matches("[1-9][0-9]+"));
            assertFalse(rows.getString(2).isBlank());assertEquals("community",rows.getString(3));assertFalse(rows.next());
        }
        assertTrue(connection.getAutoCommit());assertFalse(connection.isClosed());
    }
    @Test void ordinaryTableIdentityIsStableForWritesAndChangesAfterRecreation() throws Exception {
        String first=database.queryRelationFingerprint(connection,schema+".sample");assertNotNull(first);
        sql.execute("INSERT INTO "+schema+".sample VALUES(now,1,9,NULL)");
        assertEquals(first,database.queryRelationFingerprint(connection,schema+".sample"));
        sql.execute("DROP TABLE "+schema+".sample");
        sql.execute("CREATE TABLE "+schema+".sample(ts TIMESTAMP,id BIGINT,org_code INT,amount BIGINT)");
        assertNotEquals(first,database.queryRelationFingerprint(connection,schema+".sample"));
    }

    @Test void compositePrimaryKeyMetadataDoesNotOmitTheSecondComponent() throws Exception {
        sql.execute("CREATE TABLE "+schema+".composite_sample(ts TIMESTAMP,id BIGINT COMPOSITE KEY,amount BIGINT)");
        assertEquals(List.of("ts","id"),database.queryTablePrimaryKeys(connection,schema,"composite_sample"));
    }

    @Test void independentConnectionsAndDefaultDatabaseResolveTheSamePhysicalTable() throws Exception {
        String fingerprint=database.queryRelationFingerprint(connection,schema+".sample");
        try(var other=DriverManager.getConnection(System.getProperty("datahub.test.tdengine.url")+schema,"root",
                System.getenv("DATAHUB_TDENGINE_TEST_PASSWORD"))) {
            assertEquals(fingerprint,database.queryRelationFingerprint(other,"sample"));
            assertTrue(other.getAutoCommit());assertFalse(other.isClosed());
        }
        assertThrows(SQLFeatureNotSupportedException.class,()->database.queryRelationFingerprint(connection,"sample"));
    }

    @Test void stableAndChildTablesDoNotPretendToBeOrdinaryTables() throws Exception {
        sql.execute("CREATE STABLE "+schema+".metrics(ts TIMESTAMP,id BIGINT) TAGS(region INT)");
        sql.execute("CREATE TABLE "+schema+".child_sample USING "+schema+".metrics TAGS(9)");
        for(String table:List.of("metrics","child_sample","absent"))
            assertThrows(SQLFeatureNotSupportedException.class,()->database.queryRelationFingerprint(connection,schema+"."+table));
    }

    @ParameterizedTest @CsvSource({"SMALLINT,-32768,5", "INT,-2147483648,4", "BIGINT,-9223372036854775808,-5",
            "BIGINT,9223372036854775807,-5", "BIGINT,9007199254740993,-5", "TINYINT,1,0",
            "BIGINT UNSIGNED,18446744073709551615,0", "DOUBLE,1.5,0", "VARCHAR(128),'text',0", "BOOL,true,0"})
    void projectedPayloadRetainsNativeSignedType(String type,String value,int expectedType) throws Exception {
        sql.execute("CREATE TABLE "+schema+".keys(ts TIMESTAMP,id "+type+")");
        sql.execute("INSERT INTO "+schema+".keys VALUES(now,"+(type.startsWith("VARCHAR") ? "'"+value+"'" : value)+")");
        var projection=database.integerKeyProjection(null,"id");
        try(var rows=sql.executeQuery("SELECT "+projection.valueSql()+","+projection.typeSql()+" FROM "+schema+".keys")){
            assertTrue(rows.next()); assertEquals(expectedType,projection.jdbcType(rows.getMetaData(),2));
            assertNull(rows.getObject(2));
            if(expectedType!=0)assertEquals(value,rows.getString(1));
            assertFalse(rows.next());
        }
    }

    @Test void boundedProjectionDoesNotTruncateValidIntegersAndNullRemainsNull() throws Exception {
        sql.execute("INSERT INTO "+schema+".sample VALUES(now,123,9,NULL)(now+1s,NULL,9,NULL)");
        var component=database.keyComponentProjection(null,"id",2);
        try(var rows=sql.executeQuery("SELECT "+component.valueSql()+","+component.typeSql()+" FROM "+schema+".sample")) {
            int count=0;while(rows.next()){count++;assertNull(rows.getString(1));assertEquals(Types.BIGINT,component.jdbcType(rows.getMetaData(),2));}
            assertEquals(2,count);
        }
    }

    @Test void readOnlyAccountNeedsVisibleNativeMetadataAndRevocationPreventsIdentity() throws Exception {
        String user="q11_"+UUID.randomUUID().toString().replace("-","").substring(0,16);
        String password=UUID.randomUUID().toString().replace("-","")+"aA!1";
        try {sql.execute("CREATE USER "+user+" PASS '"+password+"' SYSINFO 1");}
        catch(SQLException ex){throw new SQLException("Disposable test account creation failed",ex.getSQLState());}
        try {
            sql.execute("GRANT READ ON "+schema+".* TO "+user);
            try(var reader=DriverManager.getConnection(System.getProperty("datahub.test.tdengine.url"),user,password)) {
                String readIdentity=database.queryRelationFingerprint(reader,schema+".sample");
                assertNotEquals(database.queryRelationFingerprint(connection,schema+".sample"),readIdentity);
                assertEquals(readIdentity,database.queryRelationFingerprint(reader,schema+".sample"));
                sql.execute("REVOKE READ ON "+schema+".* FROM "+user);
                assertThrows(SQLException.class,()->database.queryRelationFingerprint(reader,schema+".sample"));
                sql.execute("GRANT READ ON "+schema+".* TO "+user);
                sql.execute("ALTER USER "+user+" SYSINFO 0");
                assertThrows(SQLException.class,()->database.queryRelationFingerprint(reader,schema+".sample"));
            }
        } finally {sql.execute("DROP USER "+user);}
    }
}

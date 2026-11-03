package cn.iocoder.yudao.framework.dbswitch.core.database;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Disposable Oracle Free PDB only; no existing schema or business data is used. */
@EnabledIfSystemProperty(named="datahub.test.oracle.url",matches="jdbc:oracle:thin:@//127[.]0[.]0[.]1:[0-9]+/FREEPDB1")
class OracleDatabaseOracleTest {
    private final IDatabaseInterface database=cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry.discover().metadata(ProductTypeEnum.ORACLE);
    private Connection admin;
    private String owner,other,reader,password;
    @BeforeEach void setup()throws Exception{
        String id=UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase(Locale.ROOT);
        owner="Q28_"+id;other="Q28B_"+id;reader="Q28R_"+id;password="Q28x"+UUID.randomUUID().toString().replace("-","");
        admin=DriverManager.getConnection(url(),"SYSTEM",System.getenv("DATAHUB_TEST_ORACLE_PASSWORD"));
        for(String user:List.of(owner,other,reader)){
            sql("CREATE USER "+user+" IDENTIFIED BY "+password+" DEFAULT TABLESPACE CODEX_Q11 QUOTA 20M ON CODEX_Q11");
            sql("GRANT CREATE SESSION TO "+user);
        }
        sql("GRANT CREATE TABLE, CREATE VIEW TO "+owner);sql("GRANT CREATE TABLE TO "+other);
    }
    @AfterEach void cleanup()throws Exception{
        if(admin!=null)try{for(String user:List.of(reader,other,owner))sql("DROP USER IF EXISTS "+user+" CASCADE");}finally{admin.close();}
    }

    @ParameterizedTest @CsvSource(delimiter='|',value={"NUMBER(19,0)|-9223372036854775808|9223372036854775807","NUMBER(38,0)|9007199254740992|9007199254740993","NUMBER(1,0)|-9|9"})
    void preservesIntegralTypeAndFullInt64WithoutNlsLoss(String type,String low,String high)throws Exception{
        sql("CREATE TABLE "+owner+".SAMPLE(ID "+type+")");
        sql("INSERT INTO "+owner+".SAMPLE VALUES("+low+")");sql("INSERT INTO "+owner+".SAMPLE VALUES("+high+")");
        sql("ALTER SESSION SET NLS_NUMERIC_CHARACTERS=',.'");
        var text=database.integerKeyProjection(admin,"k","ID");
        var binary=database.keyComponentProjection(admin,"k","ID",256);
        try(var s=admin.createStatement();var r=s.executeQuery("SELECT "+text.valueSql()+","+text.typeSql()+","+binary.valueSql()+" FROM "+owner+".SAMPLE k ORDER BY ID")){
            assertEquals(Types.BIGINT,text.jdbcType(r.getMetaData(),2));
            for(String expected:List.of(low,high)){assertTrue(r.next());assertEquals(expected,r.getString(1));assertNull(r.getObject(2));assertEquals(expected,new String(r.getBytes(3),java.nio.charset.StandardCharsets.US_ASCII));}
            assertFalse(r.next());
        }
    }

    @ParameterizedTest @CsvSource(delimiter='|',value={"NUMBER|1.5","NUMBER(19,2)|1.25","NUMBER(19,-2)|100","FLOAT|1","BINARY_DOUBLE|1","VARCHAR2(50)|'1'"})
    void nativeUncastCaseRejectsFractionalUnboundedFloatingAndText(String type,String value)throws Exception{
        sql("CREATE TABLE "+owner+".SAMPLE(ID "+type+")");sql("INSERT INTO "+owner+".SAMPLE VALUES("+value+")");
        var projection=database.integerKeyProjection(admin,"k","ID");
        try(var s=admin.createStatement();var r=s.executeQuery("SELECT "+projection.typeSql()+" FROM "+owner+".SAMPLE k")){
            assertEquals(0,projection.jdbcType(r.getMetaData(),1));assertTrue(r.next());assertNull(r.getObject(1));
        }
    }

    @Test void oversizedNumberIsNotTruncatedOrRoundedToLong()throws Exception{
        sql("CREATE TABLE "+owner+".SAMPLE(ID NUMBER(38,0))");
        sql("INSERT INTO "+owner+".SAMPLE VALUES(9223372036854775808)");
        var projection=database.integerKeyProjection(admin,"k","ID");
        try(var s=admin.createStatement();var r=s.executeQuery("SELECT "+projection.valueSql()+","+projection.typeSql()+" FROM "+owner+".SAMPLE k")){
            assertEquals(Types.BIGINT,projection.jdbcType(r.getMetaData(),2));assertTrue(r.next());assertNull(r.getString(1));
        }
    }

    @Test void metadataIsExactOwnerOrderedAndLongDefaultsReadOnce()throws Exception{
        for(String schema:List.of(owner,other)){
            sql("CREATE TABLE "+schema+".SAMPLE(ID NUMBER(19,0), PART NUMBER(19,0), VALUE_COL VARCHAR2(200) DEFAULT 'x', CONSTRAINT SAME_PK PRIMARY KEY(PART,ID))");
            sql("COMMENT ON TABLE "+schema+".SAMPLE IS '"+schema+"'");
            sql("COMMENT ON COLUMN "+schema+".SAMPLE.VALUE_COL IS '"+schema+"'");
        }
        sql("CREATE VIEW "+owner+".SAMPLE_VIEW AS SELECT ID,PART FROM "+owner+".SAMPLE");
        assertEquals(owner,database.queryTableList(admin,owner).stream().filter(x->x.getTableName().equals("SAMPLE")).findFirst().orElseThrow().getRemarks());
        assertTrue(database.queryTableList(admin,owner+"' OR 1=1 --").isEmpty());
        var fields=database.queryTableColumnMeta(admin,owner,"SAMPLE");
        assertEquals(List.of("ID","PART","VALUE_COL"),fields.stream().map(x->x.getFieldName()).toList());
        assertEquals(owner,fields.get(2).getRemarks());assertEquals("'x'",fields.get(2).getDefaultValue().trim());
        assertEquals(List.of("PART","ID"),database.queryTablePrimaryKeys(admin,owner,"SAMPLE"));
        assertTrue(database.queryTablePrimaryKeys(admin,owner,"SAMPLE' OR 1=1 --").isEmpty());
        assertEquals(2,database.queryTableColumnMetaOnly(admin,owner,"SAMPLE_VIEW").size());
        fields.get(2).setRemarks(null);database.setColumnDefaultValue(admin,owner,"SAMPLE",fields);assertEquals(owner,fields.get(2).getRemarks());
        try(var ownerConnection=DriverManager.getConnection(url(),owner,password)){
            assertTrue(database.getTableDDL(ownerConnection,owner,"SAMPLE").contains("SAMPLE"));
            assertTrue(database.getViewDDL(ownerConnection,owner,"SAMPLE_VIEW").contains("SAMPLE_VIEW"));
            assertThrows(RuntimeException.class,()->database.getTableDDL(ownerConnection,owner,"SAMPLE' OR 1=1 --"));
        }
    }

    @Test void quotedNamesAndMetadataOnlyQueriesDoNotEvaluateBusinessRows()throws Exception{
        String table=database.getQuotedSchemaTableCombination(owner,"ta'ble");
        sql("CREATE TABLE "+table+"(\"v'col\" NUMBER DEFAULT 7)");
        assertEquals("v'col",database.queryTableColumnMeta(admin,owner,"ta'ble").getFirst().getFieldName());
        sql("CREATE VIEW "+owner+".ERROR_VIEW AS SELECT 1/0 AS BAD FROM DUAL");
        assertEquals(1,database.queryTableColumnMeta(admin,owner,"ERROR_VIEW").size());
    }

    @Test void readOnlyRuntimeHonorsRevocationAndCallerTransaction()throws Exception{
        sql("CREATE TABLE "+owner+".SAMPLE(ID NUMBER(19,0) PRIMARY KEY)");sql("GRANT SELECT ON "+owner+".SAMPLE TO "+reader);
        try(var connection=DriverManager.getConnection(url(),reader,password);var s=connection.createStatement()){
            assertEquals(1,database.queryTableList(connection,owner).size());
            assertEquals(1,database.queryTableColumnMeta(connection,owner,"SAMPLE").size());
            assertEquals(List.of("ID"),database.queryTablePrimaryKeys(connection,owner,"SAMPLE"));
            assertThrows(SQLException.class,()->s.execute("UPDATE "+owner+".SAMPLE SET ID=2"));
            connection.setAutoCommit(false);database.queryTableColumnMeta(connection,owner,"SAMPLE");assertFalse(connection.getAutoCommit());connection.rollback();connection.setAutoCommit(true);
            sql("REVOKE SELECT ON "+owner+".SAMPLE FROM "+reader);
            assertThrows(RuntimeException.class,()->database.queryTableColumnMeta(connection,owner,"SAMPLE"));
            assertTrue(database.queryTableList(connection,owner).isEmpty());assertFalse(connection.isClosed());
        }
    }
    private void sql(String text)throws SQLException{
        try(var s=admin.createStatement()){s.setQueryTimeout(30);s.execute(text);}
        catch(SQLException failure){if(text.startsWith("CREATE USER "))throw new SQLException("Fixture user creation failed",failure.getSQLState(),failure.getErrorCode());throw failure;}
    }
    private static String url(){return System.getProperty("datahub.test.oracle.url");}
}

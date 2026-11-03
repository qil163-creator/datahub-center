package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.config.ConfiguredJdbcDrivers;
import cn.iocoder.yudao.framework.dbswitch.common.config.DbSwitchOptions;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.config.DbSwitchProperties;
import cn.iocoder.yudao.framework.dbswitch.core.model.JdbcSelectResult;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real openGauss with its isolated driver; PostgreSQL syntax does not imply PostgreSQL write semantics. */
@Timeout(60)
@EnabledIfSystemProperty(named="datahub.test.opengauss.url",matches="jdbc:opengauss://127[.]0[.]0[.]1:[0-9]+/.*")
@EnabledIfSystemProperty(named="datahub.test.opengauss.driver",matches=".+")
class JdbcScriptExecutionOpenGaussTest {
    private static final ProductTypeEnum PRODUCT=ProductTypeEnum.OPENGAUSS;
    private DbSwitchRuntime runtime;
    private ConfiguredJdbcDrivers drivers;
    private Connection control;
    private String schema;
    @BeforeEach void setup() throws Exception {
        Path jar=Path.of(System.getProperty("datahub.test.opengauss.driver")).toRealPath();
        var driver=new DbSwitchProperties.Driver();driver.setPath(jar.toString());
        driver.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))));
        drivers=new ConfiguredJdbcDrivers(Map.of(PRODUCT,driver));runtime=new DbSwitchRuntime(DbSwitchProductRegistry.discover(),DbSwitchOptions.defaults(),drivers);
        control=open();try(var s=control.createStatement();var r=s.executeQuery("SELECT version()")){assertTrue(r.next());assertTrue(r.getString(1).toLowerCase(Locale.ROOT).contains("opengauss"));}
        schema="script_og_"+UUID.randomUUID().toString().replace("-","");sql("CREATE SCHEMA "+q(schema));
        sql("CREATE TABLE "+table()+"(id BIGINT PRIMARY KEY,value NUMERIC(38,18),note VARCHAR(100))");
    }
    @AfterEach void cleanup() throws Exception {
        try{if(control!=null)try(var owned=control){if(schema!=null)sql("DROP SCHEMA "+q(schema)+" CASCADE");}}
        finally{if(runtime!=null)runtime.close();if(drivers!=null)drivers.close();}
    }
    @Test void positionalParametersAndNativeDollarQuotesSurvivePlanning() throws Exception {
        var value=new BigDecimal("12345678901234567890.123456789012345678");
        var plan=SqlScriptPlan.parse(PRODUCT,"INSERT INTO "+table()+" VALUES(?,?,?);SELECT $tag$literal; ' \"$tag$ AS text_value,value FROM "+table()+" WHERE id=?;");
        assertEquals(List.of(3,1),plan.steps().stream().map(SqlScriptPlan.Step::parameterCount).toList());
        var result=DbSwitchProductRegistry.discover().metadata(PRODUCT).executePlan(control,plan,List.of(9007199254740993L,value,"中文';",9007199254740993L),10,null,ignored->{});
        assertTrue(result.getSuccess());assertEquals(2,result.getResults().size());
        assertEquals("literal; ' \"",result.getResults().get(1).getRowData().getFirst().get("text_value"));
        try(var s=control.createStatement();var r=s.executeQuery("SELECT value,note FROM "+table())){
            assertTrue(r.next());assertEquals(value,r.getBigDecimal(1));assertEquals("中文';",r.getString(2));assertFalse(r.next());
        }
    }
    @Test void callerTransactionCanRollbackWithoutLeakingVisibility() throws Exception {
        try(var connection=open();var observer=open()){
            connection.setAutoCommit(false);
            var result=DbSwitchProductRegistry.discover().metadata(PRODUCT).executeBySql(connection,"opengauss",insert(1),10,null);
            assertTrue(result.getSuccess());assertEquals(JdbcSelectResult.TransactionOutcome.NOT_MANAGED,result.getTransactionOutcome());
            assertFalse(connection.getAutoCommit());assertEquals(1,count(connection));assertEquals(0,count(observer));
            connection.rollback();assertEquals(0,count(observer));assertFalse(connection.isClosed());
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void dollarStringValueDoesNotDependOnBackslashSessionMode(boolean standard) throws Exception {
        sql("SET standard_conforming_strings="+(standard?"on":"off"));
        String value="slash\\'; literal; $other$ 中文";
        var plan=SqlScriptPlan.parse(PRODUCT,"SELECT $text$"+value+"$text$::text AS exact_value");
        var result=DbSwitchProductRegistry.discover().metadata(PRODUCT).executePlan(control,plan,List.of(),10,null,ignored->{});
        assertTrue(result.getSuccess());assertEquals(value,result.getResults().getFirst().getRowData().getFirst().get("exact_value"));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void ownedDmlAndDdlCommitOrRollbackTogether(boolean fail) throws Exception {
        String ddl="CREATE TABLE "+q(schema)+".created(id INT);";
        var result=runtime.jdbcMetadata(PRODUCT).executeBySql(url(),"opengauss",user(),password(),insert(1)+ddl+(fail?insert(1):insert(2)),1,10,null);
        assertEquals(!fail,result.getSuccess());
        assertEquals(fail?JdbcSelectResult.TransactionOutcome.ROLLED_BACK:JdbcSelectResult.TransactionOutcome.COMMITTED,result.getTransactionOutcome());
        assertEquals(fail?0:2,count(control));
        try(var s=control.prepareStatement("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname='created'")){
            s.setString(1,schema);try(var r=s.executeQuery()){assertTrue(r.next());assertEquals(fail?0:1,r.getInt(1));}
        }
    }
    @Test void incompleteScriptAndWritesAreNeverClassifiedAsReadOnly() throws Exception {
        for(String tail:List.of("SELECT $tag$unfinished", "\nGO\n", "BEGIN;", "SELECT '"))
            assertThrows(IllegalArgumentException.class,()->SqlScriptPlan.parse(PRODUCT,insert(1)+tail));
        assertEquals(0,count(control));
        assertTrue(SqlStatementClassifier.classify(PRODUCT,"SELECT 1::integer").readOnlyQuery());
        assertFalse(SqlStatementClassifier.classify(PRODUCT,"SELECT * FROM "+table()+" FOR UPDATE").readOnlyQuery());
        assertThrows(IllegalArgumentException.class,()->SqlStatementClassifier.assertReadOnlyQuery(PRODUCT,
                "WITH removed AS (DELETE FROM "+table()+" RETURNING id) SELECT * FROM removed"));
    }
    private String insert(int id){return "INSERT INTO "+table()+" VALUES("+id+",-0.000000000000000001,'x;y');";}
    private int count(Connection connection)throws SQLException {try(var s=connection.createStatement();var r=s.executeQuery("SELECT count(*) FROM "+table())){assertTrue(r.next());return r.getInt(1);}}
    private String table(){return q(schema)+"."+q("rows\"; name");}
    private static String q(String name){return '"'+name.replace("\"","\"\"")+'"';}
    private static String url(){return System.getProperty("datahub.test.opengauss.url");}
    private static String user(){return System.getProperty("datahub.test.opengauss.user","dataforge_test");}
    private static String password(){return Objects.requireNonNull(System.getenv("DATAHUB_OPENGAUSS_TEST_PASSWORD"));}
    private Connection open()throws SQLException{return runtime.openConnection(PRODUCT,null,url(),user(),password());}
    private void sql(String sql)throws SQLException{try(var s=control.createStatement()){s.setQueryTimeout(20);s.execute(sql);}}
}

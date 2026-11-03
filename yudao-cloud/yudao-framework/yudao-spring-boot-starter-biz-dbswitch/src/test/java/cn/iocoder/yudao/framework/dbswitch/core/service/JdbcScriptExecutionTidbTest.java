package cn.iocoder.yudao.framework.dbswitch.core.service;

import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.model.JdbcSelectResult;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.spi.DbSwitchProductRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** TiDB SQL/PD/TiKV fixture: parser admission is followed by native execution and transaction evidence. */
@Timeout(60)
@EnabledIfSystemProperty(named="datahub.test.tidb.url",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/.*")
class JdbcScriptExecutionTidbTest {
    private static final ProductTypeEnum PRODUCT=ProductTypeEnum.TIDB;
    private Connection control;
    private String schema;
    @BeforeEach void setup() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");control=open();
        try(var statement=control.createStatement();var rows=statement.executeQuery("SELECT tidb_version()")) {
            assertTrue(rows.next());assertTrue(rows.getString(1).contains("Release Version:"));
        }
        schema="script_tidb_"+UUID.randomUUID().toString().replace("-","");
        sql("CREATE DATABASE "+q(schema));sql("CREATE TABLE "+table()+"(id BIGINT PRIMARY KEY,value DECIMAL(38,18),note VARCHAR(100))");
    }
    @AfterEach void cleanup() throws Exception {if(control!=null)try(var owned=control){if(schema!=null)sql("DROP DATABASE "+q(schema));}}

    @Test void renderedParametersAndQuotedNamesKeepExactValues() throws Exception {
        var value=new BigDecimal("12345678901234567890.123456789012345678");
        var plan=SqlScriptPlan.parse(PRODUCT,"INSERT INTO "+table()+" VALUES(?,?,?); SELECT * FROM "+table()+" WHERE id=?;");
        assertEquals(List.of(3,1),plan.steps().stream().map(SqlScriptPlan.Step::parameterCount).toList());
        var result=DbSwitchProductRegistry.discover().metadata(PRODUCT).executePlan(control,plan,
                List.of(9007199254740993L,value,"quoted'; ;中文",9007199254740993L),10,null,ignored->{});
        assertTrue(result.getSuccess());assertEquals(2,result.getResults().size());
        try(var statement=control.createStatement();var rows=statement.executeQuery("SELECT id,value,note FROM "+table())) {
            assertTrue(rows.next());assertEquals(9007199254740993L,rows.getLong(1));assertEquals(value,rows.getBigDecimal(2));
            assertEquals("quoted'; ;中文",rows.getString(3));assertFalse(rows.next());
        }
    }

    @Test void callerTransactionRetainsOwnershipAndRollback() throws Exception {
        try(var connection=open();var observer=open()) {
            connection.setAutoCommit(false);
            var result=DbSwitchProductRegistry.discover().metadata(PRODUCT).executeBySql(connection,"tidb",insert(1),10,null);
            assertTrue(result.getSuccess());assertEquals(JdbcSelectResult.TransactionOutcome.NOT_MANAGED,result.getTransactionOutcome());
            assertFalse(connection.getAutoCommit());assertFalse(connection.isClosed());assertEquals(1,count(connection));assertEquals(0,count(observer));
            connection.rollback();assertEquals(0,count(observer));
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void ownedDmlTransactionCommitsOrRollsBackTheWholeScript(boolean duplicate) throws Exception {
        try(var runtime=DbSwitchRuntime.standalone()) {
            var result=runtime.jdbcMetadata(PRODUCT).executeBySql(url(),"tidb","root","",insert(1)+insert(duplicate?1:2),1,10,null);
            assertEquals(!duplicate,result.getSuccess());
            assertEquals(duplicate?JdbcSelectResult.TransactionOutcome.ROLLED_BACK:JdbcSelectResult.TransactionOutcome.COMMITTED,result.getTransactionOutcome());
            assertEquals(duplicate?0:2,count(control));
        }
    }

    @Test void ownedTransactionRejectsImplicitCommitDdlBeforeFirstDml() throws Exception {
        try(var runtime=DbSwitchRuntime.standalone()) {
            assertThrows(UnsupportedOperationException.class,()->runtime.jdbcMetadata(PRODUCT).executeBySql(url(),"tidb","root","",
                    insert(1)+"CREATE TABLE "+q(schema)+".extra(id INT)",1,10,null));
            assertEquals(0,count(control));
        }
    }

    @Test void autoCommitDdlAndDmlExecuteWithoutAnAtomicityClaim() throws Exception {
        String name=q(schema)+"."+q("created`;name");
        var plan=SqlScriptPlan.parse(PRODUCT,"CREATE TABLE "+name+"(id INT PRIMARY KEY);INSERT INTO "+name+" VALUES(1);SELECT * FROM "+name+";");
        var result=DbSwitchProductRegistry.discover().metadata(PRODUCT).executePlan(control,plan,List.of(),10,null,ignored->{});
        assertTrue(result.getSuccess());assertEquals(3,result.getResults().size());
        assertEquals(JdbcSelectResult.TransactionOutcome.NOT_MANAGED,result.getTransactionOutcome());
    }

    @Test void incompleteTailAndClientDirectivesCannotBecomeAnExecutablePrefix() throws Exception {
        for(String suffix:List.of("SELECT '","\nGO\n","BEGIN;", "/*! SELECT 1 */"))
            assertThrows(IllegalArgumentException.class,()->SqlScriptPlan.parse(PRODUCT,insert(1)+suffix));
        assertEquals(0,count(control));
        assertTrue(SqlStatementClassifier.classify(PRODUCT,"SELECT 1").readOnlyQuery());
        assertFalse(SqlStatementClassifier.classify(PRODUCT,"SELECT id FROM "+table()+" FOR UPDATE").readOnlyQuery());
    }
    private String insert(int id){return "INSERT INTO "+table()+" VALUES("+id+",0.000000000000000001,'x;y');";}
    private int count(Connection connection)throws SQLException {try(var s=connection.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table())){assertTrue(r.next());return r.getInt(1);}}
    private String table(){return q(schema)+"."+q("rows`; quoted");}
    private static String q(String name){return '`'+name.replace("`","``")+'`';}
    private static String url(){return System.getProperty("datahub.test.tidb.url");}
    private static Connection open()throws SQLException{return DriverManager.getConnection(url(),"root","");}
    private void sql(String sql)throws SQLException{try(var statement=control.createStatement()){statement.setQueryTimeout(20);statement.execute(sql);}}
}

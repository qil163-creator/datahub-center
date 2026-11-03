package cn.iocoder.yudao.framework.dbswitch.data;

import cn.iocoder.yudao.framework.dbswitch.common.entity.PatternMapper;
import cn.iocoder.yudao.framework.dbswitch.common.type.ProductTypeEnum;
import cn.iocoder.yudao.framework.dbswitch.core.util.OracleSqlNames;
import cn.iocoder.yudao.framework.dbswitch.data.config.MigrationRequest;
import cn.iocoder.yudao.framework.dbswitch.data.entity.SourceDataSourceProperties;
import cn.iocoder.yudao.framework.dbswitch.data.runtime.DbSwitchRuntime;
import cn.iocoder.yudao.framework.dbswitch.data.service.MigrationService;
import cn.iocoder.yudao.framework.dbswitch.dbwriter.UnconfirmedWriteException;
import cn.iocoder.yudao.framework.dbswitch.product.oracle.OracleNativeIndexes;
import cn.iocoder.yudao.framework.dbswitch.product.oracle.OracleNativeTables;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Catalog, DDL and public migration checks in a disposable Oracle 23 PDB schema. */
@Timeout(90)
@EnabledIfSystemProperty(named="datahub.test.oracle.url",matches="jdbc:oracle:thin:@//127[.]0[.]0[.]1:[0-9]+/FREEPDB1")
class OracleIndexesOracleTest {
    private Connection admin;private String schema,secret;private boolean created;
    @BeforeEach void setup()throws Exception {
        admin=DriverManager.getConnection(url(),"SYSTEM",System.getenv("DATAHUB_TEST_ORACLE_PASSWORD"));
        schema="IX_"+UUID.randomUUID().toString().replace("-","").substring(0,20).toUpperCase(Locale.ROOT);
        secret="Ix"+UUID.randomUUID().toString().replace("-","");
        sql("CREATE USER "+q(schema)+" IDENTIFIED BY "+secret+" DEFAULT TABLESPACE CODEX_Q11 QUOTA 30M ON CODEX_Q11");created=true;
        sql("GRANT CREATE SESSION,CREATE TABLE TO "+q(schema));
    }
    @AfterEach void cleanup()throws Exception {if(admin!=null)try(var owned=admin){if(created)sql("DROP USER "+q(schema)+" CASCADE");}}

    @Test void publicFullPreservesCompositeKeysUniqueConstraintsAndInvisibleDescendingIndexes()throws Exception {
        complexSource();sql("INSERT INTO "+t("S")+" VALUES(1,2,'first',10)");sql("INSERT INTO "+t("S")+" VALUES(2,1,NULL,NULL)");
        var request=new MigrationRequest();var source=new SourceDataSourceProperties();source.setSourceProductType(ProductTypeEnum.ORACLE);
        source.setUrl(url());source.setUsername(schema);source.setPassword(secret);source.setSourceSchema(schema);source.setSourceIncludes("S");source.setPoolSize(1);
        source.setRegexTableMapper(List.of(new PatternMapper("^S$","T")));request.getSource().add(source);
        var target=request.getTarget();target.setTargetProductType(ProductTypeEnum.ORACLE);target.setUrl(url());target.setUsername(schema);target.setPassword(secret);
        target.setTargetSchema(schema);target.setPoolSize(1);target.setWriterEngineInsert(true);
        try(var runtime=DbSwitchRuntime.standalone()) {
            var result=new MigrationService(request,ignored->{},null,runtime).run();
            assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());assertEquals(2,result.getTableResultList().getFirst().getTransferSummary().acknowledgedRows());
        }
        assertEquals(indexes("S"),indexes("T"));assertEquals(constraints("S"),constraints("T"));
        assertEquals(2,count("SELECT COUNT(*) FROM "+t("T")));
        assertThrows(SQLException.class,()->sql("INSERT INTO "+t("T")+" VALUES(8,9,'first',20)"));
        assertThrows(SQLException.class,()->sql("INSERT INTO "+t("T")+" VALUES(1,2,'new',20)"));
        sql("INSERT INTO "+t("T")+" VALUES(3,3,NULL,NULL)"); // Ordinary UNIQUE retains Oracle NULL semantics.
        sql("UPDATE "+t("S")+" SET C='changed' WHERE A=1");
        sql("DELETE FROM "+t("S")+" WHERE A=2");sql("INSERT INTO "+t("S")+" VALUES(4,4,'inserted',40)");
        target.setChangeDataSync(true);target.setChangeDataSyncType("PK_ORDER_COMPARE");
        try(var runtime=DbSwitchRuntime.standalone()) {
            var result=new MigrationService(request,ignored->{},null,runtime).run();assertTrue(result.getIfAllSuccess().get(),()->result.getTableResultList().toString());
        }
        assertEquals(2,count("SELECT COUNT(*) FROM "+t("T")));assertEquals("changed",scalar("SELECT C FROM "+t("T")+" WHERE A=1"));
        assertEquals("inserted",scalar("SELECT C FROM "+t("T")+" WHERE A=4"));assertEquals(indexes("S"),indexes("T"));
    }

    @Test void standaloneUniqueIndexAndItsColumnOrderArePreserved()throws Exception {
        sql("CREATE TABLE "+t("S")+"(A NUMBER,B NUMBER)");sql("CREATE UNIQUE INDEX "+t("standalone index")+" ON "+t("S")+"(B,A) INVISIBLE");
        OracleNativeTables.plan(ds(),OracleNativeTables.describe(ds(),schema,"S",15),schema,"T",Map.of("A","A","B","B"),false,15).execute();
        assertEquals(indexes("S"),indexes("T"));assertTrue(constraintColumns("T","U").isEmpty());
        sql("INSERT INTO "+t("T")+" VALUES(1,2)");assertThrows(SQLException.class,()->sql("INSERT INTO "+t("T")+" VALUES(1,2)"));
    }

    @Test void columnMappingPreservesOrderDirectionVisibilityAndBackingIndex()throws Exception {
        complexSource();var mapping=Map.of("A","renamed A","B","renamed B","C","renamed C","D","renamed D");
        var plan=OracleNativeTables.plan(ds(),OracleNativeTables.describe(ds(),schema,"S",15),schema,"T",mapping,false,15);plan.execute();
        var sourceIndexes=indexes("S");var targetIndexes=indexes("T");
        assertEquals(sourceIndexes.stream().map(s->s.replace("\"A\"","\"renamed A\"").replace("\"B\"","\"renamed B\"").replace("\"C\"","\"renamed C\"").replace("\"D\"","\"renamed D\"")).sorted().toList(),targetIndexes);
        assertEquals(List.of("renamed B","renamed A"),constraintColumns("T","P"));
        assertEquals("NONUNIQUE",scalar("SELECT i.uniqueness FROM sys.all_constraints c JOIN sys.all_indexes i ON i.owner=c.index_owner AND i.index_name=c.index_name WHERE c.owner='"+schema+"' AND c.table_name='T' AND c.constraint_type='P'"));
    }
    @Test void omittingAnIndexedColumnFailsBeforeTargetDdl()throws Exception {
        complexSource();var evidence=OracleNativeTables.describe(ds(),schema,"S",15);
        assertThrows(IllegalArgumentException.class,()->OracleNativeTables.plan(ds(),evidence,schema,"T",Map.of("A","A","B","B","C","C"),false,15));
        assertEquals(0,tableCount("T"));
    }
    @Test void existingTargetMissingUniqueConstraintIsRejectedWithoutTruncate()throws Exception {
        sql("CREATE TABLE "+t("S")+"(A NUMBER PRIMARY KEY,B NUMBER UNIQUE)");sql("CREATE TABLE "+t("T")+"(A NUMBER PRIMARY KEY,B NUMBER)");sql("INSERT INTO "+t("T")+" VALUES(9,9)");
        assertThrows(IllegalStateException.class,()->OracleNativeTables.plan(ds(),OracleNativeTables.describe(ds(),schema,"S",15),schema,"T",Map.of("A","A","B","B"),false,15));
        assertEquals(9,count("SELECT A FROM "+t("T")));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void unrelatedGeneratedNameCollisionIsRejectedBeforeAnyDrop(boolean constraint)throws Exception {
        sql("CREATE TABLE "+t("S")+"(A NUMBER PRIMARY KEY)");sql("CREATE TABLE "+t("T")+"(A NUMBER PRIMARY KEY)");sql("INSERT INTO "+t("T")+" VALUES(9)");
        var evidence=OracleNativeTables.describe(ds(),schema,"S",15);String sourceName=scalar("SELECT constraint_name FROM sys.all_constraints WHERE owner='"+schema+"' AND table_name='S' AND constraint_type='P'");
        String name=constraint?OracleNativeIndexes.constraintName(new OracleNativeIndexes.Constraint(sourceName,"P",List.of("A"),sourceName),schema,"T")
                :OracleNativeIndexes.indexName(new OracleNativeIndexes.Index(sourceName,true,true,List.of(new OracleNativeIndexes.Key("A",false))),schema,"T");
        sql("CREATE TABLE "+t("OTHER")+"(A NUMBER)");
        if(constraint)sql("ALTER TABLE "+t("OTHER")+" ADD CONSTRAINT "+q(name)+" UNIQUE(A)");else sql("CREATE INDEX "+t(name)+" ON "+t("OTHER")+"(A)");
        assertThrows(IllegalStateException.class,()->OracleNativeTables.plan(ds(),evidence,schema,"T",Map.of("A","A"),true,15));assertEquals(9,count("SELECT A FROM "+t("T")));
    }
    @Test void collisionAfterPreflightIsCheckedAgainBeforeDrop()throws Exception {
        sql("CREATE TABLE "+t("S")+"(A NUMBER PRIMARY KEY)");sql("CREATE TABLE "+t("T")+"(A NUMBER PRIMARY KEY)");sql("INSERT INTO "+t("T")+" VALUES(9)");
        var evidence=OracleNativeTables.describe(ds(),schema,"S",15);var plan=OracleNativeTables.plan(ds(),evidence,schema,"T",Map.of("A","A"),true,15);
        String name=scalar("SELECT index_name FROM sys.all_indexes WHERE table_owner='"+schema+"' AND table_name='S'");
        name=OracleNativeIndexes.indexName(new OracleNativeIndexes.Index(name,true,true,List.of(new OracleNativeIndexes.Key("A",false))),schema,"T");
        sql("CREATE TABLE "+t("OTHER")+"(A NUMBER)");sql("CREATE INDEX "+t(name)+" ON "+t("OTHER")+"(A)");
        assertThrows(IllegalStateException.class,plan::execute);assertEquals(9,count("SELECT A FROM "+t("T")));
    }
    @Test void targetIndexVisibilityChangeInvalidatesPreparedPlan()throws Exception {
        sql("CREATE TABLE "+t("S")+"(A NUMBER)");sql("CREATE INDEX "+t("SI")+" ON "+t("S")+"(A)");
        sql("CREATE TABLE "+t("T")+"(A NUMBER)");sql("CREATE INDEX "+t("TI")+" ON "+t("T")+"(A)");sql("INSERT INTO "+t("T")+" VALUES(9)");
        var plan=OracleNativeTables.plan(ds(),OracleNativeTables.describe(ds(),schema,"S",15),schema,"T",Map.of("A","A"),true,15);
        sql("ALTER INDEX "+t("TI")+" INVISIBLE");assertThrows(IllegalStateException.class,plan::execute);assertEquals(9,count("SELECT A FROM "+t("T")));
    }
    @Test void committedIndexWithLostAcknowledgementKeepsTargetIdentityAndCannotReplay()throws Exception {
        sql("CREATE TABLE "+t("S")+"(A NUMBER PRIMARY KEY)");var evidence=OracleNativeTables.describe(ds(),schema,"S",15);
        var plan=OracleNativeTables.plan(lostAcknowledgement("CREATE UNIQUE INDEX"),evidence,schema,"T",Map.of("A","A"),false,15);
        assertThrows(UnconfirmedWriteException.class,plan::execute);assertEquals(1,tableCount("T"));
        assertEquals(1,count("SELECT COUNT(*) FROM sys.all_indexes WHERE table_owner='"+schema+"' AND table_name='T'"));
        assertNotNull(plan.recoveryResources().getFirst().expectedPhysicalIdentity());assertThrows(IllegalStateException.class,plan::execute);
    }
    @Test void confirmedDropThenUnknownCreateNeverReportsOldIdentityAsCurrent()throws Exception {
        sql("CREATE TABLE "+t("S")+"(A NUMBER PRIMARY KEY)");sql("CREATE TABLE "+t("T")+"(A NUMBER PRIMARY KEY)");
        String prior=scalar("SELECT object_id FROM sys.all_objects WHERE owner='"+schema+"' AND object_name='T' AND object_type='TABLE'");
        var plan=OracleNativeTables.plan(lostAcknowledgement("CREATE TABLE"),OracleNativeTables.describe(ds(),schema,"S",15),schema,"T",Map.of("A","A"),true,15);
        assertThrows(UnconfirmedWriteException.class,plan::execute);assertEquals(1,tableCount("T"));
        assertNotEquals(prior,scalar("SELECT object_id FROM sys.all_objects WHERE owner='"+schema+"' AND object_name='T' AND object_type='TABLE'"));
        assertNull(plan.recoveryResources().getFirst().expectedPhysicalIdentity());assertThrows(IllegalStateException.class,plan::execute);
    }
    private DriverManagerDataSource lostAcknowledgement(String prefix) {
        var fault=new DriverManagerDataSource(url(),schema,secret) {
            @Override public Connection getConnection()throws SQLException {
                var actual=super.getConnection();return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    try {var result=method.invoke(actual,args);if(method.getName().equals("createStatement"))return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(p,m,a)->{
                        try{var value=m.invoke(result,a);if(m.getName().equals("execute")&&a[0] instanceof String sql&&sql.startsWith(prefix))throw new SQLException("injected acknowledgement loss","08006");return value;}catch(InvocationTargetException e){throw e.getCause();}});return result;
                    }catch(InvocationTargetException e){throw e.getCause();}});
            }
        };
        return fault;
    }
    @ParameterizedTest @ValueSource(strings={"FUNCTION","BITMAP","COMPRESSED","NOVALIDATE","DEFERRED","CHECK","VIRTUAL","REVERSE","UNUSABLE","PARTITIONED"})
    void unsupportedStructuresAreRecognizedBeforeCreatingTarget(String kind)throws Exception {
        sql("CREATE TABLE "+t("S")+"(A NUMBER,B VARCHAR2(20))");
        switch(kind) {
            case "FUNCTION"->sql("CREATE INDEX "+t("I")+" ON "+t("S")+"(LOWER(B))");
            case "BITMAP"->sql("CREATE BITMAP INDEX "+t("I")+" ON "+t("S")+"(A)");
            case "COMPRESSED"->sql("CREATE INDEX "+t("I")+" ON "+t("S")+"(A,B) COMPRESS 1");
            case "NOVALIDATE"->sql("ALTER TABLE "+t("S")+" ADD CONSTRAINT U UNIQUE(A) ENABLE NOVALIDATE");
            case "DEFERRED"->sql("ALTER TABLE "+t("S")+" ADD CONSTRAINT U UNIQUE(A) DEFERRABLE INITIALLY DEFERRED");
            case "CHECK"->sql("ALTER TABLE "+t("S")+" ADD CONSTRAINT C CHECK(A>0)");
            case "VIRTUAL"->sql("ALTER TABLE "+t("S")+" ADD V GENERATED ALWAYS AS (A+1)");
            case "REVERSE"->sql("CREATE INDEX "+t("I")+" ON "+t("S")+"(A) REVERSE");
            case "UNUSABLE"->{sql("CREATE INDEX "+t("I")+" ON "+t("S")+"(A)");sql("ALTER INDEX "+t("I")+" UNUSABLE");}
            case "PARTITIONED"->sql("CREATE INDEX "+t("I")+" ON "+t("S")+"(A) GLOBAL PARTITION BY RANGE(A)(PARTITION P1 VALUES LESS THAN(MAXVALUE))");
        }
        assertThrows(IllegalStateException.class,()->OracleNativeTables.describe(ds(),schema,"S",15));assertEquals(0,tableCount("T"));
    }
    private void complexSource()throws Exception {
        sql("CREATE TABLE "+t("S")+"(A NUMBER NOT NULL,B NUMBER NOT NULL,C VARCHAR2(20),D NUMBER)");
        sql("CREATE INDEX "+t("backing index")+" ON "+t("S")+"(A,B)");
        sql("ALTER TABLE "+t("S")+" ADD CONSTRAINT "+q("primary key")+" PRIMARY KEY(B,A) USING INDEX "+t("backing index"));
        sql("ALTER TABLE "+t("S")+" ADD CONSTRAINT "+q("unique value")+" UNIQUE(C)");
        sql("CREATE INDEX "+t("descending index")+" ON "+t("S")+"(D DESC,C ASC) INVISIBLE");
    }
    private List<String> indexes(String table)throws Exception {
        var result=new ArrayList<String>();try(var p=admin.prepareStatement("SELECT index_name,uniqueness,visibility FROM sys.all_indexes WHERE table_owner=? AND table_name=?")) {
            p.setString(1,schema);p.setString(2,table);try(var r=p.executeQuery()){while(r.next()){
                String name=r.getString(1);var keys=new ArrayList<String>();try(var k=admin.prepareStatement("SELECT c.column_name,c.descend,e.column_expression FROM sys.all_ind_columns c LEFT JOIN sys.all_ind_expressions e ON e.index_owner=c.index_owner AND e.index_name=c.index_name AND e.column_position=c.column_position WHERE c.index_owner=? AND c.index_name=? ORDER BY c.column_position")) {
                    k.setString(1,schema);k.setString(2,name);try(var kr=k.executeQuery()){while(kr.next()){String field=kr.getString(1),order=kr.getString(2),expression=kr.getString(3);keys.add((expression==null?q(field):expression)+' '+order);}}
                }result.add(r.getString(2)+' '+r.getString(3)+' '+String.join(",",keys));
            }}
        }return result.stream().sorted().toList();
    }
    private List<String> constraints(String table)throws Exception {var result=new ArrayList<String>();for(String type:List.of("P","U"))result.add(type+constraintColumns(table,type));return result;}
    private List<String> constraintColumns(String table,String type)throws Exception {
        var result=new ArrayList<String>();try(var p=admin.prepareStatement("SELECT cc.column_name FROM sys.all_constraints c JOIN sys.all_cons_columns cc ON cc.owner=c.owner AND cc.constraint_name=c.constraint_name WHERE c.owner=? AND c.table_name=? AND c.constraint_type=? ORDER BY cc.position")){
            p.setString(1,schema);p.setString(2,table);p.setString(3,type);try(var r=p.executeQuery()){while(r.next())result.add(r.getString(1));}
        }return result;
    }
    private int tableCount(String table)throws Exception{return count("SELECT COUNT(*) FROM sys.all_tables WHERE owner='"+schema+"' AND table_name='"+table+"'");}
    private int count(String text)throws Exception{return Integer.parseInt(scalar(text));}
    private String scalar(String text)throws Exception{try(var s=admin.createStatement();var r=s.executeQuery(text)){assertTrue(r.next());var value=r.getString(1);assertFalse(r.next());return value;}}
    private void sql(String text)throws SQLException{try(var s=admin.createStatement()){s.setQueryTimeout(15);s.execute(text);}}
    private DriverManagerDataSource ds(){return new DriverManagerDataSource(url(),schema,secret);}
    private String t(String name){return OracleSqlNames.relation(schema,name);}
    private static String q(String name){return OracleSqlNames.quote(name);}
    private static String url(){return System.getProperty("datahub.test.oracle.url");}
}
